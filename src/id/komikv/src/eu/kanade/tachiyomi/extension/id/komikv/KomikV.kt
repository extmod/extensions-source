package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Accept-Encoding", "gzip, deflate")
        .add("Referer", baseUrl)

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/popular/?page=$page", headers)
    }

    override fun popularMangaSelector() = "div.grid.grid-cols-1 div.flex.overflow-hidden, div.grid div.neu"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val titleElement = element.selectFirst("h2 a, h2")
            val linkElement = element.selectFirst("a[href*='/manga/']") ?: element.selectFirst("a")
            val imgElement = element.selectFirst("img.lazyimage, img")

            title = titleElement?.text()?.trim() ?: imgElement?.attr("alt")?.trim() ?: ""
            url = linkElement?.attr("href") ?: ""

            thumbnail_url = imgElement?.let { img ->
                img.attr("data-src").ifEmpty {
                    img.attr("src")
                }
            } ?: ""
        }
    }

    override fun popularMangaNextPageSelector() = "a:contains(Next), a:contains(›), .next-page, [href*='page=']"

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/search?search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        } else {
            "$baseUrl/comic-list/?page=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim() ?: ""

            author = document.selectFirst(".author, .meta-author, .manga-info .author")?.text()?.replace("Author:", "")?.trim()

            description = document.selectFirst(".synopsis, .summary, .description, .entry-content p")?.text()?.trim()
                ?: document.selectFirst(".sinopsis")?.text()?.trim() ?: ""

            genre = document.select(".genre a, .genres a, .tag a").joinToString { it.text() }

            status = document.selectFirst(".status, .manga-status")?.text()?.let { statusText ->
                when {
                    statusText.contains("Ongoing", true) || statusText.contains("On-going", true) -> SManga.ONGOING
                    statusText.contains("Completed", true) || statusText.contains("Tamat", true) -> SManga.COMPLETED
                    statusText.contains("Hiatus", true) -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            } ?: SManga.UNKNOWN

            thumbnail_url = document.selectFirst(".post-thumb img, .manga-thumb img, img.lazyimage")?.let { img ->
                img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            } ?: ""
        }
    }

    // Chapter List
    override fun chapterListSelector() = ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/']"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            val link = if (element.tagName() == "a") element else element.selectFirst("a")!!

            name = link.text().trim()
            url = link.attr("href")

            date_upload = element.selectFirst(".date, .chapter-date, .time, span.float-right")?.text()?.let {
                parseChapterDate(it)
            } ?: 0
        }
    }

    private fun parseChapterDate(dateStr: String): Long {
        return try {
            when {
                dateStr.contains("jam lalu") -> {
                    val hours = dateStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    System.currentTimeMillis() - (hours * 60 * 60 * 1000)
                }
                dateStr.contains("hari lalu") -> {
                    val days = dateStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000)
                }
                dateStr.contains("mgg lalu") || dateStr.contains("minggu lalu") -> {
                    val weeks = dateStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    System.currentTimeMillis() - (weeks * 7 * 24 * 60 * 60 * 1000)
                }
                dateStr.contains("bln lalu") || dateStr.contains("bulan lalu") -> {
                    val months = dateStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.MONTH, -months)
                    calendar.timeInMillis
                }
                else -> {
                    // Try to parse standard date format
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    dateFormat.parse(dateStr)?.time ?: 0
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    // Page List
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // Try multiple selectors for images
        val images = document.select("img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, .page-break img, .entry-content img")
            .ifEmpty { document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']") }

        images.forEachIndexed { index, img ->
            val imageUrl = img.absUrl("data-src").ifEmpty {
                img.absUrl("src")
            }

            if (imageUrl.isNotEmpty() && (imageUrl.contains(".jpg") || imageUrl.contains(".png") || imageUrl.contains(".webp"))) {
                pages.add(Page(index, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img.lazyimage, .reader-area img")?.let { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        } ?: ""
    }
}