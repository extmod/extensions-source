package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // --- Popular ---
    override fun popularMangaRequest(page: Int): Request {
        return if (page <= 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/popular/?page=$page", headers)
        }
    }

    override fun popularMangaSelector(): String = "div.grid div.flex.overflow-hidden.rounded-md"

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

    // Jangan pakai tombol JS "Load More" sebagai next selector — gunakan selector item itu sendiri
    override fun popularMangaNextPageSelector(): String = popularMangaSelector()

    // --- Latest ---
    override fun latestUpdatesRequest(page: Int): Request {
        return if (page <= 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/?page=$page", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = latestUpdatesSelector()

    // --- Search ---
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            val q = URLEncoder.encode(query, "UTF-8")
            val baseSearch = "$baseUrl/search/=$q"
            if (page <= 1) GET(baseSearch, headers) else GET("$baseSearch&page=$page", headers)
        } else {
            if (page <= 1) GET(baseUrl, headers) else GET("$baseUrl/?page=$page", headers)
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = searchMangaSelector()

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
