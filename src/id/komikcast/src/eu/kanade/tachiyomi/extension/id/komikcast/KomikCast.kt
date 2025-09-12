package eu.kanade.tachiyomi.extension.id.komikcast

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KomikCast : ParsedHttpSource(), ConfigurableSource {
    override val name = "Komik Cast"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl: String = preferences.getString("overrideBaseUrl", "https://komikcast")!!

    private fun ResizeCover(originalUrl: String): String {
        return "https://wsrv.nl/?w=110&h=150&url=$originalUrl"
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/komik/?orderby=update&page=$page", headers)

    override fun popularMangaSelector() = "div.list-update_item"
    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun popularMangaNextPageSelector(): String = "a.next.page-numbers"

    // Latest
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/komik/page/$page/?&orderby=update", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val rawList = preferences.getString("manga_whitelist", "")
        val allowedManga = rawList
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val mangas = document.select(latestUpdatesSelector()).mapNotNull { element ->
            val typeText = element.selectFirst("span.type")?.text()?.trim() ?: return@mapNotNull null
            when {
                typeText.equals("Manhwa", true) || typeText.equals("Manhua", true) ->
                    searchMangaFromElement(element)
                typeText.equals("Manga", true) -> {
                    val titleText = element.selectFirst("h3.title")?.text()?.trim()
                    if (titleText != null && allowedManga.any { it.equals(titleText, true) }) {
                        searchMangaFromElement(element)
                    } else null
                }
                else -> null
            }
        }
        val hasNext = document.select(latestUpdatesNextPageSelector()).firstOrNull() != null
        return MangasPage(mangas, hasNext)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("img").attr("abs:src")?.let { ResizeCover(it) }
        manga.title = element.select("h3.title").text().substringBefore("(").trim()
        element.select("a").first()!!.let {
            manga.setUrlWithoutDomain(it.attr("href"))
        }
        return manga
    }
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    // Details
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val info = document.selectFirst("div.komik_info") ?: return manga.apply { title = "Judul Tidak Diketahui" }

        manga.title = info.selectFirst("h1.komik_info-content-body-title")?.text().orEmpty()
            .replace("bahasa indonesia", "", ignoreCase = true)
            .substringBeforeLast("(").trim()
            .ifEmpty { "Judul Tidak Diketahui" }

        manga.thumbnail_url = document.select("div.komik_info-cover-image img").attr("abs:src")
            ?.let { ResizeCover(it) }

        val parts = info.selectFirst("span.komik_info-content-info:has(b:contains(Author))")
            ?.ownText().orEmpty().split(",")
        manga.author = parts.getOrNull(0)?.trim().orEmpty()
        manga.artist = parts.getOrNull(1)?.trim().orEmpty()

        val synopsis = info.select("div.komik_info-description-sinopsis p").eachText().joinToString("\n\n")
        val altTitle = info.selectFirst("span.komik_info-content-native")?.text().orEmpty().trim()
        manga.description = buildString {
            append(synopsis)
            if (altTitle.isNotEmpty()) append("\n\nAlternative Title: $altTitle")
        }

        val genres = info.select("span.komik_info-content-genre a.genre-item").eachText().toMutableList()
        info.selectFirst("span.komik_info-content-info-type a")?.text()?.takeIf(String::isNotBlank)?.let { genres.add(it) }
        manga.genre = genres.joinToString(", ")

        val statusText = info.selectFirst("span.komik_info-content-info:has(b:contains(Status))")
            ?.text()?.replaceFirst("Status:", "", true).orEmpty().trim()
        manga.status = when {
            statusText.contains("Ongoing", true) -> SManga.ONGOING
            statusText.contains("Completed", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        return manga
    }

    // Chapters
    override fun chapterListSelector() = "div.komik_info-chapters li"
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        name = element.select(".chapter-link-item").text()
        date_upload = parseChapterDate(element.select(".chapter-link-time").text())
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))
    private fun parseChapterDate(date: String): Long = if (date.endsWith("ago")) {
        val v = date.split(' ')[0].toInt()
        Calendar.getInstance().apply {
            when {
                "min" in date  -> add(Calendar.MINUTE, -v)
                "hour" in date -> add(Calendar.HOUR_OF_DAY, -v)
                "day" in date  -> add(Calendar.DATE, -v)
                "week" in date -> add(Calendar.DATE, -v * 7)
                "month" in date-> add(Calendar.MONTH, -v)
                "year" in date -> add(Calendar.YEAR, -v)
            }
        }.timeInMillis
    } else dateFormat.parse(date)?.time ?: 0L

    // Pages
    override fun pageListParse(document: Document): List<Page> {
    val serviceUrl = preferences.getString("resize_url_gambar", null)
    return document.select("div#chapter_body .main-reading-area img.size-full")
        .mapIndexedNotNull { i, img ->
            val url = img.absUrl("src").takeIf { 
                it.isNotBlank() && !it.lowercase().endsWith("999.jpg") 
            } ?: return@mapIndexedNotNull null
            
            val finalUrl = serviceUrl?.let { "$it$url" } ?: url
            Page(i, document.location(), finalUrl)
        }
}

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList(): FilterList = FilterList(Filter.Header("No filters"))

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "resize_url_gambar"
            title = "Layanan resize"
            dialogTitle = "Layanan resize"
            dialogMessage = "Gunakan layanan resize URL gambar"
            summary = "Gunakan layanan resize URL gambar"
            setDefaultValue("")
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Base URL override"
            summary = "Override the base URL"
            setDefaultValue(baseUrl)
            dialogTitle = "Base URL override"
            dialogMessage = "Original: $baseUrl"
            setOnPreferenceChangeListener { _, new ->
                baseUrl = new as String
                preferences.edit().putString("overrideBaseUrl", baseUrl).apply()
                summary = "Current domain: $baseUrl"
                true
            }
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "manga_whitelist"
            title = "Tampilkan Komik"
            dialogTitle = "Tampilkan Komik"
            dialogMessage = "Masukkan judul Manga yang mau ditampilkan, dipisah koma"
            summary = "Tetap tampilkan manga ini"
            setDefaultValue("")
        })
    }
}
