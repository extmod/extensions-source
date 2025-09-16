package eu.kanade.tachiyomi.extension.id.komikstation

import android.app.Application
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import androidx.preference.EditTextPreference
import eu.kanade.tachiyomi.network.GET
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.util.asJsoup
import org.jsoup.Jsoup
import org.json.JSONObject
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

    // --- existing thumbnail/title logic (kehilangan jika fragment menggantikan) ---
    val img = document.select(seriesThumbnailSelector).firstOrNull()
    if (img != null) {
        val originalUrl = img.imgAttr().trim()
        if (originalUrl.isNotEmpty()) {
            manga.thumbnail_url = "$resizeCover$originalUrl"
            manga.title = img.attr("alt").trim()
        }
    }

    // --- try to find a JSON cache fragment used by the theme (very common pattern) ---
    try {
        val htmlText = document.html()

        // 1) typical gov-cache / ajax json pattern used by KomikStation in your samples
        val cacheRegex = Regex("""/(wp-content/cache/.+?\.json)""")
        val cacheMatch = cacheRegex.find(htmlText)

        val fragmentDoc: Document? = when {
            cacheMatch != null -> {
                val relative = cacheMatch.groupValues[1]
                // resolve absolute URL using baseUri of document
                val cacheUrl = try {
                    val base = document.baseUri().ifEmpty { baseUrl } // fallback baseUrl from extension
                    java.net.URL(java.net.URL(base), relative).toString()
                } catch (e: Exception) {
                    null
                }

                if (cacheUrl != null) {
                    val resp = client.newCall(GET(cacheUrl)).execute()
                    resp.use { r ->
                        if (!r.isSuccessful) null
                        else {
                            val body = r.body?.string().orEmpty()
                            // JSON usually contains a field with HTML fragment like "html" or "content"
                            try {
                                val json = org.json.JSONObject(body)
                                var htmlFragment: String? = null
                                if (json.has("html")) htmlFragment = json.getString("html")
                                else if (json.has("content")) htmlFragment = json.getString("content")
                                else {
                                    // sometimes JSON value is nested or the whole JSON is the HTML
                                    // try common keys or treat full body as HTML if it looks like HTML
                                    if (body.trimStart().startsWith("<") || body.contains("<div")) {
                                        htmlFragment = body
                                    }
                                }
                                if (htmlFragment != null) Jsoup.parse(htmlFragment) else null
                            } catch (ex: Exception) {
                                // if not valid json, maybe the response is raw HTML
                                if (body.trimStart().startsWith("<")) Jsoup.parse(body) else null
                            }
                        }
                    }
                } else null
            }

            // 2) fallback: sometimes there's an inline script calling ts_ajax_cache_buster.run(".../ajax/<id>.json")
            else -> {
                val apiRegex = Regex("""(\/wp-content\/cache\/[^\s'"]+?\/ajax\/[a-f0-9]+\.json)""")
                val apiMatch = apiRegex.find(htmlText)
                if (apiMatch != null) {
                    val relative = apiMatch.groupValues[1]
                    val cacheUrl = try {
                        val base = document.baseUri().ifEmpty { baseUrl }
                        java.net.URL(java.net.URL(base), relative).toString()
                    } catch (e: Exception) { null }

                    if (cacheUrl != null) {
                        val resp = client.newCall(GET(cacheUrl)).execute()
                        resp.use { r ->
                            if (!r.isSuccessful) null
                            else {
                                val body = r.body?.string().orEmpty()
                                try {
                                    val json = org.json.JSONObject(body)
                                    val htmlFragment = when {
                                        json.has("html") -> json.getString("html")
                                        json.has("content") -> json.getString("content")
                                        else -> null
                                    }
                                    if (htmlFragment != null) Jsoup.parse(htmlFragment) else null
                                } catch (ex: Exception) {
                                    if (body.trimStart().startsWith("<")) Jsoup.parse(body) else null
                                }
                            }
                        }
                    } else null
                } else null
            }
        }

        // Jika fragment success, ambil data dari fragmentDoc (lebih akurat & cepat)
        if (fragmentDoc != null) {
            // Several common selectors (fallback chain) for description, author, status, genres
            fun firstText(vararg selectors: String): String {
                for (sel in selectors) {
                    val el = fragmentDoc.select(sel).firstOrNull()
                    if (el != null) {
                        val t = el.text().trim()
                        if (t.isNotEmpty()) return t
                    }
                }
                return ""
            }

            val desc = firstText(
                "div.summary__content", // mangathemesia common
                "div.post-content", 
                "div.description", 
                "div.entry-content p", 
                "div#description", 
                "div.summary"
            )
            if (desc.isNotEmpty()) manga.description = desc

            // author - common patterns
            val author = run {
                // try labeled rows: "Author" or "Pengarang"
                val labeled = fragmentDoc.select("p:contains(Author), p:contains(Pengarang), li:contains(Author), li:contains(Pengarang)")
                    .firstOrNull()
                if (labeled != null) {
                    // find anchor or small text inside
                    val a = labeled.select("a").firstOrNull()
                    if (a != null) a.text().trim() else labeled.text().replace(Regex("(?i)Author:|Pengarang:|Author"), "").trim()
                } else {
                    // fallback meta-like
                    firstText("span.author, a[rel=author], div.author, p.author")
                }
            }
            if (author.isNotEmpty()) manga.author = author

            // status - try to detect mappings
            val statusText = firstText("p:contains(Status), li:contains(Status), span.status, div.status")
            if (statusText.isNotEmpty()) {
                manga.status = when {
                    Regex("ongoing", RegexOption.IGNORE_CASE).containsMatchIn(statusText) -> SManga.ONGOING
                    Regex("complete|completed|finished|end", RegexOption.IGNORE_CASE).containsMatchIn(statusText) -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }

            // genres/tags - gather anchors inside common containers
            val tagSelectors = listOf("div.genres a", "div.tags a", "p:contains(Genre) a", "li:contains(Genre) a", "span.genres a")
            val genres = mutableListOf<String>()
            for (sel in tagSelectors) {
                val els = fragmentDoc.select(sel)
                if (els.isNotEmpty()) {
                    els.forEach { if (it.text().isNotBlank()) genres.add(it.text().trim()) }
                    if (genres.isNotEmpty()) break
                }
            }
            if (genres.isNotEmpty()) manga.genre = genres.joinToString(", ")

            // If fragment contains a better thumbnail/title, override
            val fragImg = fragmentDoc.select(seriesThumbnailSelector).firstOrNull()
            if (fragImg != null) {
                val originalUrl = fragImg.imgAttr().trim()
                if (originalUrl.isNotEmpty()) {
                    manga.thumbnail_url = "$resizeCover$originalUrl"
                    val t = fragImg.attr("alt").trim()
                    if (t.isNotEmpty()) manga.title = t
                }
            } else {
                // alternative thumbnail selectors inside fragment
                val altThumb = fragmentDoc.select("img.wp-post-image, img.thumbnail, img.cover").firstOrNull()
                if (altThumb != null) {
                    val originalUrl = altThumb.attr("src").trim()
                    if (originalUrl.isNotEmpty()) manga.thumbnail_url = "$resizeCover$originalUrl"
                }
            }
        }
    } catch (e: Exception) {
        // jangan crash jika fetch gagal; biarkan super hasil tetap berlaku
        e.printStackTrace()
    }

    return manga
}

    override fun pageListParse(response: okhttp3.Response): List<Page> {
    val doc = response.asJsoup()
    val service = preferences.getString("resize_service_url", "")

    return doc.select(pageSelector).mapIndexed { i, img ->
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
    }
}