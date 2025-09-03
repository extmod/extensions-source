package eu.kanade.tachiyomi.extension.id.komikstation

import android.app.Application
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class KomikStation : MangaThemesia(
    "Komik Station",
    "https://komikstation.org",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun ResizePage(): String? {
        return preferences.getString("resize_service_url", null)
    }

    private fun ResizeCover(originalUrl: String): String {
        return "https://wsrv.nl/?w=10&h=150&url=$originalUrl"
    }

    override var baseUrl = preferences.getString("overrideBaseUrl", super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val originalThumbnailUrl = element.select("img").imgAttr()
            thumbnail_url = ResizeCover(originalThumbnailUrl)

            title = element.select("a").attr("title")
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        val seriesDetails = document.select(seriesThumbnailSelector)
        val originalThumbnailUrl = seriesDetails.imgAttr()
        thumbnail_url = ResizeCover(originalThumbnailUrl)

        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    override fun pageListParse(response: okhttp3.Response): List<Page> {
        val doc = response.asJsoup()

        val service = preferences.getString("resize_service_url", null)
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Harap isi Resize Service URL di pengaturan")

        return doc.select(pageSelector)
            .mapNotNull { element ->
                element.selectFirst("#readerarea img")?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .mapIndexed { i, url ->
                Page(i, "", ResizePage(url))
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar."
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
                preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
                summary = "Current domain: $newUrl"
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }
}