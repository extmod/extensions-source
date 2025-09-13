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

    // Jika environment Anda tidak menyediakan network.cloudflareClient, ganti menjadi OkHttpClient()
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    // ----------------------
    // JSON q-data._objs heuristik
    // ----------------------
    private fun tryParseJSONObject(text: String): JSONObject? {
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQDataObjsToManga(root: JSONObject): List<SManga> {
        val out = mutableListOf<SManga>()
        if (!root.has("_objs")) return out
        val arr = root.optJSONArray("_objs") ?: return out

        var i = 0
        while (i < arr.length()) {
            val maybeTitle = arr.optString(i, "").trim()
            if (maybeTitle.isBlank() || maybeTitle.length < 3) {
                i++
                continue
            }

            // cari image url setelah title
            var imageUrl = ""
            var imageIdx = -1
            var j = i + 1
            while (j < arr.length()) {
                val s = arr.optString(j, "")
                if (s.matches(Regex("https?://[^\\s'\"]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\s'\"]*)?"))) {
                    imageUrl = s
                    imageIdx = j
                    break
                }
                j++
            }

            // cari slug setelah image
            var slug = ""
            var slugIdx = -1
            if (imageIdx >= 0) {
                var k = imageIdx + 1
                while (k < arr.length()) {
                    val s = arr.optString(k, "")
                    if (s.matches(Regex("^[a-z0-9\\-]{3,}$"))) {
                        slug = s
                        slugIdx = k
                        break
                    }
                    k++
                }
            }

            // synopsis: ambil kandidat teks pendek setelah title
            var synopsis = ""
            for (sIdx in i + 1 until minOf(i + 6, arr.length())) {
                val cand = arr.optString(sIdx, "")
                if (cand.length > synopsis.length && !cand.startsWith("http", true)) synopsis = cand
            }

            if (imageUrl.isNotEmpty() && slug.isNotEmpty()) {
                val manga = SManga.create().apply {
                    title = maybeTitle
                    description = synopsis
                    // Pola url ini asumsi situs pakai /comic/slug — ubah ke /manga/ jika perlu
                    url = "/comic/$slug"
                    thumbnail_url = imageUrl
                }
                out.add(manga)
                i = if (slugIdx >= 0) slugIdx + 1 else imageIdx + 1
                continue
            }

            i++
        }

        return out
    }

    // ----------------------
    // Popular
    // ----------------------
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/q-data.json?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()

        // 1) coba parse special q-data JSON
        try {
            val root = tryParseJSONObject(body)
            if (root != null) {
                val parsed = parseQDataObjsToManga(root)
                if (parsed.isNotEmpty()) return MangasPage(parsed, false)
            }
        } catch (_: Exception) {}

        // 2) fallback: parse HTML
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

    // ----------------------
    // Latest
    // ----------------------
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/q-data.json?page=$page&latest=1", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ----------------------
    // Search
    // ----------------------
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

    // ----------------------
    // Manga details
    // ----------------------
    override fun mangaDetailsParse(document: Document): SManga {
        // coba parse JSON yang mungkin tertanam di text
        val raw = document.text()
        try {
            val root = tryParseJSONObject(raw)
            if (root != null) {
                val list = parseQDataObjsToManga(root)
                if (list.isNotEmpty()) return list.first()
            }
        } catch (_: Exception) {}

        // fallback HTML parsing (aman terhadap null)
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

    // ----------------------
    // Chapters
    // ----------------------
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

    // Pada ParsedHttpSource klasik kadang signature chapterListParse menerima Response
    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()

        // coba parse JSON chapters
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

        // fallback HTML parse
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

    // ----------------------
    // Pages
    // ----------------------
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

        // fallback HTML parsing for images
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

    // ----------------------
    // Utilities
    // ----------------------
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
                    try {
                        df.parse(s)?.time ?: 0L
                    } catch (_: Exception) {
                        val df2 = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        df2.parse(s)?.time ?: 0L
                    }
                }
            }
        } catch (_: Exception) {
            return 0L
        }
    }
}