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

    // --- Requests ---
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

    override fun popularMangaNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchMangaNextPageSelector(): String? = null

    override fun popularMangaFromElement(element: Element): SManga = elementToSManga(element)
    override fun latestUpdatesFromElement(element: Element): SManga = elementToSManga(element)
    override fun searchMangaFromElement(element: Element): SManga = elementToSManga(element)

    private fun elementToSManga(element: Element): SManga {
        return SManga.create().apply {
            val linkElement = element.selectFirst("a") ?: element
            setUrlWithoutDomain(linkElement.attr("href"))
            var t = element.select("h2").text().trim()
            if (t.isEmpty()) t = element.select("a img").attr("alt").trim()
            title = t
            val imgElement = element.selectFirst("img")
            thumbnail_url = imgElement?.attr("data-src") ?: imgElement?.attr("src") ?: ""
        }
    }

    // --- Generic helper to chunk a cumulative response list into page-sized slice ---
    private fun <T> chunkForPage(all: List<T>, page: Int): List<T> {
        val per = ITEMS_PER_PAGE
        if (all.isEmpty()) return emptyList()
        val start = (page - 1) * per
        val end = (page * per).coerceAtMost(all.size)
        return if (start < all.size) all.subList(start, end) else emptyList()
    }

    private fun parsePagedResponse(response: Response, selector: String, mapper: (Element) -> SManga): MangasPage {
        val doc = response.asJsoup()
        val pageParam = response.request.url.queryParameter("page") ?: "1"
        val pageNum = pageParam.toIntOrNull() ?: 1

        val all = doc.select(selector).map { mapper(it) }
        val items = chunkForPage(all, pageNum)

        val hasNext = (pageNum * ITEMS_PER_PAGE) < all.size || pageNum < MAX_PAGE
        return MangasPage(items, hasNext)
    }

    override fun popularMangaParse(response: Response): MangasPage = parsePagedResponse(response, popularMangaSelector(), ::popularMangaFromElement)
    override fun latestUpdatesParse(response: Response): MangasPage = parsePagedResponse(response, latestUpdatesSelector(), ::latestUpdatesFromElement)
    override fun searchMangaParse(response: Response): MangasPage = parsePagedResponse(response, searchMangaSelector(), ::searchMangaFromElement)

    // --- Others (details, chapters, pages) ---
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1.text-xl").text().trim()
            thumbnail_url = document.selectFirst("img.w-full.rounded-md")?.attr("src") ?: ""
        }
    }

    override fun chapterListSelector(): String = "div.mt-4.flex.max-h-96.flex-col a"
    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.selectFirst("div p")?.text()?.trim() ?: ""
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val imgs = document.select("div#reader img, div.reading-content img, img.wp-manga-chapter-img, .chapter-content img, .page-break img, article img")
        val list = mutableListOf<Page>()
        for ((i, img) in imgs.withIndex()) {
            val src = img.attr("data-src").ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } }
            list.add(Page(i, src))
        }
        return list
    }

    override fun imageUrlParse(document: Document): String {
        val img = document.selectFirst("img[data-src], img[data-lazy-src], img[src]")
        return img?.attr("data-src")?.ifEmpty { img.attr("data-lazy-src").ifEmpty { img.attr("src") } } ?: ""
    }
}