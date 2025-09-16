package eu.kanade.tachiyomi.extension.id.luvyaa

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.decodeFromStream
import okhttp3.FormBody
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class Luvyaa : MangaThemesia(
    "Luvyaa",
    "https://luvyaa.my.id",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val resizeCover = "https://wsrv.nl/?w=110&h=150&url="
    
    // Get resize service URL from preferences with fallback
    private val resizeService: String
        get() = preferences.getString("resize_service_url", null) ?: "https://wsrv.nl/?url="

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

    override fun pageListParse(document: Document): List<Page> {
        val postId = document.select("script").map(Element::data)
            .firstOrNull(postIdRegex::containsMatchIn)
            ?.let { postIdRegex.find(it)?.groups?.get(1)?.value }
            ?: throw IOException("Post ID not found")

        val payload = FormBody.Builder()
            .add("action", "get_image_json")
            .add("post_id", postId)
            .build()

        val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, payload))
            .execute()

        if (!response.isSuccessful) {
            throw IOException("Pages not found")
        }

        val dto = response.use {
            json.decodeFromStream<LuvyaaDto>(it.body.byteStream())
        }

        return dto.pages.mapIndexed { index, imageUrl ->
            val processedUrl = if (resizeService.isNotEmpty() && resizeService != "https://wsrv.nl/?url=") {
                "$resizeService$imageUrl"
            } else {
                imageUrl
            }
            Page(index, document.location(), processedUrl)
        }
    }

    companion object {
        val postIdRegex = """chapter_id\s*=\s*(\d+)""".toRegex()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL (Pages)"
            summary = "Masukkan URL layanan resize gambar untuk halaman (page list). Contoh: https://wsrv.nl/?url="
            setDefaultValue("")
            dialogTitle = "Resize Service URL"
            dialogMessage = "Masukkan URL layanan resize untuk halaman komik. Kosongkan untuk tidak menggunakan resize."
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