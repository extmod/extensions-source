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

    // Jika build Anda tidak punya network.cloudflareClient, ganti menjadi OkHttpClient()
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    // --- Utilities untuk JSON / date ---
    private fun tryParseJSONObject(text: String): JSONObject? {
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryParseJSONArray(text: String): JSONArray? {
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            null
        }
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
        } catch (_: Exception) {
            return 0L
        }
    }

    // --- Heuristik parsing _objs dari q-data.json ---
    private fun parseQDataObjsToManga(root: JSONObject): List<SManga> {
        val out = mutableListOf<SManga>()
        if (!root.has("_objs")) return out
        val arr = root.optJSONArray("_objs") ?: return out
        val n = arr.length()
        var i = 0

        fun getStringAt(idx: Int): String {
            return if (idx in 0 until n) arr.optString(idx, "") else ""
        }

        val imageRegex = Regex("https?://[^\\s'\"]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\s'\"]*)?")
        val slugRegex = Regex("^[a-z0-9\\-]+$")
        val hasLetterRegex = Regex("\\p{L}")

        while (i < n) {
            // Jika kita menemukan gambar, kita cari judul mundur beberapa elemen ke belakang
            val maybe = getStringAt(i)
            var imageIdx = -1
            if (maybe.matches(imageRegex)) {
                imageIdx = i
            } else {
                // coba cari gambar sedikit maju (beberapa entri didahului oleh teks)
                var j = i
                while (j < minOf(n, i + 6)) {
                    if (getStringAt(j).matches(imageRegex)) {
                        imageIdx = j
                        break
                    }
                    j++
                }
            }

            if (imageIdx >= 0) {
                // cari judul mundur hingga 6 langkah sebelum imageIdx
                var title = ""
                var titleIdx = -1
                val startSearch = maxOf(0, imageIdx - 6)
                for (k in imageIdx - 1 downTo startSearch) {
                    val cand = getStringAt(k).trim()
                    if (cand.isEmpty()) continue
                    // valid title harus mengandung huruf (bukan angka murni) dan panjang minimal 4
                    if (hasLetterRegex.containsMatchIn(cand) && !cand.matches(Regex("^\\d+\$")) && cand.length >= 4) {
                        title = cand
                        titleIdx = k
                        break
                    }
                }

                // jika gak ketemu dengan mundur, coba cari maju dari startSearch
                if (title.isEmpty()) {
                    for (k in startSearch until imageIdx) {
                        val cand = getStringAt(k).trim()
                        if (cand.isEmpty()) continue
                        if (hasLetterRegex.containsMatchIn(cand) && !cand.matches(Regex("^\\d+\$")) && cand.length >= 4) {
                            title = cand
                            titleIdx = k
                            break
                        }
                    }
                }

                // cari slug setelah gambar
                var slug = ""
                var slugIdx = -1
                var k = imageIdx + 1
                while (k < minOf(n, imageIdx + 8)) {
                    val s = getStringAt(k)
                    if (s.matches(slugRegex)) {
                        slug = s
                        slugIdx = k
                        break
                    }
                    k++
                }

                // cari synopsis: ambil teks yang antara titleIdx+1 sampai imageIdx-1 terpanjang
                var synopsis = ""
                if (titleIdx >= 0) {
                    for (sIdx in titleIdx + 1 until imageIdx) {
                        val cand = getStringAt(sIdx)
                        if (cand.length > synopsis.length && !cand.startsWith("http", true)) synopsis = cand
                    }
                } else {
                    // fallback: ambil elemen sebelum image jika panjang
                    val cand = getStringAt(imageIdx - 1)
                    if (cand.isNotBlank() && cand.length >= 10) synopsis = cand
                }

                if (title.isNotBlank() && slug.isNotBlank()) {
                    val manga = SManga.create().apply {
                        this.title = title
                        this.description = synopsis
                        this.thumbnail_url = getStringAt(imageIdx)
                        // buat url relatif ke situs
                        this.url = if (slug.startsWith("/")) "/comic/$slug" else "/comic/$slug"
                    }
                    out.add(manga)
                    // lompat indeks melewati entri yang baru dipakai
                    i = maxOf(imageIdx + 1, slugIdx + 1).coerceAtLeast(i + 1)
                    continue
                }
            }

            // jika tidak menemukan image di dekat i, coba jika arr[i] sendiri sudah judul yang valid (cadangan)
            val cand2 = getStringAt(i).trim()
            if (cand2.isNotBlank() && hasLetterRegex.containsMatchIn(cand2) && !cand2.matches(Regex("^\\d+\$")) && cand2.length >= 4) {
                // coba cari image sedikit setelah
                var img = ""
                var j = i + 1
                while (j < minOf(n, i + 8)) {
                    val s = getStringAt(j)
                    if (s.matches(imageRegex)) { img = s; break }
                    j++
                }
                val slugCandidate = run {
                    var sl = ""
                    var kk = j + 1
                    while (kk < minOf(n, j + 8)) {
                        val s = getStringAt(kk)
                        if (s.matches(slugRegex)) { sl = s; break }
                        kk++
                    }
                    sl
                }
                if (img.isNotBlank() && slugCandidate.isNotBlank()) {
                    val synopsis = getStringAt(i + 1)
                    val manga = SManga.create().apply {
                        title = cand2
                        description = synopsis
                        thumbnail_url = img
                        url = "/comic/$slugCandidate"
                    }
                    out.add(manga)
                    i = j + 1
                    continue
                }
            }

            i++
        }

        return out
    }

    // --- Popular / paging ---
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/q-data.json?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()

        // 1) coba parse q-data JSON _objs
        try {
            val root = tryParseJSONObject(body)
            if (root != null) {
                val parsed = parseQDataObjsToManga(root)
                // jika kita menemukan entri dari JSON, kembalikan dan biarkan app coba next page bila parsed tidak kosong
                if (parsed.isNotEmpty()) return MangasPage(parsed, parsed.isNotEmpty())
            }
        } catch (_: Exception) {}

        // 2) fallback HTML parsing
        try {
            val doc = Jsoup.parse(body, baseUrl)
            val elements = doc.select(popularMangaSelector())
            val mangas = elements.map { popularMangaFromElement(it) }
            val hasNext = doc.select(popularMangaNextPageSelector()).isNotEmpty()
            return MangasPage(mangas, hasNext)
        } catch (_: Exception) {
            return MangasPage(emptyList(), false)
        }
    }

    override fun popularMangaSelector() = "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val titleElement = element.selectFirst("h2 a, h2, .title, .entry-title")
            val linkElement = element.selectFirst("a[href*='/comic/'], a[href*='/manga/']") ?: element.selectFirst("a")
            val imgElement = element.selectFirst("img[data-src], img.lazyimage, img")

            title = titleElement?.text()?.trim().orEmpty()
            val linkHref = linkElement?.attr("href") ?: ""
            url = if (linkHref.startsWith(baseUrl)) linkHref.removePrefix(baseUrl) else linkHref
            thumbnail_url = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: ""
        }
    }

    override fun popularMangaNextPageSelector() = "a:contains(Next), a:contains(›), .next-page, [href*='page=']"

    // --- Latest (re-use) ---
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/q-data.json?page=$page&latest=1", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // --- Search ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

    // --- Manga details ---
    override fun mangaDetailsParse(document: Document): SManga {
        // coba parse JSON yang tertanam di text
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

    // --- Chapters ---
    override fun chapterListSelector() = ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/']"

    override fun chapterFromElement(element: Element): SChapter {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        val name = link.text()?.trim().orEmpty()
        val hrefRaw = link.attr("href")
        var url = hrefRaw?.let { it } ?: ""
        if (url.startsWith(baseUrl)) url = url.removePrefix(baseUrl)
        return SChapter.create().apply {
            this.name = name
            this.url = url
            this.date_upload = tryParseDate(link.selectFirst(".date, .chapter-date, .time")?.text())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()

        // coba parse JSON chapters (beberapa endpoint mungkin mengembalikan objek chapters)
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
                            if (url.isNotBlank() && !url.startsWith("http")) {
                                url = baseUrl.trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
                            }
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

        // fallback HTML
        val doc = Jsoup.parse(body, baseUrl)
        val elems = doc.select(chapterListSelector())
        return elems.map { el ->
            val link = if (el.tagName() == "a") el else el.selectFirst("a")!!
            val name = link.text()?.trim().orEmpty()
            val hrefRaw = link.attr("href")
            var url = hrefRaw?.let { it } ?: ""
            if (url.startsWith(baseUrl)) url = url.removePrefix(baseUrl)
            SChapter.create().apply {
                this.name = name
                this.url = url
                this.date_upload = tryParseDate(link.selectFirst(".date, .chapter-date, .time")?.text())
            }
        }
    }

    // --- Pages ---
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

        // fallback HTML images
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