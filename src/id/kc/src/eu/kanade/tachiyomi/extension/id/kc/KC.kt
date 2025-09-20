package eu.kanade.tachiyomi.extension.id.kc

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KC : ParsedHttpSource() {
    override val name = "KC"
    override val baseUrl = "https://komik-cast.cc"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    // Popular & latest
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/komik-list/?order=popular&page=$page", headers)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/komik-list/?order=update&page=$page", headers)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
        url.addQueryParameter("query", query)
        return GET(url.build(), headers)
    }

    // Selectors
    override fun popularMangaSelector() = "div.grid > a"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()
    override fun popularMangaNextPageSelector() = ".flex.justify-between.flex-1 a"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga from element
    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("img").attr("src")
        manga.title = element.select("h3").text()
        manga.setUrlWithoutDomain(element.attr("href"))
        return manga
    }

    // Manga details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.select("h1.line-clamp-2").text()
        manga.thumbnail_url = document.select(".md\\:w\\[480px\\] img").attr("src")
        val genres = mutableListOf<String>()
        document.select("div.flex.flex-wrap a span").forEach { genres.add(it.text()) }
        document.select(".text-sm.py-1.pb-2 span.font-medium").forEach { genres.add(it.text()) } // tipe komik
        manga.genre = genres.joinToString(", ")
        val statusText = document.select("div.text-sm.py-1.pb-2 span.font-medium").getOrNull(1)?.text() ?: ""
        manga.status = when {
            statusText.contains("Ongoing", true) -> SManga.ONGOING
            statusText.contains("Completed", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        manga.description = document.select("p.my-2").text()
        return manga
    }

    // Chapters
    override fun chapterListSelector() = "div.flex.flex-col.overflow-y-auto a"
    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.select("div > p:first-child").text()
        chapter.date_upload = element.select("div > p.text-xs").text().let { parseChapterDate(it) }
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return if (date.contains("yang lalu")) {
            val value = date.split(' ')[0].toInt()
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
        } else {
            try { dateFormat.parse(date)?.time ?: 0 } catch (_: Exception) { 0L }
        }
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("div.max-w-5xl img").forEachIndexed { i, element ->
            val url = element.attr("src")
            if (url.isNotEmpty()) pages.add(Page(i + 1, "", url))
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Filters (kosong / default)
    override fun getFilterList() = FilterList()
}