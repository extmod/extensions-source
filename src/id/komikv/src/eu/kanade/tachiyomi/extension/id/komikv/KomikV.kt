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
        private val seenUrls = mutableSetOf<String>()
        fun resetSeen() {
            seenUrls.clear()
        }
    }

    // 1) resetSeen pada popular request
    override fun popularMangaRequest(page: Int): Request {
    if (page <= 1) resetSeen()
    return GET("$baseUrl/?page=$page", headers)
}

// 2) perketat next selector
    override fun popularMangaNextPageSelector() =
    "a[rel=next], .pagination a[rel=next], .pagination a[href*='page=']:not([href*='page=1']), a.next, a:contains(Next), a:contains(›)"

// 3) override popularMangaParse untuk deteksi next yang lebih andal
    override fun popularMangaParse(response: Response): MangasPage {
    val body = response.body?.string().orEmpty()
    val doc = Jsoup.parse(body, baseUrl)

    // ambil daftar dengan dedupe menggunakan seenUrls (agar tidak duplicate across pages)
    val list = doc.select(popularMangaSelector())
        .map { popularMangaFromElement(it) }
        .filter { it.url.isNotBlank() && seenUrls.add(it.url) }

    // cari page saat ini dari request (fallback ke 1)
    val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

    // cara pertama: ada elemen next di DOM sesuai selector kita?
    val hasNextByDom = doc.select(popularMangaNextPageSelector()).isNotEmpty()

    // cara kedua (fallback): cari literal ?page=current+1 di HTML/JS (berguna untuk Qwik/json)
    val nextPageNumber = currentPage + 1
    val hasNextByPattern = Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body)

    val hasNext = hasNextByDom || hasNextByPattern

    return MangasPage(list, hasNext)
}

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

    // Latest / Search reuse popular parse
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

            // thumbnail: cari data-src dulu, lalu src
            val thumbEl = document.selectFirst("img[data-src], img.lazyimage, img.cover, img")
            val thumb1 = thumbEl?.attr("data-src").orEmpty()
            val thumb2 = thumbEl?.attr("src").orEmpty()
            thumbnail_url = when {
                thumb1.isNotBlank() -> if (thumb1.startsWith("http")) thumb1 else baseUrl.trimEnd('/') + thumb1
                thumb2.isNotBlank() -> if (thumb2.startsWith("http")) thumb2 else baseUrl.trimEnd('/') + thumb2
                else -> ""
            }
        }
    }

    // --- Chapters
    override fun chapterListSelector() = ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/']"
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