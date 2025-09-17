package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
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
        val title = listOf(
            "h2 a", "h2", "a.title", "a[title]", "div.title a", "div > a > h2"
        ).firstNotNullOfOrNull { sel ->
            element.selectFirst(sel)?.text()?.trim()
        } ?: element.text().trim()
        val url = listOf(
            "a[href]", "h2 a[href]", "div > a[href]"
        ).mapNotNull { sel ->
            element.selectFirst(sel)?.attr("href")
        }.firstOrNull().orEmpty()
        val thumb = element.selectFirst("img")?.let { img ->
            val originalUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            if (originalUrl.isNotEmpty()) {
                val processedUrl = originalUrl.replace(".lol", ".li")
                "https://wsrv.nl/?w=150&h=110&url=$processedUrl"
            } else {
                ""
            }
        }.orEmpty()
        manga.title = title
        manga.url = url
        manga.thumbnail_url = thumb
        return manga
    }

    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/popular/?page=$page", headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }
        return MangasPage(mangas, true)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun latestUpdatesSelector(): String = "div.grid div.flex.overflow-hidden"

    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }
        return MangasPage(mangas, true)
    }

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

    override fun searchMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val currentUrl = response.request.url.toString()
        val currentPage = Regex("""page=(\d+)""").find(currentUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        
        // Check for "No Result" indicator
        val noResultElement = document.selectFirst("*:contains(No Result)")
        if (noResultElement != null) {
            return MangasPage(emptyList(), false)
        }
        
        val allResults = document.select(searchMangaSelector())
            .map { searchMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        
        // If no results found in grid
        if (allResults.isEmpty()) {
            return MangasPage(emptyList(), false)
        }
        
        // Get current search query from URL
        val searchQuery = Regex("""/search/([^/?]+)""").find(currentUrl)?.groupValues?.get(1)?.let { 
            URLEncoder.decode(it, "UTF-8") 
        } ?: ""
        
        // Create a unique key for this search query and page combination
        val cacheKey = "${searchQuery}_page${currentPage}"
        val resultUrls = allResults.map { it.url }.toSet()
        
        // Check if we've seen these exact results before for this search
        val previousResults = searchResultsCache[searchQuery] ?: emptySet()
        val hasIdenticalResults = previousResults.isNotEmpty() && previousResults == resultUrls
        
        // Update cache with current results
        searchResultsCache[searchQuery] = resultUrls
        
        val newMangas = allResults
            .distinctBy { it.url }
            .filter { seenUrls.add(it.url) }
        
        // Determine if there's a next page
        val hasNextPage = when {
            // If we found identical results to previous pages, no more unique content
            hasIdenticalResults && currentPage > 1 -> false
            // If no new mangas after filtering duplicates and we're past page 1
            newMangas.isEmpty() && currentPage > 1 -> false
            // If we have fewer results than expected (typically indicates last page)
            newMangas.size < 18 && currentPage > 1 -> false
            // Otherwise, assume there might be more pages
            else -> newMangas.isNotEmpty()
        }
        
        return MangasPage(newMangas, hasNextPage)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
            author = document.select("a[href*=\"/tax/author/\"]").joinToString(", ") { it.text().trim() }
            description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()
            genre = (document.select(".mt-4.w-full a.text-md.mb-1").map { it.text().trim() } +
                    document.select(".bg-red-800").map { it.text().trim() })
                .joinToString(", ")
            status = parseStatus(document.selectFirst(".bg-green-800")?.text().orEmpty())
            thumbnail_url = document.selectFirst("img.neu-active")?.absUrl("src")?.let { originalUrl ->
                if (originalUrl.isNotEmpty()) {
                    val processedUrl = originalUrl.replace(".lol", ".li")
                    "https://wsrv.nl/?w=150&h=110&url=$processedUrl"
                } else {
                    ""
                }
            }.orEmpty()
        }
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("on-going", ignoreCase = true) -> SManga.ONGOING
        statusString.contains("complete", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.mt-4.flex.max-h-96.flex-col > a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val url = element.attr("href")
        chapter.setUrlWithoutDomain(url)
        val titleEl = element.selectFirst("div > p:first-of-type, p:first-of-type")
        chapter.name = titleEl?.text()?.trim() ?: element.text().trim()
        val dateCandidates = listOf(
            "p.text-xs.font-medium",
            "p.text-xs",
            "div > p:nth-of-type(2)",
            "time",
            "span.time",
            "small"
        )
        var dateText: String? = null
        for (sel in dateCandidates) {
            val e = element.selectFirst(sel)
            if (e != null && e.text().isNotBlank()) {
                dateText = e.text().trim()
                break
            }
        }
        if (dateText.isNullOrBlank()) {
            val r = Regex("""(?:(\d+)\s*)?(detik|dtk|menit|mnt|jam|hari|mgg|minggu|bln|bulan|thn|tahun)\b""", RegexOption.IGNORE_CASE)
            val m = r.find(element.text())
            if (m != null) dateText = m.value
        }
        chapter.date_upload = parseChapterDate(dateText ?: "")
        val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
        val numberMatch = numberRegex.find(chapter.name)
        chapter.chapter_number = numberMatch?.value?.replace(",", ".")?.toFloatOrNull() ?: 0f
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        val txt = date.lowercase().trim()
        val regex = Regex("""(?:(\d+)\s*)?(detik|dtk|menit|mnt|jam|hari|mgg|minggu|bln|bulan|thn|tahun)\b""")
        val match = regex.find(txt)
        if (match != null) {
            val valueStr = match.groupValues[1]
            val value = if (valueStr.isBlank()) 1 else {
                try { valueStr.toInt() } catch (_: Exception) { 1 }
            }
            val unit = match.groupValues[2]
            val cal = Calendar.getInstance()
            when (unit) {
                "detik", "dtk" -> cal.add(Calendar.SECOND, -value)
                "menit", "mnt" -> cal.add(Calendar.MINUTE, -value)
                "jam" -> cal.add(Calendar.HOUR_OF_DAY, -value)
                "hari" -> cal.add(Calendar.DATE, -value)
                "mgg", "minggu" -> cal.add(Calendar.DATE, -value * 7)
                "bln", "bulan" -> cal.add(Calendar.MONTH, -value)
                "thn", "tahun" -> cal.add(Calendar.YEAR, -value)
                else -> return 0L
            }
            return cal.timeInMillis
        }
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val chapters = document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
        return when {
            chapters.any { it.chapter_number != 0f } -> chapters.sortedByDescending { it.chapter_number }
            chapters.any { it.date_upload > 0L } -> chapters.sortedByDescending { it.date_upload }
            else -> chapters.reversed()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("img.imgku.mx-auto")
            .forEachIndexed { index, img ->
                val imageUrl = img.absUrl("src")
                if (imageUrl.isNotEmpty() && !imageUrl.contains("banner.jpg")) {
                    val resizedImageUrl = "https://images.weserv.nl/?w=300&q=70&url=$imageUrl"
                    pages.add(Page(index, "", resizedImageUrl))
                }
            }
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src").orEmpty()
    }
}