package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class KomikV : ParsedHttpSource() {

    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    // Jika environment Anda tidak punya network.cloudflareClient, ubah menjadi OkHttpClient()
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    companion object {
        // menyimpan url yang sudah ditampilkan di sesi runtime agar next page tidak duplicate
        private val seenUrls = mutableSetOf<String>()

        fun resetSeen() {
            seenUrls.clear()
        }
    }

    // ----------------------
    // util JSON/date
    // ----------------------
    private fun tryParseJSONObject(text: String): JSONObject? {
        return try { JSONObject(text) } catch (_: Exception) { null }
    }
    private fun tryParseJSONArray(text: String): JSONArray? {
        return try { JSONArray(text) } catch (_: Exception) { null }
    }

    private fun tryParseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        try {
            val s = dateStr
            return when {
                s.contains("jam lalu", true) -> {
                    val h = s.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    System.currentTimeMillis() - h * 3600L * 1000L
                }
                s.contains("hari lalu", true) -> {
                    val d = s.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    System.currentTimeMillis() - d * 24L * 3600L * 1000L
                }
                else -> {
                    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                    try { df.parse(s)?.time ?: 0L } catch (_: Exception) {
                        val df2 = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        df2.parse(s)?.time ?: 0L
                    }
                }
            }
        } catch (_: Exception) { return 0L }
    }

    // ----------------------
    // heuristik parsing _objs (lebih ketat untuk judul)
    // ----------------------
    private fun parseQDataObjsToManga(root: JSONObject): List<SManga> {
        val out = mutableListOf<SManga>()
        if (!root.has("_objs")) return out
        val arr = root.optJSONArray("_objs") ?: return out
        val n = arr.length()
        if (n == 0) return out

        fun getStringAt(idx: Int): String = if (idx in 0 until n) arr.optString(idx, "") else ""
        val imageRegex = Regex("https?://[^\\s'\"]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\s'\"]*)?")
        val slugRegex = Regex("^[a-z0-9\\-]{3,}$")
        val hasLetterRegex = Regex("\\p{L}")
        val isoDateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}") // avoid timestamps

        var i = 0
        while (i < n) {
            val s = getStringAt(i)

            // if encountering an image directly or within next few entries, use it as anchor
            var imageIdx = -1
            if (s.matches(imageRegex)) {
                imageIdx = i
            } else {
                var j = i
                while (j < minOf(n, i + 6)) {
                    if (getStringAt(j).matches(imageRegex)) { imageIdx = j; break }
                    j++
                }
            }

            if (imageIdx >= 0) {
                // search backward for a good title candidate (prefer up to 6 positions)
                var title = ""
                var titleIdx = -1
                val startSearch = maxOf(0, imageIdx - 6)
                for (k in imageIdx - 1 downTo startSearch) {
                    val cand = getStringAt(k).trim()
                    if (cand.isEmpty()) continue
                    // reject JSON-like strings, timestamps, numeric-only, too-short or control-chars
                    if (cand.startsWith("{") || cand.contains("\":") || cand.contains("id\":") || cand.contains("\"id\"") || isoDateRegex.containsMatchIn(cand)) continue
                    if (cand.length < 4) continue
                    if (!hasLetterRegex.containsMatchIn(cand)) continue
                    // looks good
                    title = cand
                    titleIdx = k
                    break
                }

                // try forward if not found backward
                if (title.isEmpty()) {
                    for (k in imageIdx + 1 .. minOf(n-1, imageIdx + 3)) {
                        val cand = getStringAt(k).trim()
                        if (cand.isEmpty()) continue
                        if (cand.startsWith("{") || cand.contains("\":") || isoDateRegex.containsMatchIn(cand)) continue
                        if (cand.length < 4) continue
                        if (!hasLetterRegex.containsMatchIn(cand)) continue
                        title = cand
                        titleIdx = k
                        break
                    }
                }

                // find slug after image
                var slug = ""
                var slugIdx = -1
                var kk = imageIdx + 1
                while (kk < minOf(n, imageIdx + 10)) {
                    val candidate = getStringAt(kk)
                    if (candidate.matches(slugRegex)) { slug = candidate; slugIdx = kk; break }
                    kk++
                }

                // synopsis: candidate between titleIdx+1 and imageIdx-1 longest non-http string
                var synopsis = ""
                if (titleIdx >= 0 && titleIdx + 1 <= imageIdx - 1) {
                    for (sIdx in (titleIdx + 1) until imageIdx) {
                        val cand = getStringAt(sIdx)
                        if (cand.length > synopsis.length && !cand.startsWith("http") && !cand.startsWith("{") && !isoDateRegex.containsMatchIn(cand)) synopsis = cand
                    }
                } else {
                    // fallback: try element before image if plausible
                    val cand = getStringAt(imageIdx - 1)
                    if (cand.isNotBlank() && cand.length >= 10 && !isoDateRegex.containsMatchIn(cand)) synopsis = cand
                }

                if (title.isNotBlank() && slug.isNotBlank()) {
                    val manga = SManga.create().apply {
                        this.title = title
                        this.description = synopsis
                        this.thumbnail_url = getStringAt(imageIdx)
                        this.url = if (slug.startsWith("/")) slug else "/comic/$slug"
                    }
                    out.add(manga)
                    // advance i beyond used area
                    i = maxOf(imageIdx + 1, slugIdx + 1).coerceAtLeast(i + 1)
                    continue
                }
            }

            // fallback: if arr[i] looks like a clean title by itself (rare)
            val cand = s.trim()
            if (cand.isNotBlank() && cand.length >= 4 && hasLetterRegex.containsMatchIn(cand) && !cand.startsWith("{") && !isoDateRegex.containsMatchIn(cand)) {
                // try to find image & slug shortly after
                var foundImg = ""
                var j = i + 1
                while (j < minOf(n, i + 8)) {
                    val s2 = getStringAt(j)
                    if (s2.matches(imageRegex)) { foundImg = s2; break }
                    j++
                }
                var slug = ""
                var k = j + 1
                while (k < minOf(n, j + 8)) {
                    val s3 = getStringAt(k)
                    if (s3.matches(slugRegex)) { slug = s3; break }
                    k++
                }
                if (foundImg.isNotBlank() && slug.isNotBlank()) {
                    out.add(SManga.create().apply {
                        this.title = cand
                        this.description = getStringAt(i + 1)
                        this.thumbnail_url = foundImg
                        this.url = "/comic/$slug"
                    })
                    i = k + 1
                    continue
                }
            }

            i++
        }

        return out
    }

    // ----------------------
    // Popular (with dedupe across pages)
    // ----------------------
    override fun popularMangaRequest(page: Int): Request {
        // reset seen for page 1 so new listing refresh works
        if (page <= 1) resetSeen()
        return GET("$baseUrl/q-data.json?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()

        // try JSON _objs parsing first
        try {
            val root = tryParseJSONObject(body)
            if (root != null) {
                val parsed = parseQDataObjsToManga(root)

                // filter duplicates using seenUrls
                val newList = parsed.filter { manga ->
                    val key = (manga.url ?: manga.title ?: manga.thumbnail_url ?: "").ifEmpty { manga.title }
                    !seenUrls.contains(key).also { added ->
                        if (!seenUrls.contains(key)) seenUrls.add(key)
                    }
                }

                // hasNext true only if there's new items for this page
                val hasNext = newList.isNotEmpty()
                return MangasPage(newList, hasNext)
            }
        } catch (_: Exception) {}

        // fallback HTML parse
        try {
            val doc = Jsoup.parse(body, baseUrl)
            val elements = doc.select(popularMangaSelector())
            val mangas = elements.map { popularMangaFromElement(it) }

            // filter duplicates
            val newList = mangas.filter { manga ->
                val key = (manga.url ?: manga.title ?: manga.thumbnail_url ?: "").ifEmpty { manga.title }
                !seenUrls.contains(key).also { added ->
                    if (!seenUrls.contains(key)) seenUrls.add(key)
                }
            }

            val hasNext = doc.select(popularMangaNextPageSelector()).isNotEmpty() && newList.isNotEmpty()
            return MangasPage(newList, hasNext)
        } catch (_: Exception) {
            return MangasPage(emptyList(), false)
        }
    }

    override fun popularMangaSelector() = "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx"
    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2.font-bold, h2 a, h2")?.text()?.trim().orEmpty()
            val link = element.selectFirst("a[href*='/comic/'], a[href*='/manga/']") ?: element.selectFirst("a")
            val linkHref = link?.attr("href").orEmpty()
            url = if (linkHref.startsWith(baseUrl)) linkHref.removePrefix(baseUrl) else linkHref
            val img = element.selectFirst("img[data-src], img.lazyimage, img")
            thumbnail_url = img?.attr("data-src")?.ifEmpty { img.attr("src") }.orEmpty()
        }
    }
    override fun popularMangaNextPageSelector() = "a:contains(Next), a:contains(›), .next-page, [href*='page=']"

    // --- Latest / Search reuse popular parse
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/q-data.json?page=$page&latest=1", headers)
    }
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        return if (query.isNotEmpty()) {
            GET("$baseUrl/q-data.json?search=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            GET("$baseUrl/comic-list/?page=$page", headers)
        }
    }
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)
    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // --- Manga details
    override fun mangaDetailsParse(document: Document): SManga {
        val raw = document.text()
        try {
            val root = tryParseJSONObject(raw)
            if (root != null) {
                val list = parseQDataObjsToManga(root)
                if (list.isNotEmpty()) return list.first()
            }
        } catch (_: Exception) {}
        return SManga.create().apply {
            title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim().orEmpty()
            author = document.selectFirst(".author, .meta-author")?.text()?.replace("Author:", "")?.trim().orEmpty()
            description = document.selectFirst(".synopsis, .summary, .entry-content p")?.text()?.trim().orEmpty()
            genre = document.select(".genre a, .genres a, .tag a").joinToString { it.text() }
            val statusText = document.selectFirst(".status, .manga-status")?.text().orEmpty()
            status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) || statusText.contains("tamat", true) -> SManga.COMPLETED
                statusText.contains("hiatus", true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            val thumb1 = document.selectFirst(".post-thumb img, img.lazyimage")?.absUrl("data-src").orEmpty()
            val thumb2 = document.selectFirst(".post-thumb img, img")?.absUrl("src").orEmpty()
            thumbnail_url = if (thumb1.isNotBlank()) thumb1 else if (thumb2.isNotBlank()) thumb2 else ""
        }
    }

    // --- Chapters
    override fun chapterListSelector() = ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/']"
    override fun chapterFromElement(element: Element): SChapter {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        val name = link.text()?.trim().orEmpty()
        val hrefRaw = link.attr("href").orEmpty()
        var url = if (hrefRaw.startsWith(baseUrl)) hrefRaw.removePrefix(baseUrl) else hrefRaw
        return SChapter.create().apply {
            this.name = name
            this.url = url
            this.date_upload = tryParseDate(link.selectFirst(".date, .chapter-date, .time")?.text())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        try {
            val root = tryParseJSONObject(body)
            if (root != null) {
                val keys = listOf("chapters", "items", "data")
                for (k in keys) {
                    if (root.has(k) && root.get(k) is JSONArray) {
                        val arr = root.getJSONArray(k)
                        val list = mutableListOf<SChapter>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("title", o.optString("chapter", o.optString("name", ""))).orEmpty()
                            val rawUrl = o.optString("url", o.optString("link", "")).orEmpty()
                            var url = rawUrl
                            if (url.isNotBlank() && !url.startsWith("http")) url = baseUrl.trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
                            list.add(SChapter.create().apply {
                                this.name = name
                                this.url = url.removePrefix(baseUrl)
                                this.date_upload = tryParseDate(o.optString("date", ""))
                            })
                        }
                        if (list.isNotEmpty()) return list
                    }
                }
            }
        } catch (_: Exception) {}
        // HTML fallback
        val doc = Jsoup.parse(body, baseUrl)
        val elems = doc.select(chapterListSelector())
        return elems.map { el ->
            val link = if (el.tagName() == "a") el else el.selectFirst("a")!!
            val name = link.text()?.trim().orEmpty()
            val hrefRaw = link.attr("href").orEmpty()
            var url = if (hrefRaw.startsWith(baseUrl)) hrefRaw.removePrefix(baseUrl) else hrefRaw
            SChapter.create().apply {
                this.name = name
                this.url = url
                this.date_upload = tryParseDate(link.selectFirst(".date, .chapter-date, .time")?.text())
            }
        }
    }

    // --- Pages
    override fun pageListParse(document: Document): List<Page> {
        val raw = document.text()
        try {
            val root = tryParseJSONObject(raw)
            if (root != null) {
                val arrNames = listOf("pages", "images", "imgs")
                for (k in arrNames) {
                    if (root.has(k) && root.get(k) is JSONArray) {
                        val arr = root.getJSONArray(k)
                        val pages = mutableListOf<Page>()
                        for (i in 0 until arr.length()) {
                            val v = arr.opt(i)
                            val url = when (v) {
                                is JSONObject -> v.optString("url", v.optString("src", "")).orEmpty()
                                is String -> v
                                else -> ""
                            }
                            if (url.isNotBlank()) pages.add(Page(pages.size, "", if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url))
                        }
                        if (pages.isNotEmpty()) return pages
                    }
                }
                if (root.has("_objs")) {
                    val arr = root.getJSONArray("_objs")
                    val pages = mutableListOf<Page>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "")
                        if (s.matches(Regex("https?://[^\\s'\"]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\s'\"]*)?"))) {
                            pages.add(Page(pages.size, "", s))
                        }
                    }
                    if (pages.isNotEmpty()) return pages
                }
            }
        } catch (_: Exception) {}

        val images = document.select("img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, .page-break img, .entry-content img")
            .ifEmpty { document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']") }

        val pages = mutableListOf<Page>()
        images.forEachIndexed { idx, img ->
            val imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            if (imageUrl.isNotEmpty()) pages.add(Page(idx, "", imageUrl))
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src").orEmpty()
    }
}