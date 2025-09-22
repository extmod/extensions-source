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

    private val ITEMS_PER_PAGE = 18
    private val MAX_PAGE = 50

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/popular/" else "$baseUrl/popular/?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            val q = URLEncoder.encode(query, "UTF-8")
            val baseSearch = "$baseUrl/search/$q/"
            val url = if (page <= 1) baseSearch else "$baseSearch?page=$page"
            GET(url, headers)
        } else {
            val url = if (page <= 1) baseUrl else "$baseUrl/?page=$page"
            GET(url, headers)
        }
    }

    override fun popularMangaSelector(): String =
        "div.grid > div.flex > div:first-child a.relative, div.grid a.relative"

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = elementToSManga(element)
    override fun latestUpdatesFromElement(element: Element): SManga = elementToSManga(element)
    override fun searchMangaFromElement(element: Element): SManga = elementToSManga(element)

    private fun elementToSManga(element: Element): SManga {
        val anchor = if (element.tagName().equals("a", true)) {
            element
        } else {
            element.selectFirst("a.relative") ?: element.selectFirst("a") ?: element
        }
        val img = anchor.selectFirst("img")
        val titleCandidate = element.selectFirst("h2")?.text()?.trim().orEmpty()
        val titleFinal = when {
            titleCandidate.isNotBlank() -> titleCandidate
            img?.hasAttr("alt") == true -> img.attr("alt").trim()
            else -> anchor.text().trim()
        }
        return SManga.create().apply {
            setUrlWithoutDomain(anchor.attr("href").trim())
            title = titleFinal
            thumbnail_url = img?.absUrl("src") ?: ""
        }
    }

    override fun popularMangaNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchMangaNextPageSelector(): String? = null

    private fun <T> chunkForPage(all: List<T>, page: Int): List<T> {
        val per = ITEMS_PER_PAGE
        if (all.isEmpty()) return emptyList()
        val start = (page - 1) * per
        val end = (page * per).coerceAtMost(all.size)
        return if (start < all.size) all.subList(start, end) else emptyList()
    }

    private fun parsePagedResponse(response: Response, selector: String, mapper: (Element) -> SManga): MangasPage {
        val doc = response.asJsoup()
        val pageNum = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mapped = doc.select(selector).map { mapper(it) }
        val seen = mutableSetOf<String>()
        val unique = mapped.filter { seen.add(it.url) }
        val items = chunkForPage(unique, pageNum)
        val hasNext = when {
            items.isEmpty() -> false
            items.size < ITEMS_PER_PAGE -> false
            else -> pageNum < MAX_PAGE
        }
        return MangasPage(items, hasNext)
    }

    override fun popularMangaParse(response: Response): MangasPage =
        parsePagedResponse(response, popularMangaSelector(), ::popularMangaFromElement)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parsePagedResponse(response, latestUpdatesSelector(), ::latestUpdatesFromElement)

    override fun searchMangaParse(response: Response): MangasPage =
        parsePagedResponse(response, searchMangaSelector(), ::searchMangaFromElement)

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img.w-full.rounded-md")?.absUrl("src") ?: ""

            val infoDivs = document.select("div.mt-4.flex.w-full.items-center div")
            val typeText = infoDivs.firstOrNull()?.text()?.trim().orEmpty()
            val statusTextRaw = infoDivs.getOrNull(1)?.text()?.trim().orEmpty()

            val normalized = statusTextRaw.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
            status = when {
    normalized.contains("on-going") -> SManga.ONGOING
    normalized.contains("completed") -> SManga.COMPLETED
    else -> SManga.UNKNOWN
            }

            val authorText = document.selectFirst("p.text-sm a")?.text()?.trim()
            if (!authorText.isNullOrBlank()) author = authorText
            artist = author

            val genres = document.select(".mt-4 a").map { it.text().trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (typeText.isNotBlank()) {
                if (!genres.contains(typeText)) genres.add(typeText)
            }
            if (genres.isNotEmpty()) genre = genres.joinToString(", ")

            val desc = document.selectFirst("div.mt-4.w-full p")?.text()?.trim().orEmpty()
            description = if (desc.isNotEmpty() && genres.isNotEmpty()) "$desc\n${genres.joinToString(", ")}" else desc
        }
    }

    override fun chapterListSelector(): String = "div.mt-4.flex.max-h-96.flex-col a"
    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.selectFirst("div p")?.text()?.trim() ?: element.text().trim()
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

    override fun pageListParse(document: Document): List<Page> {
        val imgs = document.select(".imgku img")
        return imgs.mapIndexed { i, img ->
            val src = img.absUrl("src")
            Page(i, src)
        }
    }

    override fun imageUrlParse(document: Document): String? = null

}