package eu.kanade.tachiyomi.extension.id.komikcastcc

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class KomikCastCC : ParsedHttpSource() {

    override val name = "KomikCast"
    override val baseUrl = "https://komik-cast.cc"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/komik-list/?order=popular&page=$page", headers)

    override fun popularMangaSelector() = "a.group[href*=\"/komik/\"]"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("img")?.attr("alt") ?: ""
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/komik-list/?order=update&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/komik-list/".toHttpUrl().newBuilder()

        if (query.isNotBlank()) url.addQueryParameter("s", query)

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> if (filter.state != 0) {
                    url.addQueryParameter("status", filter.pairs[filter.state].second)
                }
                is TypeFilter -> if (filter.state != 0) {
                    url.addQueryParameter("type", filter.pairs[filter.state].second)
                }
                is OrderFilter -> {
                    url.addQueryParameter("order", filter.pairs[filter.state].second)
                }
                is GenreFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("genre[]", it.value)
                }
                else -> {} // Menangani semua tipe filter lainnya
            }
        }

        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = null

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""

        thumbnail_url = document.selectFirst("img[src*=\"cdn.komik-cast.cc/uploads\"]")
            ?.attr("src")

        val spans = document.select("div.text-sm span.font-medium")
        val typeText = spans.getOrNull(0)?.text() ?: ""
        val statusText = spans.getOrNull(1)?.text()?.lowercase() ?: ""

        status = when {
            "ongoing" in statusText -> SManga.ONGOING
            "completed" in statusText -> SManga.COMPLETED
            "hiatus" in statusText -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val genreList = document.select("a[href*=\"genre\"]").map { it.text() }.toMutableList()
        if (typeText.isNotBlank()) genreList.add(0, typeText)
        genre = genreList.joinToString()

        description = document.selectFirst("div.my-2")?.text()
    }

    override fun chapterListSelector() =
        "div.gap-2.my-4 a[href*=\"-chapter-\"]"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val href = element.attr("href")
        setUrlWithoutDomain(href)

        name = element.selectFirst("p.mb-0\\.5")?.text()?.trim()
            ?: Regex("chapter-([\\d.]+(?:-\\w+)?)/?$")
                .find(href)?.groupValues?.get(1)
                ?.let { "Chapter $it" }
            ?: href

        chapter_number = Regex("chapter-([\\d]+(?:[.,]\\d+)?)")
            .find(href)?.groupValues?.get(1)
            ?.replace(",", ".")
            ?.toFloatOrNull() ?: -1f

        date_upload = element.selectFirst("p.text-xs")?.text()
            ?.let { parseRelativeDate(it) } ?: 0L
    }

    private fun parseRelativeDate(text: String): Long {
        val now = System.currentTimeMillis()
        val clean = text.lowercase().trim()
        val num = Regex("(\\d+)").find(clean)?.groupValues?.get(1)?.toLongOrNull() ?: 1L

        return when {
            "detik" in clean -> now - num * 1_000L
            "menit" in clean -> now - num * 60_000L
            "jam" in clean -> now - num * 3_600_000L
            "hari" in clean -> now - num * 86_400_000L
            "minggu" in clean -> now - num * 604_800_000L
            "bulan" in clean -> now - num * 2_592_000_000L
            "tahun" in clean -> now - num * 31_536_000_000L
            else -> 0L
        }
    }

    override fun pageListParse(document: Document): List<Page> =
        document.select("img[src*=\"cdn.komik-cast.cc/images\"]")
            .mapIndexed { i, img -> Page(i, imageUrl = img.attr("src")) }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request =
        GET(
            page.imageUrl!!,
            headersBuilder()
                .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .build(),
        )

    override fun getFilterList() = FilterList(
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    open class SelectFilter(name: String, val pairs: List<Pair<String, String>>) :
        Filter.Select<String>(name, pairs.map { it.first }.toTypedArray())

    class OrderFilter : SelectFilter(
        "Urutkan",
        listOf(
            "Update" to "update",
            "Terbaru Ditambah" to "added",
            "A-Z" to "title",
            "Z-A" to "titlereverse",
            "Terpopuler" to "popular",
        ),
    )

    class StatusFilter : SelectFilter(
        "Status",
        listOf(
            "Semua" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
        ),
    )

    class TypeFilter : SelectFilter(
        "Tipe",
        listOf(
            "Semua" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
        ),
    )

    class Genre(name: String, val value: String) : Filter.CheckBox(name)

    class GenreFilter : Filter.Group<Genre>(
        "Genre",
        listOf(
            Genre("Action", "action"),
            Genre("Adventure", "adventure"),
            Genre("Comedy", "comedy"),
            Genre("Drama", "drama"),
            Genre("Ecchi", "ecchi"),
            Genre("Fantasy", "fantasy"),
            Genre("Harem", "harem"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Isekai", "isekai"),
            Genre("Josei", "josei"),
            Genre("Martial Arts", "martial-arts"),
            Genre("Mature", "mature"),
            Genre("Mecha", "mecha"),
            Genre("Mystery", "mystery"),
            Genre("Psychological", "psychological"),
            Genre("Romance", "romance"),
            Genre("School Life", "school-life"),
            Genre("Sci-fi", "sci-fi"),
            Genre("Seinen", "seinen"),
            Genre("Shoujo", "shoujo"),
            Genre("Shounen", "shounen"),
            Genre("Slice of Life", "slice-of-life"),
            Genre("Sports", "sports"),
            Genre("Supernatural", "supernatural"),
            Genre("Thriller", "thriller"),
            Genre("Tragedy", "tragedy"),
            Genre("Webtoon", "webtoon"),
        ),
    )

    companion object {
        const val PAGE_SIZE = 24
    }
}