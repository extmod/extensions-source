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

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl?page=$page", headers)
    }

    override fun popularMangaSelector(): String = "div.grid.grid-cols-1.gap-6 div.flex.overflow-hidden.rounded-md"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val linkElement = element.selectFirst("a[href*=/manga/]")!!

        manga.setUrlWithoutDomain(linkElement.attr("href"))
        manga.title = linkElement.selectFirst("img")?.attr("alt") ?: ""
        manga.thumbnail_url = linkElement.selectFirst("img")?.attr("data-src")
            ?: linkElement.selectFirst("img")?.attr("src")

        return manga
    }

    override fun popularMangaNextPageSelector(): String = "span:contains(Load More)"

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        return popularMangaRequest(page)
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
        url.addQueryParameter("q", query)
        if (page > 1) url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("genre", getGenreList()[filter.state].second)
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", getStatusList()[filter.state].second)
                    }
                }
                is TypeFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("type", getTypeList()[filter.state].second)
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

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        val infoElement = document.selectFirst("div.mx-auto.min-h-screen")

        manga.title = document.selectFirst("h1, .manga-title")?.text()
            ?: document.selectFirst("title")?.text()?.split(" - ")?.get(0) ?: ""

        manga.author = infoElement?.selectFirst(
            "*:contains(Author) + *, *:contains(Pengarang) + *",
        )?.text()?.trim()

        manga.artist = infoElement?.selectFirst(
            "*:contains(Artist) + *, *:contains(Seniman) + *",
        )?.text()?.trim()

        val genreElements = infoElement?.select(
            "*:contains(Genre) + * a, *:contains(Kategori) + * a",
        )
        manga.genre = genreElements?.joinToString(", ") { it.text() }

        val statusText = infoElement?.selectFirst(
            "*:contains(Status) + *, *:contains(Status) + span",
        )?.text()
        manga.status = when {
            statusText?.contains("Ongoing", true) == true -> SManga.ONGOING
            statusText?.contains("Completed", true) == true -> SManga.COMPLETED
            statusText?.contains("Selesai", true) == true -> SManga.COMPLETED
            statusText?.contains("Berjalan", true) == true -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        manga.description = infoElement?.selectFirst(
            "*:contains(Synopsis) + *, *:contains(Sinopsis) + *, .summary",
        )?.text()?.trim()

        manga.thumbnail_url = document.selectFirst(
            "img[alt*=\"${manga.title}\"], .manga-cover img, .cover img",
        )?.attr("abs:src")

        return manga
    }

    // Chapter List
    override fun chapterListSelector(): String =
        "div.grid.grid-cols-1.gap-2 a, .chapter-list a, a[href*=/chapter-]"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.selectFirst("*:contains(Ch.)")?.text()?.trim()
            ?: element.text().trim()

        val dateText = element.selectFirst("span.float-right, .chapter-date")?.text()
        chapter.date_upload = parseDate(dateText)

        return chapter
    }

    // Page List
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // Try multiple selectors for images
        val imageElements = document.select(
            "img[src*=cdn], img[data-src*=cdn], .chapter-images img, .reading-content img",
        )

        imageElements.forEachIndexed { index, element ->
            val imageUrl = element.attr("data-src").ifEmpty {
                element.attr("src")
            }.ifEmpty {
                element.attr("data-lazy-src")
            }

            if (imageUrl.isNotEmpty()) {
                pages.add(Page(index, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    // Filters
    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("NOTE: Filters may not work with search!"),
            GenreFilter(),
            StatusFilter(),
            TypeFilter(),
        )
    }

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        getGenreList().map { it.first }.toTypedArray(),
    )

    private class StatusFilter : Filter.Select<String>(
        "Status",
        getStatusList().map { it.first }.toTypedArray(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Type",
        getTypeList().map { it.first }.toTypedArray(),
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
                else -> {
                    // Try to parse other date formats
                    val formats = arrayOf(
                        "yyyy-MM-dd",
                        "dd/MM/yyyy",
                        "dd-MM-yyyy",
                    )

                    for (format in formats) {
                        try {
                            return SimpleDateFormat(
                                format,
                                Locale.ENGLISH,
                            ).parse(dateString)?.time ?: 0L
                        } catch (e: ParseException) {
                            continue
                        }
                    }
                    0L
                }
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
            "Martial Arts" to "martial-arts",
            "Romance" to "romance",
            "School" to "school",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
        )

        fun getStatusList() = listOf(
            "All" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
        )

        fun getTypeList() = listOf(
            "All" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
        )
    }
}