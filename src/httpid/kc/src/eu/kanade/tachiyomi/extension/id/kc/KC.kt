package eu.kanade.tachiyomi.extension.id.kc

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

class KC : ParsedHttpSource() {

    override val name = "KC"
    override val baseUrl = "https://komik-cast.cc"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    // --- Requests ---
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/komik-list/?order=popular&page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/komik-list/?order=update&page=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/komik-list/".toHttpUrl().newBuilder()
        url.addQueryParameter("title", query)
        return GET(url.build(), headers)
    }

    // --- Selectors ---
    override fun popularMangaSelector() = "div.grid.gap-y-3 > a"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaNextPageSelector() = "a.next"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // --- Manga parsing ---
    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    private fun mangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("h3").text()
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.select("img").attr("src")
        }
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val info = document.selectFirst("div.relative.py-2")!!

        manga.title = info.selectFirst("h1")?.text() ?: ""
        manga.description = info.selectFirst("p.my-2")?.text() ?: ""
        manga.genre = info.select("div.flex.flex-wrap a span").joinToString { it.text() }

        val statusText = info.select("div.text-sm.flex span").getOrNull(2)?.text() ?: ""
        manga.status = when {
            statusText.contains("Ongoing", true) -> SManga.ONGOING
            statusText.contains("Completed", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        manga.thumbnail_url = document.selectFirst("div.rounded-lg img")?.attr("src") ?: ""

        return manga
    }

    // --- Chapter parsing ---
    override fun chapterListSelector() = "div.flex.flex-col.max-h-96 a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.selectFirst("p.mb-0.5")?.text() ?: ""

        val dateText = element.selectFirst("p.text-xs.text-gray-400")?.text()?.trim() ?: ""
        chapter.date_upload = parseChapterDate(dateText)

        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return if (date.contains("yang lalu")) {
            val value = date.split(' ')[0].toIntOrNull() ?: 0
            when {
                "detik" in date -> Calendar.getInstance().apply { add(Calendar.SECOND, -value) }.timeInMillis
                "menit" in date -> Calendar.getInstance().apply { add(Calendar.MINUTE, -value) }.timeInMillis
                "jam" in date -> Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -value) }.timeInMillis
                "hari" in date -> Calendar.getInstance().apply { add(Calendar.DATE, -value) }.timeInMillis
                "minggu" in date -> Calendar.getInstance().apply { add(Calendar.DATE, -value * 7) }.timeInMillis
                "bulan" in date -> Calendar.getInstance().apply { add(Calendar.MONTH, -value) }.timeInMillis
                "tahun" in date -> Calendar.getInstance().apply { add(Calendar.YEAR, -value) }.timeInMillis
                else -> 0L
            }
        } else 0L
    }

    // --- Page parsing ---
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("div.max-w-5xl img").forEachIndexed { i, element ->
            val url = element.attr("src")
            if (url.isNotEmpty()) pages.add(Page(i + 1, "", url))
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // --- Filters ---
    override fun getFilterList() = FilterList(Filter.Header("Filters not implemented"))
}