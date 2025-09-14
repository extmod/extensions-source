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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KomikV : ParsedHttpSource() {

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

    companion object {
        private val seenUrls = mutableSetOf<String>()

        fun resetSeen() {
            seenUrls.clear()
        }
    }

    // === POPULAR SECTION ===
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return if (page <= 1) GET(baseUrl, headers) else GET("$baseUrl/?page=$page", headers)
    }

    override fun popularMangaSelector(): String =
        "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx, div[class*='grid'] > div"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2.font-bold, h2 a, h2, .title, .entry-title")?.text()?.trim().orEmpty()

            val link = element.selectFirst("a[href*='/comic/'], a[href*='/manga/'], a[href*='/series/']") 
                ?: element.selectFirst("a")
            val linkHref = link?.attr("href").orEmpty()
            url = if (linkHref.startsWith(baseUrl)) linkHref.removePrefix(baseUrl) else linkHref

            val img = element.selectFirst("img[data-src], img.lazyimage, img")
            thumbnail_url = when {
                img?.attr("data-src")?.isNotEmpty() == true -> img.absUrl("data-src")
                img?.attr("src")?.isNotEmpty() == true -> img.absUrl("src")
                else -> ""
            }
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        val allMangas = doc.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        val hasNext = hasNextPage(doc, body)
        return MangasPage(allMangas, hasNext)
    }

    // === LATEST SECTION ===
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return if (page <= 1) GET("$baseUrl/?latest=1", headers) else GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        val allMangas = doc.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        val hasNext = hasNextPage(doc, body)
        return MangasPage(allMangas, hasNext)
    }

    // === SEARCH ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            if (page <= 1) GET("$baseUrl/comic-list/", headers) else GET("$baseUrl/comic-list/?page=$page", headers)
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // === MANGA DETAILS ===
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1, .entry-title, .post-title, .manga-title")?.text()?.trim().orEmpty()
            author = document.selectFirst(".author, .mt-4 .text-sm a, .manga-author")?.text()?.trim().orEmpty()

            val descElements = document.select(".description p, .summary p, .mt-4.w-full p")
            description = descElements.firstOrNull { it.text().length > 50 }?.text()?.trim().orEmpty()

            val genres = mutableListOf<String>()
            document.select(".genre a, .genres a, .tag a").forEach { genres.add(it.text().trim()) }
            val type = document.selectFirst(".type, .w-full.rounded-l-full.bg-red-800")?.text()?.trim()
            if (!type.isNullOrBlank()) genres.add(type)
            genre = genres.filter { it.isNotEmpty() }.distinct().joinToString(", ")

            val statusText = document.selectFirst(".status, .manga-status, .w-full.rounded-r-full, .bg-green-800")?.text()?.lowercase().orEmpty()
            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") || statusText.contains("tamat") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            val imgElement = document.selectFirst("img.cover, .manga-cover img, img[data-src], img")
            thumbnail_url = when {
                imgElement?.absUrl("data-src")?.isNotEmpty() == true -> imgElement.absUrl("data-src")
                imgElement?.absUrl("src")?.isNotEmpty() == true -> imgElement.absUrl("src")
                else -> ""
            }
        }
    }

    // === CHAPTER LIST ===
    override fun chapterListSelector(): String =
        ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/'], .episode-list a"

    override fun chapterFromElement(element: Element): SChapter {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        val name = link.text()?.trim().orEmpty()
        val hrefRaw = link.attr("href").orEmpty()
        val url = if (hrefRaw.startsWith(baseUrl)) hrefRaw.removePrefix(baseUrl) else hrefRaw

        return SChapter.create().apply {
            this.name = name.ifEmpty { "Chapter ${link.attr("data-chapter") ?: "Unknown"}" }
            this.url = url
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        return doc.select(chapterListSelector())
            .mapNotNull { element ->
                try {
                    chapterFromElement(element)
                } catch (e: Exception) {
                    null
                }
            }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .reversed()
    }

    // === PAGE LIST / IMAGE PARSING ===
    override fun pageListParse(document: Document): List<Page> {
        val images = document.select(
            "img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, " +
            ".page-break img, .entry-content img, .chapter-content img, " +
            "img[data-src*='.jpg'], img[data-src*='.png'], img[data-src*='.webp'], " +
            "img[src*='.jpg'], img[src*='.png'], img[src*='.webp']"
        )

        val pages = mutableListOf<Page>()
        images.forEachIndexed { index, img ->
            val imageUrl = when {
                img.absUrl("data-src").isNotEmpty() -> img.absUrl("data-src")
                img.absUrl("src").isNotEmpty() -> img.absUrl("src")
                else -> ""
            }

            if (imageUrl.isNotEmpty() &&
                (imageUrl.contains(".jpg") || imageUrl.contains(".png") ||
                 imageUrl.contains(".webp") || imageUrl.contains(".jpeg"))) {
                pages.add(Page(index, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img[data-src], img")?.let { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }.orEmpty()
    }

    // === Helper: Centralized next-page detection ===
    private fun hasNextPage(doc: Document, body: String): Boolean {
        // 1) Cek rel=next atau tombol .next
        val relNext = doc.selectFirst("a[rel=next], a.next, .pagination a[rel=next], .pagination .next")
        if (relNext != null) return true

        // 2) Cek tombol load-more / data-cursor / tombol pagination dinamis
        val loadMore = doc.selectFirst("[class*=load-more], [class*=LoadMore], button[id*=load], button[class*=load], [data-load-more], [data-cursor]")
        if (loadMore != null) return true

        // 3) Cek isi script[type=qwik/json] — baca isinya, jangan hanya cek keberadaan tag
        val qwikScript = doc.selectFirst("script[type=qwik/json]")
        if (qwikScript != null) {
            val qtext = qwikScript.data() // ambil isi JSON/state
            // pola-pola umum yang menunjukkan pagination
            val hasMorePattern = Regex("(?i)\"has[_-]?more\"\\s*:\\s*(true|false)")
            val nextCursorPattern = Regex("(?i)\"next[_-]?cursor\"\\s*:\\s*\"([^\"]+)\"")
            val nextPagePattern = Regex("(?i)\"next[_-]?page\"\\s*:\\s*(\\d+)")
            if (hasMorePattern.containsMatchIn(qtext)) {
                val found = hasMorePattern.find(qtext)
                if (found?.groups?.get(1)?.value?.lowercase() == "true") return true
            }
            if (nextCursorPattern.containsMatchIn(qtext) || nextPagePattern.containsMatchIn(qtext)) return true
        }

        // 4) Cek anchor pagination di container yang relevan saja (lebih ketat)
        val paginationAnchors = doc.select(".pagination a[href*=?page=], nav a[href*=?page=], .pager a[href*=?page=]")
        if (paginationAnchors.isNotEmpty()) {
            val hasPageGt1 = paginationAnchors.any { a ->
                Regex("[?&]page=(\\d+)").find(a.attr("href") ?: "")?.groups?.get(1)?.value?.toIntOrNull()?.let { it > 1 } ?: false
            }
            if (hasPageGt1) return true
        }

        // 5) Fallback konservatif: cari indikator kata kunci di body (lebih selektif daripada sebelumnya)
        val conservative = Regex("(?i)load-?more|nextcursor|next_page|\"hasMore\"|\"has_more\"")
        return conservative.containsMatchIn(body)
    }
}