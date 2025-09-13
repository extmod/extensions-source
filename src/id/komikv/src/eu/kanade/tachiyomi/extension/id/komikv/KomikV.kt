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
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun popularMangaSelector(): String =
        "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx"

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

    override fun popularMangaNextPageSelector(): String =
        "a[rel=next], .pagination a[rel=next], .pagination a[href*='page=']:not([href*='page=1']), a.next, a:contains(Next), a:contains(›)"

    // Override popularMangaParse untuk deteksi next yang lebih andal (DOM + pattern di JS/HTML)
    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        val list = doc.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && seenUrls.add(it.url) }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        val hasNextByDom = doc.select(popularMangaNextPageSelector()).isNotEmpty()

        val nextPageNumber = currentPage + 1
        val hasNextByPattern = Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body) ||
                Regex("""/page/${nextPageNumber}(/|["'])""").containsMatchIn(body)

        val hasNext = hasNextByDom || hasNextByPattern

        return MangasPage(list, hasNext)
    }

    // --- Latest / Search reuse popular parse
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        return if (query.isNotEmpty()) {
            GET("$baseUrl/q-data.json?search=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            GET("$baseUrl/comic-list/?page=$page", headers)
        }
    }
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // --- Manga details (sederhana, HTML-only)
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim().orEmpty()

            author = document.selectFirst(".mt-4 .text-sm a")?.text()?.trim().orEmpty()

            description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()

            // genre list + tambahkan tipe komik di akhir (selector tipe: .w-full.rounded-l-full.bg-red-800)
            val genres = document.select(".genre a, .genres a, .tag a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            val type = document.selectFirst(".w-full.rounded-l-full.bg-red-800")?.text()?.trim().orEmpty()
            if (type.isNotBlank()) genres.add(type)
            genre = genres.joinToString(", ")

            // status (cari beberapa kemungkinan selector)
            val statusText = document.selectFirst(".status, .manga-status, .w-full.rounded-r-full, .bg-green-800")
                ?.text().orEmpty()
            status = when {
                statusText.contains("ongoing", true) || statusText.contains("on-going", true) -> SManga.ONGOING
                statusText.contains("completed", true) || statusText.contains("tamat", true) -> SManga.COMPLETED
                statusText.contains("hiatus", true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // thumbnail: cari data-src dulu, lalu src (gunakan absUrl supaya relatif juga tertangani)
            val thumb1 = document.selectFirst("img[data-src], img.lazyimage, img.cover")?.absUrl("data-src").orEmpty()
            val thumb2 = document.selectFirst("img[data-src], img.lazyimage, img.cover, img")?.absUrl("src").orEmpty()
            thumbnail_url = when {
                thumb1.isNotBlank() -> thumb1
                thumb2.isNotBlank() -> thumb2
                else -> ""
            }
        }
    }

    // --- Chapters
    override fun chapterListSelector(): String =
        ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/']"

    override fun chapterFromElement(element: Element): SChapter {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        val name = link.text()?.trim().orEmpty()
        val hrefRaw = link.attr("href").orEmpty()
        val url = if (hrefRaw.startsWith(baseUrl)) hrefRaw.removePrefix(baseUrl) else hrefRaw
        return SChapter.create().apply {
            this.name = name
            this.url = url
            // tanggal dihapus (tidak memakai tryParseDate)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)
        val elems = doc.select(chapterListSelector())
        return elems.map { el ->
            val link = if (el.tagName() == "a") el else el.selectFirst("a")!!
            val name = link.text()?.trim().orEmpty()
            val hrefRaw = link.attr("href").orEmpty()
            val url = if (hrefRaw.startsWith(baseUrl)) hrefRaw.removePrefix(baseUrl) else hrefRaw
            SChapter.create().apply {
                this.name = name
                this.url = url
            }
        }
    }

    // --- Pages (HTML-only)
    override fun pageListParse(document: Document): List<Page> {
        val images = document.select(
            "img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, .page-break img, .entry-content img"
        ).ifEmpty { document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']") }

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