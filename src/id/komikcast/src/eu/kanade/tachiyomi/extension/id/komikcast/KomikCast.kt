package eu.kanade.tachiyomi.extension.id.komikcast

import android.app.Application
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale

class KomikCast : MangaThemesia("Komik Cast", "https://komikcast.li", "id", "/daftar-komik"), ConfigurableSource {

    // Formerly "Komik Cast (WP Manga Stream)"
    override val id = 972717448578983812

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()
        
    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    override var baseUrl: String = preferences.getString("overrideBaseUrl", "https://komikcast.li") ?: "https://komikcast.li"

    private fun ResizeCover(originalUrl: String): String {
        return "https://wsrv.nl/?w=110&h=150&url=$originalUrl"
    }

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

    override fun popularMangaRequest(page: Int) = customPageRequest(page, "orderby", "popular")
    override fun latestUpdatesRequest(page: Int) = customPageRequest(page, "sortby", "update")
    
    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)
    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    private fun customPageRequest(page: Int, filterKey: String, filterValue: String): Request {
        val pagePath = if (page > 1) "page/$page/" else ""

        return GET("$baseUrl$mangaUrlDirectory/$pagePath?$filterKey=$filterValue", headers)
    }

    override fun searchMangaSelector() = "div.list-update_item"

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val whitelistTitles = preferences.getString("manga_whitelist", "")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
        val isWhitelistActive = whitelistTitles.isNotEmpty()

        val mangas = document.select(searchMangaSelector()).mapNotNull { element ->
            val manga = searchMangaFromElement(element)
            val titleLower = manga.title.lowercase()
            
            // Get type from element
            val typeElement = element.selectFirst("span.type")
            val mangaType = typeElement?.ownText()?.lowercase() ?: ""
            val isManga = mangaType.contains("manga")
            
            // Filter logic
            when {
                isWhitelistActive -> {
                    // If whitelist is active, show only whitelisted titles (any type)
                    if (whitelistTitles.any { titleLower.contains(it) }) manga else null
                }
                else -> {
                    // If whitelist is not active, show only manhwa and manhua (exclude manga)
                    if (!isManga) manga else null
                }
            }
        }

        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
        title = element.selectFirst("h3.title")?.ownText() ?: ""
        thumbnail_url = ResizeCover(thumbnail_url ?: "")
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
                ?.replace("bahasa indonesia", "", ignoreCase = true)?.trim() ?: ""
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
            thumbnail_url = ResizeCover(seriesDetails.select(seriesThumbnailSelector).imgAttr())
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

    override fun pageListParse(document: Document): List<Page> {
        val resizeTemplate = preferences.getString("resize_url_gambar", "") ?: ""
        return document.select("div#chapter_body .main-reading-area img.size-full")
            .distinctBy { img -> img.imgAttr() }
            .mapIndexed { i, img ->
                val originalUrl = img.imgAttr()
                val resizedUrl = if (resizeTemplate.isNotEmpty()) {
                    resizeTemplate.format(originalUrl)
                } else {
                    originalUrl
                }
                Page(i, document.location(), resizedUrl)
            }
    }

    override val hasProjectPage: Boolean = true
    override val projectPageString = "/project-list"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addPathSegments("page/$page/").addQueryParameter("s", query)
            return GET(url.build(), headers)
        }

        url.addPathSegment(mangaUrlDirectory.substring(1))
            .addPathSegments("page/$page/")

        return GET(url.build(), headers)
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Separator()
        )
        return FilterList(filters)
    }
    
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