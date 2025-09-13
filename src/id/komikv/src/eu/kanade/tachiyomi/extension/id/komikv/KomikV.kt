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

    // Popular (with dedupe across pages)
    // ----------------------
        override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", headers)
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
        return GET("$baseUrl/?page=$page&latest=1", headers)
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
            author = document.selectFirst(".mt-4 text-sm a")?.text()?.replace("", "")?.trim().orEmpty()
            description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()
            genre = document.select(".genre a, .genres a, .tag a").joinToString { it.text() }
            val statusText = document.selectFirst(".status, .manga-status")?.text().orEmpty()
            status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) || statusText.contains("tamat", true) -> SManga.COMPLETED
                statusText.contains("hiatus", true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            val thumb1 = document.selectFirst("img.w-full rounded-md neu neu-active")?.absUrl("data-src").orEmpty()
            val thumb2 = document.selectFirst("img.w-full rounded-md neu neu-active")?.absUrl("src").orEmpty()
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