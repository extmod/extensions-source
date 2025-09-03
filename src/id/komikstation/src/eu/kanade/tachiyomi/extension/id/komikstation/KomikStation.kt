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
import okhttp3.ResponseBody.Companion.toResponseBody
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

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    private fun resizeImageUrl(originalUrl: String): String {
        return "LayananGambar$originalUrl"
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val originalThumbnailUrl = element.select("img").imgAttr()
            thumbnail_url = resizeImageUrl(originalThumbnailUrl)

        }
    }

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        val seriesDetails = document.select(seriesThumbnailSelector)
        val originalThumbnailUrl = seriesDetails.imgAttr()
        thumbnail_url = resizeImageUrl(originalThumbnailUrl)

    }

    override fun pageListParse(document: Document): List<Page> {
    val attr = imageAttr
    val resizePrefix = getResizeServiceUrl() ?: ""

    val urls = document.select("img")
        .mapNotNull { el ->
            el.attr(attr).ifEmpty { el.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.trim()
        }
        .distinct()

    return urls.mapIndexed { i, url -> Page(i, document.location(), resizePrefix + url) }
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

        // Preference untuk mengubah base URL
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                baseUrl = newUrl
                preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
                summary = "Current domain: $newUrl" // Update summary untuk domain yang baru
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
    }
}
