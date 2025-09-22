package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Calendar

class KomikV : ParsedHttpSource() {

    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Safety cap to avoid endless paging loops on sites that always render a "Load More" button.
    private val MAX_PAGE = 50
    // Items per page observed on komikav (you reported ~18 per page)
    private val ITEMS_PER_PAGE = 18
    // If a single response dumps *everything* (server returns all items), avoid loading all to memory
    private val MAX_ITEMS_PER_RESPONSE = 1000
    private val TRUNCATE_TO = 200

    // Keep first-page first-item URL for each listing to detect duplicate/homepage fallback
    private var firstPopularUrl: String? = null
    private var firstLatestUrl: String? = null
    private var firstSearchUrl: String? = null

    // Keep seen URL sets to dedupe across pages and detect overlap
    private val seenPopularUrls = mutableSetOf<String>()
    private val seenLatestUrls = mutableSetOf<String>()
    private val seenSearchUrls = mutableSetOf<String>()

    // Helper: get chunk for requested page from a cumulative response
    private fun getPageChunk(all: List<SManga>, pageNum: Int): List<SManga> {
        if (all.isEmpty()) return emptyList()
        val per = ITEMS_PER_PAGE
        val total = all.size
        val start = (pageNum - 1) * per
        val end = start + per

        return when {
            // Enough items to slice exact chunk
            total >= end -> all.subList(start, end)
            // Not enough for full chunk but contains some items for requested page -> take the last 'per' items
            total > start -> {
                val from = kotlin.math.max(0, total - per)
                all.subList(from, total)
            }
            // No items for that page
            else -> emptyList()
        }
    }

    // --- Popular ---
    override fun popularMangaRequest(page: Int): Request {
        if (page > MAX_PAGE) return GET("$baseUrl/?page=99999", headers)
        return if (page <= 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/?page=$page", headers)
        }
    }

    // Broader selector: use container grid items; fallback to links inside group
    override fun popularMangaSelector(): String = "div.grid div.flex.overflow-hidden.rounded-md, div.grid a.group"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val linkElement = element.selectFirst("a") ?: element
            setUrlWithoutDomain(linkElement.attr("href"))

            var t = element.select("h2").text().trim()
            if (t.isEmpty()) t = element.select("a img").attr("alt").trim()
            title = t

            val imgElement = element.selectFirst("img")
            thumbnail_url = imgElement?.attr("data-src") ?: imgElement?.attr("src") ?: ""

            val genreElement = element.select("div.z-100.absolute.left-0.top-0")
            if (genreElement.isNotEmpty()) genre = genreElement.text().trim()
        }
    }

    // We return the item selector as "next" check — paging decision handled in parse override below.
    override fun popularMangaNextPageSelector(): String = popularMangaSelector()

    // Robust parser: handle server responses that append pages (cumulative content), dedupe, detect duplicated homepage dump, and truncate giant responses.
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        var mangasAll = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }

        // If server returns an enormous concatenated page (server dump), truncate to avoid browser/app lag
        if (mangasAll.size > MAX_ITEMS_PER_RESPONSE) {
            mangasAll = mangasAll.take(TRUNCATE_TO)
        }

        val pageParam = response.request.url.queryParameter("page") ?: "1"
        val pageNum = pageParam.toIntOrNull() ?: 1

        // Extract only the chunk corresponding to requested page (handles cumulative responses)
        val mangas = getPageChunk(mangasAll, pageNum)

        val firstUrlOnThisPage = mangas.firstOrNull()?.url

        if (pageNum == 1) {
            firstPopularUrl = firstUrlOnThisPage
            // reset seen set when starting from page 1
            seenPopularUrls.clear()
        }

        // Dedupe by previously seen URLs and keep only new items
        val newMangas = mangas.filter { seenPopularUrls.add(it.url) }

        // If page returned zero items -> no next
        if (mangas.isEmpty()) return MangasPage(emptyList(), false)

        // If server returns homepage (first item equals first page first item), stop
        if (pageNum > 1 && firstPopularUrl != null && firstUrlOnThisPage == firstPopularUrl) {
            return MangasPage(emptyList(), false)
        }

        // If large overlap (more than 50% already seen) -> likely duplicate dump or server-side issue -> stop
        val overlapRatio = 1.0 - (if (mangas.isEmpty()) 1.0 else newMangas.size.toDouble() / mangas.size.toDouble())
        if (pageNum > 1 && overlapRatio > 0.5) {
            return MangasPage(newMangas, false)
        }

        // Otherwise continue unless we've hit MAX_PAGE
        val hasNext = pageNum < MAX_PAGE
        return MangasPage(newMangas, hasNext)
    }

    // --- Latest ---
    override fun latestUpdatesRequest(page: Int): Request {
        if (page > MAX_PAGE) return GET("$baseUrl/?page=99999", headers)
        return if (page <= 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/?page=$page", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = latestUpdatesSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        var mangasAll = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }

        if (mangasAll.size > MAX_ITEMS_PER_RESPONSE) mangasAll = mangasAll.take(TRUNCATE_TO)

        val pageParam = response.request.url.queryParameter("page") ?: "1"
        val pageNum = pageParam.toIntOrNull() ?: 1

        val mangas = getPageChunk(mangasAll, pageNum)

        val firstUrlOnThisPage = mangas.firstOrNull()?.url

        if (pageNum == 1) {
            firstLatestUrl = firstUrlOnThisPage
            seenLatestUrls.clear()
        }

        val newMangas = mangas.filter { seenLatestUrls.add(it.url) }

        if (mangas.isEmpty()) return MangasPage(emptyList(), false)

        if (pageNum > 1 && firstLatestUrl != null && firstUrlOnThisPage == firstLatestUrl) {
            return MangasPage(emptyList(), false)
        }

        val overlapRatio = 1.0 - (if (mangas.isEmpty()) 1.0 else newMangas.size.toDouble() / mangas.size.toDouble())
        if (pageNum > 1 && overlapRatio > 0.5) {
            return MangasPage(newMangas, false)
        }

        val hasNext = pageNum < MAX_PAGE
        return MangasPage(newMangas, hasNext)
    }

    // --- Search ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page > MAX_PAGE) return GET("$baseUrl/?page=99999", headers)
        return if (query.isNotEmpty()) {
            val q = URLEncoder.encode(query, "UTF-8")
            val baseSearch = "$baseUrl/search/$q/"
            if (page <= 1) GET(baseSearch, headers) else GET("$baseSearch?page=$page", headers)
        } else {
            if (page <= 1) GET(baseUrl, headers) else GET("$baseUrl/?page=$page", headers)
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = searchMangaSelector()

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        // Some search pages use slightly different containers; try a couple of fallbacks
        var mangasAll = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
        if (mangasAll.isEmpty()) {
            mangasAll = document.select("div.grid a.group, div.archive .card").map { searchMangaFromElement(it) }
        }

        if (mangasAll.size > MAX_ITEMS_PER_RESPONSE) mangasAll = mangasAll.take(TRUNCATE_TO)

        val pageParam = response.request.url.queryParameter("page") ?: "1"
        val pageNum = pageParam.toIntOrNull() ?: 1

        val mangas = getPageChunk(mangasAll, pageNum)

        val firstUrlOnThisPage = mangas.firstOrNull()?.url

        if (pageNum == 1) {
            firstSearchUrl = firstUrlOnThisPage
            seenSearchUrls.clear()
        }

        val newMangas = mangas.filter { seenSearchUrls.add(it.url) }

        if (mangas.isEmpty()) return MangasPage(emptyList(), false)

        if (pageNum > 1 && firstSearchUrl != null && firstUrlOnThisPage == firstSearchUrl) {
            return MangasPage(emptyList(), false)
        }

        val overlapRatio = 1.0 - (if (mangas.isEmpty()) 1.0 else newMangas.size.toDouble() / mangas.size.toDouble())
        if (pageNum > 1 && overlapRatio > 0.5) {
            return MangasPage(newMangas, false)
        }

        val hasNext = pageNum < MAX_PAGE
        return MangasPage(newMangas, hasNext)
    }

    // --- Manga details ---
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1.text-xl").text().trim()
            thumbnail_url = document.selectFirst("img.w-full.rounded-md")?.attr("src") ?: ""

            val statusText = document.select("div.w-full.rounded-r-full").text()
            status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val authorElements = document.select("div:contains(Author) + p a")
            if (authorElements.isNotEmpty()) author = authorElements.joinToString(", ") { it.text() }
            artist = author

            val genres = document.select("div.w-full.gap-4 a").map { it.text() }
            if (genres.isNotEmpty()) genre = genres.joinToString(", ")

            val descriptionElement = document.selectFirst("div.mt-4.w-full p")
            if (descriptionElement != null) description = descriptionElement.text().trim()
        }
    }

    // --- Chapters ---
    override fun chapterListSelector(): String = "div.mt-4.flex.max-h-96.flex-col a"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val chapterText = element.selectFirst("div p")?.text() ?: ""
            name = chapterText.trim()
            val dateElement = element.selectFirst("div p.text-xs")
            if (dateElement != null) date_upload = parseDate(dateElement.text())
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            when {
                dateStr.contains("menit", true) -> {
                    val minutes = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply { add(Calendar.MINUTE, -minutes) }.timeInMillis
                }
                dateStr.contains("jam", true) -> {
                    val hours = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -hours) }.timeInMillis
                }
                dateStr.contains("hari", true) -> {
                    val days = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -days) }.timeInMillis
                }
                dateStr.contains("mgg", true) || dateStr.contains("minggu", true) -> {
                    val weeks = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, -weeks) }.timeInMillis
                }
                dateStr.contains("bln", true) || dateStr.contains("bulan", true) -> {
                    val months = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply { add(Calendar.MONTH, -months) }.timeInMillis
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // --- Pages & image parse (required) ---
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        val imgElements = document.select(
            "div#reader img, div.reading-content img, img.wp-manga-chapter-img, .chapter-content img, .page-break img, article img"
        )

        val imgs = if (imgElements.isNotEmpty()) imgElements else document.select("img")

        for ((index, img) in imgs.withIndex()) {
            val src = img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty {
                    img.attr("src").ifEmpty { img.attr("data-original") }
                }
            }
            pages.add(Page(index, src))
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String {
        val img = document.selectFirst("img[data-src], img[data-lazy-src], img[src], img[data-original]")
        return img?.attr("data-src")?.ifEmpty {
            img.attr("data-lazy-src").ifEmpty {
                img.attr("src").ifEmpty { img.attr("data-original") }
            }
        } ?: ""
    }
}
