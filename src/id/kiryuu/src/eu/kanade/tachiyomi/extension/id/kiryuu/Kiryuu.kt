package eu.kanade.tachiyomi.extension.id.kiryuu

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

class Kiryuu : MangaThemesia(
    "Kiryuu",
    "https://kiryuu02.com",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val resizeCover = "https://wsrv.nl/?w=110&h=150&url="

    override var baseUrl = preferences.getString("overrideBaseUrl", super.baseUrl)!!

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun searchMangaFromElement(element: Element): SManga {
    return super.searchMangaFromElement(element).apply {
        thumbnail_url = "$resizeCover$thumbnail_url"
    }
}

    override fun mangaDetailsParse(document: Document): SManga {
    val manga = super.mangaDetailsParse(document)

    val img = document.select(seriesThumbnailSelector).firstOrNull()
    if (img != null) {
        val originalUrl = img.imgAttr().trim()
        manga.thumbnail_url = "$resizeCover$originalUrl"
        manga.title = img.attr("alt").trim()
    }

    return manga
}

    override fun pageListParse(response: okhttp3.Response): List<Page> {
    val doc = response.asJsoup()
    val service = preferences.getString("resize_service_url", "")

    val srcList = doc.select(pageSelector)
        .map { it.imgAttr().trim() }
        .filter { it.isNotEmpty() }
        .filter { src ->
            val check = src.substringBefore("?")
            !check.contains("999.jpg", ignoreCase = true) &&
            !check.contains("logov2.png", ignoreCase = true)
        }

    return srcList.mapIndexed { i, src ->
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
    }
}

private const val IMG_CONTENT_TYPE = "image/jpeg"
