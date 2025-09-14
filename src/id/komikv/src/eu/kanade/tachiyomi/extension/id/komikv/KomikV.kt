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

    // === POPULAR MANGA SECTION ===
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.flex.overflow-hidden"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2 a")?.text()?.trim().orEmpty()
            url = element.selectFirst("a")?.attr("href").orEmpty()
            thumbnail_url = element.selectFirst("img")?.absUrl("src").orEmpty()
        }
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }
        
        // Hilangkan kode untuk pagination di sini
        return MangasPage(mangas, true) // Selalu kembalikan true untuk hasNextPage
    }

    // === LATEST UPDATES SECTION ===
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2 a")?.text()?.trim().orEmpty()
            url = element.selectFirst("a")?.attr("href").orEmpty()
            thumbnail_url = element.selectFirst("img")?.absUrl("src").orEmpty()
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        // Hilangkan kode untuk pagination di sini
        return MangasPage(mangas, true) // Selalu kembalikan true untuk hasNextPage
    }

    // === SEARCH SECTION ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        val url = "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(searchMangaSelector())
            .map { popularMangaFromElement(it) }
        
        return MangasPage(mangas, true)
    }

    // === MANGA DETAILS SECTION ===
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            author = document.selectFirst(".author")?.text()?.trim().orEmpty()
            description = document.selectFirst(".description p")?.text()?.trim().orEmpty()
            genre = document.select(".genre a").joinToString(", ") { it.text().trim() }
            status = parseStatus(document.selectFirst(".status")?.text().orEmpty())
            thumbnail_url = document.selectFirst("img")?.absUrl("src").orEmpty()
        }
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
        statusString.contains("completed", ignoreCase = true) || statusString.contains("tamat", ignoreCase = true) -> SManga.COMPLETED
        statusString.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // === CHAPTER LIST SECTION ===
    override fun chapterListSelector(): String = ".chapter-list a"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            name = element.text()?.trim().orEmpty()
            url = element.attr("href").orEmpty()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        return document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .reversed()
    }

    // === PAGE LIST SECTION ===
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']")
            .forEachIndexed { index, img ->
                val imageUrl = img.absUrl("src")
                if (imageUrl.isNotEmpty()) {
                    pages.add(Page(index, "", imageUrl))
                }
            }
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src").orEmpty()
    }
}