package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
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

    // Override semua abstract member, bahkan yang defaultnya kosong/null jika diperlukan.
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

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h2")?.text()?.trim() ?: element.text().trim()
        url = element.selectFirst("a")?.attr("href").orEmpty()
        thumbnail_url = element.selectFirst("img")?.let { img ->
            val originalUrl = img.absUrl("data-src")
            if (originalUrl.isNotEmpty()) {
                val processedUrl = originalUrl.replace(".lol", ".li")
                "https://wsrv.nl/?w=150&h=110&url=$processedUrl"
            } else {
                ""
            }
        }.orEmpty()
    }

    override fun popularMangaFromElement(element: Element): SManga {
        return searchMangaFromElement(element)
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return searchMangaFromElement(element)
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/popular/?page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun popularMangaNextPageSelector(): String? = null

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesNextPageSelector(): String? = null

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

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector())
            .map { element: Element -> searchMangaFromElement(element) }
        return MangasPage(mangas, false)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
            author = document.select("p.text-sm a").joinToString(", ") { a: Element -> a.text().trim() }
            description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()
            genre = (document.select(".mt-4.w-full a.text-md.mb-1").map { a: Element -> a.text().trim() } +
                    document.select(".bg-red-800").map { a: Element -> a.text().trim() })
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

    override fun chapterListSelector(): String = "div.mt-4.flex.max-h-96.flex-col > a"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("p:first-of-type")?.text().orEmpty()

        val rawDate = element.selectFirst("p.text-xs, time, span.time, small")
            ?.text().orEmpty()

        date_upload = parseChapterDate(rawDate)
    }

    private fun parseChapterDate(text: String): Long {
        val t = text.lowercase().trim()
        val cal = Calendar.getInstance()

        val units = mapOf(
            "dtk" to Calendar.SECOND,
            "mnt" to Calendar.MINUTE,
            "jam" to Calendar.HOUR_OF_DAY,
            "mgg" to Calendar.DATE,
            "bln" to Calendar.MONTH,
            "thn" to Calendar.YEAR,
        )

        for ((key, field) in units) {
            if (t.contains(key)) {
                val num = t.filter { ch: Char -> ch.isDigit() }.ifBlank { "1" }.toInt()
                if (key == "mgg") {
                    cal.add(Calendar.DATE, -num * 7)
                } else {
                    cal.add(field, -num)
                }
                return cal.timeInMillis
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
            .map { element: Element -> chapterFromElement(element) }
            .filter { chapter: SChapter -> chapter.url.isNotEmpty() && chapter.name.isNotEmpty() }
        return when {
            chapters.any { chapter: SChapter -> chapter.chapter_number != 0f } -> chapters.sortedByDescending { chapter: SChapter -> chapter.chapter_number }
            chapters.any { chapter: SChapter -> chapter.date_upload > 0L } -> chapters.sortedByDescending { chapter: SChapter -> chapter.date_upload }
            else -> chapters.reversed()
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("img.imgku.mx-auto")
            .forEachIndexed { index: Int, img: Element ->
                val imageUrl = img.absUrl("src")
                if (imageUrl.isNotEmpty() && !imageUrl.contains("banner.jpg")) {
                    val resizedImageUrl = "https://images.weserv.nl/?w=300&q=70&url=$imageUrl"
                    pages.add(Page(index, "", resizedImageUrl))
                }
            }
        return pages
    }
}