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
import eu.kanade.tachiyomi.util.asJsoup
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

    private val resizeCover = "https://LayananGambar"

    private fun resizePage(): String? {
        return preferences.getString("resize_service_url", null)?.takeIf { it.isNotBlank() }
    }

    override var baseUrl = preferences.getString("overrideBaseUrl", super.baseUrl)!!

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun searchMangaFromElement(element: Element): SManga {
        return super.searchMangaFromElement(element).apply {
            thumbnail_url = resizeCover
        }
    }

    override fun mangaDetailsParse(document: Document): SManga {
    val manga = super.mangaDetailsParse(document)

    val img = document.select(seriesThumbnailSelector).firstOrNull()
    if (img != null) {
        manga.thumbnail_url = coverServiceBase
        manga.title = img.attr("alt").trim()
    }
    
    return manga
}

    override fun pageListParse(response: okhttp3.Response): List<Page> {
        val doc = response.asJsoup()
        val service = resizePage()

        return doc.select("#readerarea img").mapIndexed { i, img ->
            val src = img.attr("src").trim()
            val finalUrl = if (service != null) {
                "$service$src"
            } else {
                src
            }
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
    }
}
