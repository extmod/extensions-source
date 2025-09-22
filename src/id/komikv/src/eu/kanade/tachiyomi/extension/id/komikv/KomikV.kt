package eu.kanade.tachiyomi.extension.id.komikv

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.OkHttpClient
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

    // Popular Manga (menggunakan halaman utama)
    override fun popularMangaRequest(page: Int) = GET(baseUrl, headers)

    override fun popularMangaSelector(): String = "div.grid div.flex.overflow-hidden.rounded-md"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            // Link dari elemen a pertama
            val linkElement = element.selectFirst("a")!!
            setUrlWithoutDomain(linkElement.attr("href"))

            // Title dari h2 yang bisa di desktop atau mobile
            var t = element.select("h2").text().trim()
            if (t.isEmpty()) {
                t = element.select("a img").attr("alt").trim()
            }
            title = t

            // Thumbnail dari img dengan data-src atau src
            val imgElement = element.selectFirst("img")
            thumbnail_url = imgElement?.attr("data-src") ?: imgElement?.attr("src") ?: ""

            // Genre dari div badge (jika ada)
            val genreElement = element.select("div.z-100.absolute.left-0.top-0")
            if (genreElement.isNotEmpty()) {
                genre = genreElement.text().trim()
            }
        }
    }

    override fun popularMangaNextPageSelector(): String = "span.cursor-pointer:contains(Load More)"

    // Latest Updates (halaman utama)
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Search - Komikav.net menggunakan endpoint search berbeda
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        if (query.isNotEmpty()) {
            val q = URLEncoder.encode(query, "UTF-8")
            GET("$baseUrl/search?q=$q", headers)
        } else {
            GET(baseUrl, headers)
        }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            // Title dari h1
            title = document.select("h1.text-xl").text().trim()

            // Thumbnail dari img utama
            thumbnail_url = document.selectFirst("img.w-full.rounded-md")?.attr("src") ?: ""

            // Status dari div status badge
            val statusText = document.select("div.w-full.rounded-r-full").text()
            status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            // Author dari section author
            val authorElements = document.select("div:contains(Author) + p a")
            if (authorElements.isNotEmpty()) {
                author = authorElements.joinToString(", ") { it.text() }
            }
            artist = author

            // Genre dari link genre
            val genres = document.select("div.w-full.gap-4 a").map { it.text() }
            if (genres.isNotEmpty()) {
                genre = genres.joinToString(", ")
            }

            // Description dari paragraph
            val descriptionElement = document.selectFirst("div.mt-4.w-full p")
            if (descriptionElement != null) {
                description = descriptionElement.text().trim()
            }
        }
    }

    // Chapter List
    override fun chapterListSelector(): String = "div.mt-4.flex.max-h-96.flex-col a"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))

            // Nama chapter dari div p pertama
            val chapterText = element.selectFirst("div p")?.text() ?: ""
            name = chapterText.trim()

            // Date dari div p kedua
            val dateElement = element.selectFirst("div p.text-xs")
            if (dateElement != null) {
                date_upload = parseDate(dateElement.text())
            }
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

    // --- Halaman dan gambar (diperlukan oleh ParsedHttpSource) ---
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // Coba beberapa selector umum untuk reader images
        val imgElements = document.select(
            "div#reader img, div.reading-content img, img.wp-manga-chapter-img, .chapter-content img, .page-break img, article img"
        )

        // Jika tidak ada, ambil semua img sebagai fallback
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
        // Ambil image pertama yang mungkin merepresentasikan URL gambar langsung
        val img = document.selectFirst("img[data-src], img[data-lazy-src], img[src], img[data-original]")
        return img?.attr("data-src")?.ifEmpty {
            img.attr("data-lazy-src").ifEmpty {
                img.attr("src").ifEmpty { img.attr("data-original") }
            }
        } ?: ""
    }
}