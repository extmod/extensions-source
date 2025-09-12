package eu.kanade.tachiyomi.extension.id.komikcast

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomikCast : MangaThemesia("Komik Cast", "https://komikcast.li", "id", "/daftar-komik"), ConfigurableSource {

    override val id = 972717448578983812

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

    override fun popularMangaRequest(page: Int) = customPageRequest(page, "orderby", "popular")
    override fun latestUpdatesRequest(page: Int) = customPageRequest(page, "sortby", "update")

    private fun customPageRequest(page: Int, filterKey: String, filterValue: String): Request {
        val pagePath = if (page > 1) "page/$page/" else ""
        return GET("$baseUrl$mangaUrlDirectory/$pagePath?$filterKey=$filterValue", headers)
    }

    override fun searchMangaSelector() = "div.list-update_item"

    // Preference keys & defaults
    private val PREF_RESIZE_SERVICE = "resize_service_url"
    private val PREF_OVERRIDE_DOMAIN = "override_domain"
    private val PREF_WHITELIST = "whitelist_titles"
    private val DEFAULT_RESIZE = "https://images.weserv.nl/?w=300&q=70&url="

    // SharedPreferences sesuai permintaan
    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun buildServiceImageUrlFromAbsolute(absoluteUrl: String?): String? {
        if (absoluteUrl.isNullOrBlank()) return null
        val service = preferences.getString(PREF_RESIZE_SERVICE, DEFAULT_RESIZE) ?: DEFAULT_RESIZE
        // langsung gabung, asumsi absoluteUrl sudah lengkap (gunakan absUrl dari element)
        return service + absoluteUrl.trim()
    }

    private fun buildCoverUrlFromElementImage(el: Element): String? {
        // prefer absUrl, fallback ke attr src apa adanya
        val abs = el.selectFirst("img")?.absUrl("src").takeIf { !it.isNullOrBlank() }
            ?: el.selectFirst("img")?.attr("src")
        return buildServiceImageUrlFromAbsolute(abs)
    }

    /**
     * Deteksi tipe hanya dari listing element — tidak melakukan fetch.
     * Mengembalikan normalized: "manga"/"manhwa"/"manhua" atau null.
     */
    private fun detectTypeFromElement(element: Element): String? {
        // cek span.type di dalam .list-update_item-image (contoh HTML Anda punya <span class="type manga-bg">Manga</span>)
        element.select(".list-update_item-image span, span.type, .type, .flag").forEach { s ->
            val txt = s.text().trim()
            if (txt.isNotEmpty()) {
                val norm = txt.lowercase(Locale.ROOT)
                if (norm.contains("manga")) return "manga"
                if (norm.contains("manhwa")) return "manhwa"
                if (norm.contains("manhua")) return "manhua"
            }
            // cek classname seperti 'manga-bg'
            s.classNames().firstOrNull()?.let { cls ->
                val c = cls.lowercase(Locale.ROOT)
                if (c.contains("manga")) return "manga"
                if (c.contains("manhwa")) return "manhwa"
                if (c.contains("manhua")) return "manhua"
            }
        }

        // cek teks langsung di judul/other small tags
        element.select("span, small, div").map { it.text().trim().lowercase(Locale.ROOT) }
            .firstOrNull { it == "manga" || it == "manhwa" || it == "manhua" }?.let { return it }

        // cek HTML mentah untuk pattern khas (heuristic)
        val html = element.html().lowercase(Locale.ROOT)
        if (html.contains("manga-bg") || html.contains(">manga<") || html.contains("class=\"type manga")) return "manga"
        if (html.contains("manhwa")) return "manhwa"
        if (html.contains("manhua")) return "manhua"

        return null
    }

    // ringkas: kosongkan filter list sesuai permintaan
    override fun getFilterList(): FilterList = FilterList()

    /**
     * latestUpdatesParse: deteksi hanya dari element (NO fetch).
     * Skip item jika detected == "manga" kecuali judul ada di whitelist.
     */
    fun latestUpdatesParse(document: Document): MangasPage {
        val elements = document.select(searchMangaSelector())
        val mangas = mutableListOf<SManga>()

        val whitelistRaw = preferences.getString(PREF_WHITELIST, "") ?: ""
        val whitelist = whitelistRaw.split(",")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

        for (el in elements) {
            val manga = searchMangaFromElement(el)
            val mangaTitle = manga.title?.trim().orEmpty()

            val detected = detectTypeFromElement(el)
            val isManga = detected != null && detected.equals("manga", ignoreCase = true)

            if (isManga) {
                if (whitelist.any { it == mangaTitle.lowercase(Locale.ROOT) }) {
                    mangas.add(manga)
                } else {
                    continue
                }
            } else {
                mangas.add(manga)
            }
        }

        val hasNext = document.select("a.next").isNotEmpty() || document.select("li.page-item:contains(Next)").isNotEmpty()
        return MangasPage(mangas, hasNext)
    }

    override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
        // title sama seperti sebelumnya
        title = element.selectFirst("h3.title")!!.ownText()

        // thumbnail: gunakan absUrl bila tersedia (menghindari makeAbsoluteUrl manual)
        val thumbAbs = element.selectFirst("img")?.absUrl("src").takeIf { !it.isNullOrBlank() }
        val thumbAttr = element.selectFirst("img")?.attr("src")
        thumbnail_url = buildServiceImageUrlFromAbsolute(thumbAbs ?: thumbAttr ?: thumbnail_url)
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
            val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
            altName?.let {
                description = "$description\n\n$altNamePrefix$altName".trim()
            }
            val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
            seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
            genre = genres.map { genre ->
                genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.forLanguageTag(lang)) else char.toString()
                }
            }
                .joinToString { it.trim() }

            status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()

            // gunakan element -> absUrl untuk cover
            val coverAbs = seriesDetails.selectFirst(seriesThumbnailSelector)?.absUrl("src")
            val coverAttr = seriesDetails.selectFirst(seriesThumbnailSelector)?.attr("src")
            thumbnail_url = buildServiceImageUrlFromAbsolute(coverAbs ?: coverAttr)
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
                "min" in date -> Calendar.getInstance().apply { add(Calendar.MINUTE, -value) }.timeInMillis
                "hour" in date -> Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -value) }.timeInMillis
                "day" in date -> Calendar.getInstance().apply { add(Calendar.DATE, -value) }.timeInMillis
                "week" in date -> Calendar.getInstance().apply { add(Calendar.DATE, -value * 7) }.timeInMillis
                "month" in date -> Calendar.getInstance().apply { add(Calendar.MONTH, -value) }.timeInMillis
                "year" in date -> Calendar.getInstance().apply { add(Calendar.YEAR, -value) }.timeInMillis
                else -> 0L
            }
        } else {
            try { dateFormat.parse(date)?.time ?: 0 } catch (_: Exception) { 0L }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#chapter_body .main-reading-area img.size-full")
            .distinctBy { img -> img.absUrl("src").ifEmpty { img.attr("src") } }
            .mapIndexed { i, img ->
                val orig = img.absUrl("src").ifEmpty { img.attr("src") }
                val finalUrl = buildServiceImageUrlFromAbsolute(orig) ?: orig
                Page(i, document.location(), finalUrl)
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
        url.addPathSegment(mangaUrlDirectory.substring(1)).addPathSegments("page/$page/")
        return GET(url.build(), headers)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        val resizeServicePref = EditTextPreference(context).apply {
            key = PREF_RESIZE_SERVICE
            title = "Resize Service URL"
            summary = "URL layanan resize gambar. Contoh: $DEFAULT_RESIZE (akan menambahkan URL gambar setelah 'url=')"
            setDefaultValue(DEFAULT_RESIZE)
            dialogTitle = "Resize Service URL"
        }

        val overrideDomainPref = EditTextPreference(context).apply {
            key = PREF_OVERRIDE_DOMAIN
            title = "Override Domain (Opsional)"
            summary = "Jika ingin mengganti domain gambar (mis. untuk proxy), masukkan domain di sini (contoh: images.example.com). Kosongkan untuk tidak mengubah."
            setDefaultValue("")
            dialogTitle = "Override Domain"
        }

        val whitelistPref = EditTextPreference(context).apply {
            key = PREF_WHITELIST
            title = "Whitelist Judul (untuk menampilkan manga di Update Terbaru)"
            summary = "Masukkan judul judul yang ingin tetap tampil walau tipe 'Manga'. Pisahkan dengan koma."
            setDefaultValue("")
            dialogTitle = "Whitelist Judul (pisah koma)"
        }

        screen.addPreference(resizeServicePref)
        screen.addPreference(overrideDomainPref)
        screen.addPreference(whitelistPref)
    }
}
