package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KomikV : ParsedHttpSource() {
    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    companion object {
        private val seenUrls = mutableSetOf<String>()
        private var lastSearchLastUrl: String? = null
        private val searchResultsCache = mutableMapOf<String, Set<String>>()
        
        fun resetSeen() {
            seenUrls.clear()
            lastSearchLastUrl = null
            searchResultsCache.clear()
        }
    }

    private var searchFinished: Boolean = false
    private var currentSearchQuery: String? = null
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        
        val title = getTitleFromElement(element)
        val url = getUrlFromElement(element)
        val thumbnail = getThumbnailFromElement(element)
        
        manga.title = title
        manga.url = url
        manga.thumbnail_url = thumbnail
        return manga
    }

    private fun getTitleFromElement(element: Element): String {
        val selectors = listOf("h2 a", "h2", "a.title", "a[title]", "div.title a", "div > a > h2")
        return selectors.firstNotNullOfOrNull { element.selectFirst(it)?.text()?.trim() }
            ?: element.text().trim()
    }

    private fun getUrlFromElement(element: Element): String {
        val selectors = listOf("a[href]", "h2 a[href]", "div > a[href]")
        return selectors.mapNotNull { element.selectFirst(it)?.attr("href") }.firstOrNull().orEmpty()
    }

    private fun getThumbnailFromElement(element: Element): String {
        return element.selectFirst("img")?.let { img ->
            val originalUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            if (originalUrl.isNotEmpty()) {
                val processedUrl = originalUrl.replace(".lol", ".li")
                "https://wsrv.nl/?w=150&h=110&url=$processedUrl"
            } else ""
        }.orEmpty()
    }

    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/popular/?page=$page", headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector())
            .map(::popularMangaFromElement)
            .filter { isValidManga(it) && seenUrls.add(it.url) }
        return MangasPage(mangas, true)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector())
            .map(::latestUpdatesFromElement)
            .filter { isValidManga(it) && seenUrls.add(it.url) }
        return MangasPage(mangas, true)
    }

    private fun isValidManga(manga: SManga): Boolean = 
        manga.url.isNotBlank() && manga.title.isNotBlank()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page == 1) resetSeen()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page == 1) {
            "$baseUrl/search/$encodedQuery/"
        } else {
            "$baseUrl/search/$encodedQuery/?page=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map(::searchMangaFromElement)
        return MangasPage(mangas, false)
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
        author = document.select("a[href*=\\"/tax/author/\\"]")
            .joinToString(", ") { it.text().trim() }
        description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()
        genre = getGenreList(document).joinToString(", ")
        status = parseStatus(document.selectFirst(".bg-green-800")?.text().orEmpty())
        thumbnail_url = getThumbnailFromDocument(document)
    }

    private fun getGenreList(document: Document): List<String> {
        return document.select(".mt-4.w-full a.text-md.mb-1").map { it.text().trim() } +
               document.select(".bg-red-800").map { it.text().trim() }
    }

    private fun getThumbnailFromDocument(document: Document): String {
        return document.selectFirst("img.neu-active")?.absUrl("src")?.let { originalUrl ->
            if (originalUrl.isNotEmpty()) {
                val processedUrl = originalUrl.replace(".lol", ".li")
                "https://wsrv.nl/?w=150&h=110&url=$processedUrl"
            } else ""
        }.orEmpty()
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("on-going", ignoreCase = true) -> SManga.ONGOING
        statusString.contains("complete", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.mt-4.flex.max-h-96.flex-col > a"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("p:first-of-type")?.text().orEmpty()

        val rawDate = element.selectFirst("p.text-xs, time, span.time, small")?.text().orEmpty()
        date_upload = parseChapterDate(rawDate)
    }

    private fun parseChapterDate(text: String): Long {
        val normalizedText = text.lowercase().trim()
        val calendar = Calendar.getInstance()

        val timeUnits = mapOf(
            "dtk" to Calendar.SECOND,
            "mnt" to Calendar.MINUTE,
            "jam" to Calendar.HOUR_OF_DAY,
            "mgg" to Calendar.DATE,
            "bln" to Calendar.MONTH,
            "thn" to Calendar.YEAR
        )

        for ((unit, field) in timeUnits) {
            if (normalizedText.contains(unit)) {
                val number = normalizedText.filter { it.isDigit() }.ifBlank { "1" }.toInt()
                if (unit == "mgg") {
                    calendar.add(Calendar.DATE, -number * 7)
                } else {
                    calendar.add(field, -number)
                }
                return calendar.timeInMillis
            }
        }

        return try {
            dateFormat.parse(text)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(chapterListSelector())
            .map(::chapterFromElement)
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
        
        return when {
            chapters.any { it.chapter_number != 0f } -> chapters.sortedByDescending { it.chapter_number }
            chapters.any { it.date_upload > 0L } -> chapters.sortedByDescending { it.date_upload }
            else -> chapters.reversed()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.imgku.mx-auto")
            .mapIndexedNotNull { index, img ->
                val imageUrl = img.absUrl("src")
                if (imageUrl.isNotEmpty() && !imageUrl.contains("banner.jpg")) {
                    val resizedImageUrl = "https://images.weserv.nl/?w=300&q=70&url=$imageUrl"
                    Page(index, "", resizedImageUrl)
                } else null
            }
    }

    override fun imageUrlParse(document: Document): String =
        document.selectFirst("img")?.absUrl("src").orEmpty()
}