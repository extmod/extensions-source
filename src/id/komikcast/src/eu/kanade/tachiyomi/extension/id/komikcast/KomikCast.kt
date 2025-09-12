package eu.kanade.tachiyomi.extension.id.komikcast

import android.app.Application
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.Response
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.util.asJsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale

class KomikCast : MangaThemesia(
    "Komik Cast",
    "https://komikcast.li",
    "id",
    "/manga"
), ConfigurableSource {

    // Formerly "Komik Cast (WP Manga Stream)"
    override val id = 972717448578983812

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    
    private val resizeCover = "https://wsrv.nl/?w=110&h=150&url="
    
    // Constants for preferences
    companion object {
        private const val MANGA_WHITELIST_PREF = "MANGA_WHITELIST"
        private const val MANGA_WHITELIST_PREF_TITLE = "Manga Whitelist"
    }
    
    // Request untuk daftar populer
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/komik/?orderby=update&page=$page", headers)

    // Request untuk update terbaru - FIXED
    override fun latestUpdatesRequest(page: Int): Request = 
        customPageRequest(page, "sortby", "update")

    private fun customPageRequest(page: Int, filterKey: String, filterValue: String): Request {
        val pagePath = if (page > 1) "page/$page/" else ""
        return GET("$baseUrl$mangaUrlDirectory/$pagePath?$filterKey=$filterValue", headers)
    }
    
    // Selector untuk latest updates - ADDED
    override fun latestUpdatesSelector(): String = searchMangaSelector()
    
    // Selector untuk pagination latest updates - ADDED
    override fun latestUpdatesNextPageSelector(): String = "a.next.page-numbers"
    
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=$query&page=$page".toHttpUrl().newBuilder().build()
        return GET(url, headers)
    }
    
    // Parsing khusus untuk update terbaru - UPDATED
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val rawList = preferences.getString(MANGA_WHITELIST_PREF, "")
        val allowedManga = rawList
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val mangas = document.select(latestUpdatesSelector()).mapNotNull { element ->
            val typeText = element.selectFirst("span.type")?.text()?.trim() ?: return@mapNotNull null
            when {
                typeText.equals("Manhwa", true) || typeText.equals("Manhua", true) ->
                    latestUpdatesFromElement(element)
                typeText.equals("Manga", true) -> {
                    val titleText = element.selectFirst("h3.title")?.text()?.trim()
                    if (titleText != null && allowedManga.any { it.equals(titleText, true) }) {
                        latestUpdatesFromElement(element)
                    } else null
                }
                else -> null
            }
        }
        val hasNext = document.select(latestUpdatesNextPageSelector()).firstOrNull() != null
        return MangasPage(mangas, hasNext)
    }

    // Method untuk mengkonversi element ke SManga di latest updates - ADDED
    override fun latestUpdatesFromElement(element: Element): SManga {
        return super.latestUpdatesFromElement(element).apply {
            thumbnail_url = "$resizeCover$thumbnail_url"
            title = element.selectFirst("h3.title")!!.ownText()
        }
    }

    override var baseUrl = preferences.getString("overrideBaseUrl", super.baseUrl)!!

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun searchMangaSelector() = "div.list-update_item"

    override fun searchMangaFromElement(element: Element): SManga {
        return super.searchMangaFromElement(element).apply {
            thumbnail_url = "$resizeCover$thumbnail_url"
            title = element.selectFirst("h3.title")!!.ownText()
        }
    }

    override val seriesDetailsSelector = "div.komik_info:has(.komik_info-content)"
    override val seriesTitleSelector = "h1.komik_info-content-body-title"
    override val seriesDescriptionSelector = ".komik_info-description-sinopsis"
    override val seriesAltNameSelector = ".komik_info-content-native"
    override val seriesGenreSelector = ".komik_info-content-genre a"
    override val seriesThumbnailSelector = ".komik_info-content-thumbnail img"
    override val seriesStatusSelector = ".komik_info-content-info:contains(Status)"

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst(seriesDetailsSelector)?.let { seriesDetails ->
            title = seriesDetails.selectFirst(seriesTitleSelector)?.text()
                ?.replace("bahasa indonesia", "", ignoreCase = true)?.trim().orEmpty()
            artist = seriesDetails.selectFirst(seriesArtistSelector)?.ownText().removeEmptyPlaceholder()
            author = seriesDetails.selectFirst(seriesAuthorSelector)?.ownText().removeEmptyPlaceholder()
            description = seriesDetails.select(seriesDescriptionSelector).joinToString("\n") { it.text() }.trim()
            // Add alternative name to manga description
            val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
            altName?.let {
                description = "$description\n\n$altNamePrefix$altName".trim()
            }
            val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
            // Add series type (manga/manhwa/manhua/other) to genre
            seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
            genre = genres.map { genre ->
                genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.forLanguageTag(lang))
                    } else {
                        char.toString()
                    }
                }
            }
                .joinToString { it.trim() }

            status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()
            thumbnail_url = resizeCover + seriesDetails.select(seriesThumbnailSelector).imgAttr()
        }
    }

    override fun chapterListSelector() = "div.komik_info-chapters li"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val urlElements = element.select("a")
        setUrlWithoutDomain(urlElements.attr("href"))
        name = element.select(".chapter-link-item").text()
        date_upload = parseChapterDate2(element.select(".chapter-link-time").text())
    }

    private fun parseChapterDate2(date: String): Long {
        return if (date.endsWith("ago")) {
            val value = date.split(' ')[0].toInt()
            when {
                "min" in date -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, -value)
                }.timeInMillis
                "hour" in date -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, -value)
                }.timeInMillis
                "day" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, -value)
                }.timeInMillis
                "week" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, -value * 7)
                }.timeInMillis
                "month" in date -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, -value)
                }.timeInMillis
                "year" in date -> Calendar.getInstance().apply {
                    add(Calendar.YEAR, -value)
                }.timeInMillis
                else -> {
                    0L
                }
            }
        } else {
            try {
                dateFormat.parse(date)?.time ?: 0
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun pageListParse(response: okhttp3.Response): List<Page> {
        val doc = response.asJsoup()
        val service = preferences.getString("resize_service_url", "")

        return doc.select("div#chapter_body .main-reading-area img.size-full")
            .filter { img -> 
                val src = img.imgAttr().trim()
                !src.contains("999.jpg")
            }
            .mapIndexed { i, img ->
                val src = img.imgAttr().trim()
                val finalUrl = "$service$src"
                Page(i, "", finalUrl)
            }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL (Pages)"
            summary = "Masukkan URL layanan resize gambar untuk halaman (page list)."
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
        }
        screen.addPreference(resizeServicePref)

        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = "Update domain untuk ekstensi ini"
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                baseUrl = newUrl
                preferences.edit().putString("overrideBaseUrl", newUrl).apply()
                summary = "Current domain: $newUrl"
                true
            }
        }
        screen.addPreference(baseUrlPref)

        screen.addPreference(EditTextPreference(screen.context).apply {
            key = MANGA_WHITELIST_PREF
            title = MANGA_WHITELIST_PREF_TITLE
            dialogTitle = MANGA_WHITELIST_PREF_TITLE
            dialogMessage = "Masukkan judul Manga yang mau ditampilkan, dipisah koma"
            summary = "Tetap tampilkan manga ini"
            setDefaultValue("")
        })
    }
}