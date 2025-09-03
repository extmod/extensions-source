package eu.kanade.tachiyomi.extension.id.komikstation

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
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

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    private fun resizeImageUrl(originalUrl: String): String {
    val rp = getResizeServiceUrl()
        ?: "https://wsrv.nl/?w=110&h150&url="
    return rp + originalUrl
}

    override var baseUrl = preferences.getString("overrideBaseUrl", super.baseUrl)!!

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun searchMangaFromElement(element: Element): SManga {
    return super.searchMangaFromElement(element).apply {
        if (!thumbnail_url.isNullOrEmpty()) {
            thumbnail_url = resizeImageUrl(thumbnail_url!!)
        }
    }
}

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        val seriesDetails = document.select(seriesThumbnailSelector)
        val originalThumbnailUrl = seriesDetails.imgAttr()
        thumbnail_url = resizeImageUrl(originalThumbnailUrl)
    }

    override fun pageListParse(response: okhttp3.Response): List<Page> {
    val doc = response.asJsoup()
    return doc.select(pageSelector)
        .mapNotNull { it.imgAttr().trim().takeIf { url -> url.isNotEmpty() } }
        .distinct()
        .mapIndexed { i, url -> Page(i, "", resizeImageUrl(url)) }
}

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL"
            summary = "Masukkan URL layanan resize gambar"
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
        }
        screen.addPreference(resizeServicePref)

        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = "Ubah Domain"
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
    }
}
