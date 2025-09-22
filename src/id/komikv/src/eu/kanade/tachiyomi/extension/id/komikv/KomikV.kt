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
        val items = chunkForPage(mapped, pageNum)
        val hasNext = items.isNotEmpty() && items.size >= ITEMS_PER_PAGE
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
            thumbnail_url = document.selectFirst("img.w-full.rounded-md")?.absUrl("src").orEmpty()

            val infoDivs = document.select("div.mt-4.flex.w-full.items-center div")
            val typeText = infoDivs.firstOrNull()?.text()?.trim().orEmpty()
            val statusTextRaw = infoDivs.getOrNull(1)?.text()?.trim().orEmpty()

            status = when {
                statusTextRaw.contains("on-going", ignoreCase = true) -> SManga.ONGOING
                statusTextRaw.contains("completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val authorText = document.selectFirst("p.text-sm a")?.text()?.trim()
            if (!authorText.isNullOrBlank()) {
                author = authorText
                artist = authorText
            }

            val genres = document.select(".mt-4 a").mapNotNull { 
                it.text().trim().takeIf { text -> text.isNotEmpty() }
            }.toMutableList()
        
            if (typeText.isNotBlank()) genres.add(typeText)
            if (genres.isNotEmpty()) genre = genres.joinToString(", ")

            description = document.selectFirst("div.mt-4.w-full p")?.text()?.trim().orEmpty()
        }
    }

    override fun parseDate(date: String): Long {
        val trimmed = date.trim()
        val now = System.currentTimeMillis()
        val parts = trimmed.split(" ")
        if (parts.size < 2) return 0L

        val number = parts[0].toIntOrNull() ?: return 0L
        val unit = parts[1]

        val multiplier = when (unit) {
            "dtk"  -> 1000L
            "mnt"  -> 60_000L
            "jam"  -> 3_600_000L
            "hari" -> 86_400_000L
            "mgg"  -> 604_800_000L
            "bln"  -> 2_592_000_000L
            "thn"  -> 31_536_000_000L
            else   -> 0L
        }

        return now - (number * multiplier)
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