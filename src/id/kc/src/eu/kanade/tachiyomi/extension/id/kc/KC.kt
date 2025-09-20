package eu.kanade.tachiyomi.extension.id.kc

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    // Popular / Latest
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/komik-list/?order=popular&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/komik-list/?order=update&page=$page", headers)

    override fun popularMangaSelector() = "div.grid a.group"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector() = ".flex.justify-between.flex-1 a"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
        return GET(url.build(), headers)
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.select("h3").text()
        manga.thumbnail_url = element.select("img").attr("src")
        manga.setUrlWithoutDomain(element.attr("href"))
        return manga
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val infoElement = document.selectFirst("main")!!

        manga.title = document.selectFirst("h1.line-clamp-2")?.text() ?: ""
        manga.description = document.selectFirst("p.my-2")?.text() ?: ""

        // Ambil genre
        val genres = mutableListOf<String>()
        infoElement.select("div.flex.flex-wrap a span").forEach {
            genres.add(it.text())
        }

        // Ambil tipe komik
        val type = infoElement.selectFirst("div.text-sm span.font-medium")?.text()
        type?.let { genres.add(it) }

        manga.genre = genres.joinToString(", ")

        // Status
        val statusText = infoElement.select("div.text-sm span.font-medium").getOrNull(1)?.text()
        manga.status = when {
            statusText?.contains("Ongoing", true) == true -> SManga.ONGOING
            statusText?.contains("Completed", true) == true -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        // Thumbnail
        manga.thumbnail_url = document.selectFirst("div[style*='background-image'] img")?.attr("src")
        return manga
    }

    override fun chapterListSelector() = "div.flex.flex-col a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))

        // Nama chapter
        chapter.name = element.selectFirst("div > p:first-child")?.text() ?: ""

        // Tanggal
        val dateText = element.selectFirst("div > p.text-xs")?.text()?.trim() ?: ""
        chapter.date_upload = parseChapterDate(dateText)
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return if ("yang lalu" in date) {
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
        } else {
            try {
                dateFormat.parse(date)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        var i = 0
        document.select("div.max-w-5xl img").forEach { img ->
            val url = img.attr("src")
            if (url.isNotEmpty()) {
                i++
                pages.add(Page(i, "", url))
            }
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()
    override fun getFilterList(): FilterList = FilterList()
}