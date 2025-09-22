package eu.kanade.tachiyomi.extension.id.komikv

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.Calendar

class KomikV : ParsedHttpSource() {

    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Popular Manga (menggunakan halaman utama)
    override fun popularMangaRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.flex.overflow-hidden.rounded-md"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            // Link dari elemen a pertama
            val linkElement = element.select("a").first()!!
            setUrlWithoutDomain(linkElement.attr("href"))
            
            // Title dari h2 yang bisa di desktop atau mobile
            title = element.select("h2").text().trim()
            if (title.isEmpty()) {
                title = element.select("a img").attr("alt").trim()
            }
            
            // Thumbnail dari img dengan data-src atau src
            val imgElement = element.select("img").first()
            thumbnail_url = imgElement?.attr("data-src") ?: imgElement?.attr("src")
            
            // Genre dari div badge
            val genreElement = element.select("div.z-100.absolute.left-0.top-0")
            if (genreElement.isNotEmpty()) {
                genre = genreElement.text().trim()
            }
        }
    }

    override fun popularMangaNextPageSelector(): String = "span.cursor-pointer:contains(Load More)"

    // Latest Updates (halaman utama)
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Search - Komikav.net menggunakan endpoint search berbeda
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search?q=${query}", headers)
        } else {
            GET(baseUrl, headers)
        }
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
            thumbnail_url = document.select("img.w-full.rounded-md").attr("src")
            
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
            val descriptionElement = document.select("div.mt-4.w-full p").first()
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
            val chapterText = element.select("div p").first()?.text() ?: ""
            name = chapterText.trim()
            
            // Date dari div p kedua
            val dateElement = element.select("div p.text-xs").first()
            if (dateElement != null) {
                date_upload = parseDate(dateElement.text())
            }
        }
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            when {
                dateStr.contains("menit") -> {
                    val minutes = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.MINUTE, -minutes)
                    }.timeInMillis
                }
                dateStr.contains("jam") -> {
                    val hours = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.HOUR_OF_DAY, -hours)
                    }.timeInMillis
                }
                dateStr.contains("hari") -> {
                    val days = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_MONTH, -days)
                    }.timeInMillis
                }
                dateStr.contains("mgg") || dateStr.contains("minggu") -> {
                    val weeks = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.WEEK_OF_YEAR, -weeks)
                    }.timeInMillis
                }
                dateStr.contains("bln") || dateStr.contains("bulan") -> {
                    val months = dateStr.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.MONTH, -months)
                    }.timeInMillis
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}