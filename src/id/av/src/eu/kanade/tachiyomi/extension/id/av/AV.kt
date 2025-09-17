package eu.kanade.tachiyomi.extension.id.av

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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class AV : ParsedHttpSource() {

    override val name = "av"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Popular Manga - Homepage (sama dengan latest karena tidak ada endpoint terpisah)
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun popularMangaSelector(): String = 
        "div.grid.grid-cols-1.gap-6 div.flex.overflow-hidden.rounded-md"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val linkElement = element.selectFirst("a[href*=/manga/]")!!

        manga.setUrlWithoutDomain(linkElement.attr("href"))
        
        // Title dari img alt
        manga.title = linkElement.selectFirst("img")?.attr("alt") ?: ""
        
        // Thumbnail dari data-src atau src
        manga.thumbnail_url = linkElement.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { 
                img.attr("src") 
            }
        }

        return manga
    }

    override fun popularMangaNextPageSelector(): String = "span:contains(Load More)"

    // Latest Updates - sama dengan popular karena website tidak memiliki latest endpoint terpisah
    override fun latestUpdatesRequest(page: Int): Request {
        return popularMangaRequest(page)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Search - menggunakan parameter "search" bukan "s" atau "q"
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        
        if (query.isNotEmpty()) {
            url.addQueryParameter("search", query)
        }
        
        if (page > 1) {
            url.addQueryParameter("page", page.toString())
        }
        
        // Handle filters jika ada
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("genre", getGenreList()[filter.state].second)
                    }
                }
                else -> {}
            }
        }
        
        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Manga Details - perlu selector yang tepat untuk detail page
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        // Title dari berbagai kemungkinan selector
        manga.title = document.selectFirst("h1")?.text() ?: 
                      document.selectFirst("title")?.text()?.substringBefore(" - ") ?: 
                      ""

        // Info element container - cari container yang berisi info manga
        val infoElement = document.selectFirst("div.mx-auto.min-h-screen") ?: document

        // Author - cari text yang mengandung kata "Author" atau "Pengarang"
        manga.author = infoElement.getInfoText("Author", "Pengarang")

        // Artist - cari text yang mengandung kata "Artist" atau "Seniman" 
        manga.artist = infoElement.getInfoText("Artist", "Seniman")

        // Genre - cari link dalam section genre
        val genreElements = infoElement.select("a[href*=genre]")
        manga.genre = genreElements.joinToString(", ") { it.text() }

        // Status - cari text status
        val statusText = infoElement.getInfoText("Status")
        manga.status = when {
            statusText?.contains("Ongoing", true) == true -> SManga.ONGOING
            statusText?.contains("Completed", true) == true -> SManga.COMPLETED
            statusText?.contains("Selesai", true) == true -> SManga.COMPLETED
            statusText?.contains("Berjalan", true) == true -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        // Description - cari dalam berbagai kemungkinan selector
        manga.description = infoElement.selectFirst("div[class*=summary], div[class*=desc], p")?.text()?.trim()

        // Thumbnail - cari img dengan alt yang sesuai atau dalam container cover
        manga.thumbnail_url = document.selectFirst("img[alt*=\"${manga.title}\"], div[class*=cover] img, div[class*=thumb] img")
            ?.attr("abs:src")

        return manga
    }

    // Extension function untuk mencari info text
    private fun Element.getInfoText(vararg keywords: String): String? {
        for (keyword in keywords) {
            val element = this.selectFirst("*:contains($keyword)")?.nextElementSibling()
            if (element != null && element.text().isNotBlank()) {
                return element.text().trim()
            }
        }
        return null
    }

    // Chapter List - berdasarkan struktur HTML yang terlihat
    override fun chapterListSelector(): String = "div.grid.grid-cols-1.gap-2 a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        chapter.setUrlWithoutDomain(element.attr("href"))
        
        // Chapter name - ambil text sebelum span.float-right (tanggal)
        val fullText = element.text()
        val dateSpan = element.selectFirst("span.float-right")?.text()
        
        chapter.name = if (dateSpan != null) {
            fullText.replace(dateSpan, "").trim()
        } else {
            fullText.trim()
        }

        // Hanya ambil yang berformat "Ch. XX" dan skip yang "First chapter", "Latest chapter"
        if (!chapter.name.startsWith("Ch.")) {
            chapter.name = ""  // Skip chapter ini
        }

        // Date parsing - ambil dari span.float-right
        val dateText = dateSpan
        chapter.date_upload = parseDate(dateText)

        return chapter
    }

    // Filter chapter yang tidak valid
    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).mapNotNull { element ->
            val chapter = chapterFromElement(element)
            if (chapter.name.isNotEmpty()) chapter else null
        }
    }

    // Page List - untuk membaca chapter
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // Selector untuk gambar dalam chapter
        val imageElements = document.select("img[src*=cdn], img[data-src*=cdn]")

        imageElements.forEachIndexed { index, element ->
            val imageUrl = element.attr("data-src").ifEmpty {
                element.attr("src")
            }

            if (imageUrl.isNotEmpty()) {
                pages.add(Page(index, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    // Filters - minimal karena website sederhana
    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Search menggunakan parameter 'search'"),
            GenreFilter(),
        )
    }

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        getGenreList().map { it.first }.toTypedArray(),
    )

    // Helper Functions
    private fun parseDate(dateString: String?): Long {
        if (dateString.isNullOrEmpty()) return 0L

        return try {
            when {
                dateString.contains("mnt lalu", true) -> {
                    val minutes = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.MINUTE, -minutes)
                    }.timeInMillis
                }
                dateString.contains("jam lalu", true) -> {
                    val hours = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.HOUR_OF_DAY, -hours)
                    }.timeInMillis
                }
                dateString.contains("hari lalu", true) -> {
                    val days = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_MONTH, -days)
                    }.timeInMillis
                }
                dateString.contains("mgg lalu", true) -> {
                    val weeks = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                    Calendar.getInstance().apply {
                        add(Calendar.WEEK_OF_YEAR, -weeks)
                    }.timeInMillis
                }
                dateString.contains("bln lalu", true) -> {
                    val months = dateString.replace(Regex("\\D"), "").toIntOrNull() ?: 0
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

    companion object {
        fun getGenreList() = listOf(
            "All" to "",
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Horror" to "horror",
            "Romance" to "romance",
            "School" to "school",
            "Sci-Fi" to "sci-fi",
            "Supernatural" to "supernatural",
        )
    }
}