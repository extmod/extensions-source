package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.source.HttpSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KomikV : HttpSource() {

    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    // ---------- Helpers for q-data.json style ----------
    private fun tryParseTopLevel(body: String): JSONObject? {
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Heuristik untuk struktur yang Anda upload: JSON top-level punya key "_objs" yang
     * berisi array campur-aduk; setiap entry manga muncul sebagai rangkaian nilai yang
     * termasuk title (string), synopsis (string), url gambar (http...jpg/webp/png), slug (kebab).
     *
     * Kode mencari pola: title -> (next strings maybe synopsis) -> next image url -> next slug-like string
     * Jika ditemukan, buat SManga.
     */
    private fun parseQDataObjs(root: JSONObject): List<SManga> {
        val results = mutableListOf<SManga>()
        if (!root.has("_objs")) return results

        val arr = root.optJSONArray("_objs") ?: return results
        var i = 0
        while (i < arr.length()) {
            val maybeTitle = arr.optString(i, "").trim()
            if (maybeTitle.isEmpty()) { i++; continue }

            // Avoid entries that are short tokens like control chars (e.g. "\u0001")
            if (maybeTitle.length < 3) { i++; continue }

            // Look ahead for an image url (jpg/png/webp)
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

            // Look ahead for slug-like token after image
            var slug = ""
            var slugIdx = -1
            if (imageIdx >= 0) {
                var k = imageIdx + 1
                while (k < arr.length()) {
                    val s = arr.optString(k, "")
                    // slug pattern: lowercase, numbers and hyphens (adjust if needed)
                    if (s.matches(Regex("^[a-z0-9\\-]{3,}$"))) {
                        slug = s
                        slugIdx = k
                        break
                    }
                    k++
                }
            }

            // synopsis: try immediate next element after title when it's long text
            var synopsis = ""
            val nextAfterTitle = arr.optString(i + 1, "")
            if (nextAfterTitle.isNotBlank() && nextAfterTitle.length > 20 && !nextAfterTitle.startsWith("http")) {
                synopsis = nextAfterTitle
            } else {
                // try a few elements after title for likely synopsis (defensive)
                for (sIdx in i+1 until minOf(i+5, arr.length())) {
                    val sCandidate = arr.optString(sIdx, "")
                    if (sCandidate.length > synopsis.length && sCandidate.length > 20 && !sCandidate.startsWith("http")) {
                        synopsis = sCandidate
                    }
                }
            }

            if (imageUrl.isNotEmpty() && slug.isNotEmpty()) {
                val manga = SManga.create().apply {
                    title = maybeTitle
                    description = synopsis
                    // store relative url (extension expects relative path). Use /comic/slug as common pattern.
                    url = "/comic/$slug"
                    thumbnail_url = imageUrl
                }
                results.add(manga)
                // advance index beyond slug to avoid re-parsing
                i = if (slugIdx >= 0) slugIdx + 1 else imageIdx + 1
                continue
            }
            // otherwise step forward
            i++
        }
        return results
    }

    // ----------------------
    // Popular
    // ----------------------
    override fun popularMangaRequest(page: Int): Request {
        // use q-data.json endpoint observed on the site
        return GET("$baseUrl/q-data.json?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()

        // 1) try parse as special q-data structure
        try {
            val root = tryParseTopLevel(body)
            if (root != null) {
                val parsed = parseQDataObjs(root)
                if (parsed.isNotEmpty()) {
                    // pagination unknown in this q-data; assume no next (or you can inspect meta)
                    return MangasPage(parsed, false)
                }
            }
        } catch (ignored: Exception) {}

        // 2) fallback: try to parse HTML (some endpoints may return HTML)
        try {
            val doc = response.asJsoup()
            val selector = "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx"
            val elements = doc.select(selector)
            val mangas = elements.map { el -> elementToManga(el) }
            val hasNext = doc.select("a:contains(Next), a:contains(›), .next-page, [href*='page=']").isNotEmpty()
            return MangasPage(mangas, hasNext)
        } catch (e: Exception) {
            // return empty if both fail
            return MangasPage(emptyList(), false)
        }
    }

    private fun elementToManga(element: Element): SManga {
        return SManga.create().apply {
            val titleElement = element.selectFirst("h2 a, h2, .title, .entry-title")
            val linkElement = element.selectFirst("a[href*='/comic/'], a[href*='/manga/']") ?: element.selectFirst("a")
            val imgElement = element.selectFirst("img[data-src], img.lazyimage, img")

            title = titleElement?.text()?.trim() ?: imgElement?.attr("alt")?.trim() ?: ""
            url = linkElement?.attr("href") ?: ""
            if (url.startsWith(baseUrl)) url = url.removePrefix(baseUrl)
            thumbnail_url = imgElement?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            } ?: ""
        }
    }

    // ----------------------
    // Latest (reuse popular)
    // ----------------------
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/q-data.json?page=$page&latest=1", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ----------------------
    // Search
    // ----------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            // try q-data search param (site may support it)
            GET("$baseUrl/q-data.json?search=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            GET("$baseUrl/comic-list/?page=$page", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ----------------------
    // Manga details (fallback to HTML if needed)
    // ----------------------
    override fun mangaDetailsRequest(mangaUrl: String): Request {
        val absolute = if (mangaUrl.startsWith("http")) mangaUrl else baseUrl.trimEnd('/') + (if (mangaUrl.startsWith("/")) mangaUrl else "/$mangaUrl")
        // try JSON inside path first
        return GET("$absolute/q-data.json", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string().orEmpty()
        try {
            val root = tryParseTopLevel(body)
            if (root != null) {
                // if the json is q-data style, try to reuse parsing to find a matching slug
                val list = parseQDataObjs(root)
                // try to match by current request path (best-effort)
                if (list.isNotEmpty()) {
                    // if only one item or first items are valid, pick the one whose url equals request path
                    val reqPath = response.request.url.encodedPath
                    val found = list.firstOrNull { it.url == reqPath || it.url.removePrefix("/") == reqPath.trim('/') } ?: list.first()
                    return found
                }
            }
        } catch (ignored: Exception) {}

        // fallback HTML parse
        try {
            val doc = response.asJsoup()
            return SManga.create().apply {
                title = doc.selectFirst("h1, .entry-title, .post-title")?.text()?.trim() ?: ""
                author = doc.selectFirst(".author, .meta-author")?.text()?.replace("Author:", "")?.trim()
                description = doc.selectFirst(".synopsis, .summary, .entry-content p")?.text()?.trim() ?: ""
                genre = doc.select(".genre a, .genres a, .tag a").joinToString { it.text() }
                val statusText = doc.selectFirst(".status, .manga-status")?.text()
                status = when {
                    statusText?.contains("Ongoing", true) == true -> SManga.ONGOING
                    statusText?.contains("Completed", true) == true -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                thumbnail_url = doc.selectFirst(".post-thumb img, img.lazyimage")?.absUrl("data-src").ifEmpty { doc.selectFirst(".post-thumb img, img")?.absUrl("src") } ?: ""
            }
        } catch (e: Exception) {
            return SManga.create()
        }
    }

    // ----------------------
    // Chapter list
    // ----------------------
    override fun chapterListRequest(mangaUrl: String): Request {
        val absolute = if (mangaUrl.startsWith("http")) mangaUrl else baseUrl.trimEnd('/') + (if (mangaUrl.startsWith("/")) mangaUrl else "/$mangaUrl")
        return GET("$absolute/q-data.json", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()

        // attempt to parse JSON chapters arrays if present
        try {
            val root = tryParseTopLevel(body)
            if (root != null) {
                // common keys in other q-data variants: "chapters", "items" etc
                val possible = listOf("chapters", "items", "data")
                for (k in possible) {
                    if (root.has(k) && root.get(k) is JSONArray) {
                        val arr = root.getJSONArray(k)
                        val list = mutableListOf<SChapter>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val name = o.optString("title", o.optString("chapter", o.optString("name", "")))
                            var url = o.optString("url", o.optString("link", ""))
                            if (url.isNotEmpty() && !url.startsWith("http")) url = baseUrl.trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
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
        try {
            val doc = response.asJsoup()
            val elems = doc.select(".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/']")
            return elems.map { el ->
                val link = if (el.tagName() == "a") el else el.selectFirst("a")!!
                SChapter.create().apply {
                    name = link.text().trim()
                    url = link.attr("href").let { if (it.startsWith(baseUrl)) it.removePrefix(baseUrl) else it }
                    date_upload = tryParseDate(link.selectFirst(".date, .chapter-date, .time")?.text().orEmpty())
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // ----------------------
    // Page list
    // ----------------------
    override fun pageListRequest(chapterUrl: String): Request {
        val absolute = if (chapterUrl.startsWith("http")) chapterUrl else baseUrl.trimEnd('/') + (if (chapterUrl.startsWith("/")) chapterUrl else "/$chapterUrl")
        return GET("$absolute/q-data.json", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string().orEmpty()
        try {
            val root = tryParseTopLevel(body)
            if (root != null) {
                // look for "pages" or "images" arrays
                val arrNames = listOf("pages", "images", "imgs")
                for (k in arrNames) {
                    if (root.has(k) && root.get(k) is JSONArray) {
                        val arr = root.getJSONArray(k)
                        val pages = mutableListOf<Page>()
                        for (i in 0 until arr.length()) {
                            val v = arr.opt(i)
                            val url = when (v) {
                                is JSONObject -> v.optString("url", v.optString("src", ""))
                                is String -> v
                                else -> ""
                            }
                            if (url.isNotBlank()) pages.add(Page(i, "", if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url))
                        }
                        if (pages.isNotEmpty()) return pages
                    }
                }
                // fallback: in q-data style, pages might be encoded inside _objs; try to find image sequence in _objs
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

        // HTML fallback
        try {
            val doc = response.asJsoup()
            val images = doc.select("img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, .page-break img, .entry-content img")
                .ifEmpty { doc.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']") }

            val pages = mutableListOf<Page>()
            images.forEachIndexed { idx, img ->
                val imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
                if (imageUrl.isNotEmpty()) pages.add(Page(idx, "", imageUrl))
            }
            return pages
        } catch (_: Exception) {
            return emptyList()
        }
    }

    // required but not used in JSON flow
    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src") ?: ""
    }

    // small date helper
    private fun tryParseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        try {
            val s = dateStr
            when {
                s.contains("jam lalu", true) -> {
                    val h = s.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    return System.currentTimeMillis() - h * 3600L * 1000L
                }
                s.contains("hari lalu", true) -> {
                    val d = s.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    return System.currentTimeMillis() - d * 24L * 3600L * 1000L
                }
                else -> {
                    val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                    return try { df.parse(s)?.time ?: 0L } catch (_: Exception) {
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