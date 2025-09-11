package eu.kanade.tachiyomi.multisrc.mangathemesia

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.lang.ref.SoftReference
import java.text.SimpleDateFormat
import java.util.Locale

abstract class MangaThemesiaAlt(
    name: String,
    baseUrl: String,
    lang: String,
    mangaUrlDirectory: String = "/manga",
    dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US),
    private val randomUrlPrefKey: String = "pref_auto_random_url",
) : MangaThemesia(name, baseUrl, lang, mangaUrlDirectory, dateFormat), ConfigurableSource {

    protected open val listUrl = "$mangaUrlDirectory/list-mode/"
    protected open val listSelector = "div#content div.soralist ul li a.series"

    // preferences lokal untuk subclass ini (tidak bergantung pada superclass)
    protected val preferences by getPreferencesLazy {
        if (contains("__random_part_cache")) {
            edit().remove("__random_part_cache").apply()
        }
        if (contains("titles_without_random_part")) {
            edit().remove("titles_without_random_part").apply()
        }
    }

    /**
     * Effective base URL read from preferences, or fallback ke baseUrl asli.
     * Disimpan sebagai string lengkap (dengan scheme).
     */
    protected val prefBaseUrl: String
        get() = preferences.getString("overrideBaseUrl", baseUrl) ?: baseUrl

    /**
     * Safe HttpUrl built from prefBaseUrl; jika invalid, fallback ke baseUrl asli (yang diasumsikan valid).
     */
    protected val prefBaseUrlHttpUrl: HttpUrl
        get() = prefBaseUrl.toHttpUrlOrNull() ?: baseUrl.toHttpUrl()

    protected fun buildUrl(path: String): String {
        // path mungkin seperti "/manga/list-mode/" atau "manga/slug/"
        val cleaned = path.trimStart('/').trimEnd('/')
        return prefBaseUrlHttpUrl.newBuilder()
            .addPathSegments(cleaned)
            .build()
            .toString()
            .let { if (path.endsWith("/")) "$it/" else it }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Resize service preference
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL (Pages)"
            summary = preferences.getString("resize_service_url", null)
                ?: "Masukkan URL layanan resize gambar untuk halaman (page list)."
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
            dialogMessage = "Contoh: https://images.weserv.nl/?url="
        }
        resizeServicePref.setOnPreferenceChangeListener { _, newValue ->
            val newUrl = (newValue as? String)?.trim().takeIf { it?.isNotEmpty() == true }
            preferences.edit().putString("resize_service_url", newUrl).apply()
            resizeServicePref.summary = newUrl ?: "Masukkan URL layanan resize gambar untuk halaman (page list)."
            true
        }
        screen.addPreference(resizeServicePref)

        // Override base URL preference (domain override)
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Current domain: ${preferences.getString("overrideBaseUrl", baseUrl)}"
            setDefaultValue(baseUrl)
            dialogTitle = "Update domain untuk ekstensi ini"
            dialogMessage = "Original: $baseUrl\nMasukkan lengkap, mis. https://example.com"
        }

        baseUrlPref.setOnPreferenceChangeListener { _, newValue ->
    val ctx = screen.context
    var newUrl = (newValue as? String)?.trim().orEmpty()

    if (newUrl.isEmpty()) {
        preferences.edit().remove("overrideBaseUrl").apply()
        baseUrlPref.summary = "Current domain: $baseUrl"
        true
    } else {
        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
            newUrl = "https://$newUrl"
        }
        if (newUrl.toHttpUrlOrNull() == null) {
            Toast.makeText(ctx, "URL tidak valid, masukkan lengkap (mis. https://example.com)", Toast.LENGTH_SHORT).show()
            false
        } else {
            val normalized = newUrl.trimEnd('/')
            preferences.edit().putString("overrideBaseUrl", normalized).apply()
            baseUrlPref.summary = "Current domain: $normalized"
            true
        }
    }
}
        screen.addPreference(baseUrlPref)

        // existing switch preference for random URL behavior
        SwitchPreferenceCompat(screen.context).apply {
            key = randomUrlPrefKey
            title = intl["pref_dynamic_url_title"]
            summary = intl["pref_dynamic_url_summary"]
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    private fun getRandomUrlPref() = preferences.getBoolean(randomUrlPrefKey, true)

    private val mutex = Mutex()
    private var cachedValue: SoftReference<Map<String, String>>? = null
    private var fetchTime = 0L

    private suspend fun getUrlMapInternal(): Map<String, String> {
        if (fetchTime + 3600000 < System.currentTimeMillis()) {
            // reset cache
            cachedValue = null
        }

        // fast way
        cachedValue?.get()?.let {
            return it
        }
        return mutex.withLock {
            cachedValue?.get()?.let {
                return it
            }

            fetchUrlMap().also {
                cachedValue = SoftReference(it)
                fetchTime = System.currentTimeMillis()
                preferences.urlMapCache = it
            }
        }
    }

    protected open fun fetchUrlMap(): Map<String, String> {
        // gunakan prefBaseUrl agar override bekerja
        client.newCall(GET(buildUrl(listUrl), headers)).execute().use { response ->
            val document = response.asJsoup()

            return document.select(listSelector).associate {
                val url = it.absUrl("href")

                val slug = url.removeSuffix("/")
                    .substringAfterLast("/")

                val permaSlug = slug
                    .replaceFirst(slugRegex, "")

                permaSlug to slug
            }
        }
    }

    protected fun getUrlMap(cached: Boolean = false): Map<String, String> {
        return if (cached && cachedValue == null) {
            preferences.urlMapCache
        } else {
            runBlocking { getUrlMapInternal() }
        }
    }

    // cache in preference for webview urls
    private var SharedPreferences.urlMapCache: Map<String, String>
        get(): Map<String, String> {
            val value = getString("url_map_cache", "{}")!!
            return try {
                json.decodeFromString(value)
            } catch (_: Exception) {
                emptyMap()
            }
        }
        set(newMap) = edit().putString("url_map_cache", json.encodeToString(newMap)).apply()

    override fun searchMangaParse(response: Response): MangasPage {
        val mp = super.searchMangaParse(response)

        if (!getRandomUrlPref()) return mp

        val mangas = mp.mangas.toPermanentMangaUrls()

        return MangasPage(mangas, mp.hasNextPage)
    }

    protected fun List<SManga>.toPermanentMangaUrls(): List<SManga> {
        return onEach {
            val slug = it.url
                .removeSuffix("/")
                .substringAfterLast("/")

            val permaSlug = slug
                .replaceFirst(slugRegex, "")

            it.url = "$mangaUrlDirectory/$permaSlug/"
        }
    }

    protected open val slugRegex = Regex("""^(\d+-)""")

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (!getRandomUrlPref()) return super.mangaDetailsRequest(manga)

        val slug = manga.url
            .substringBefore("#")
            .removeSuffix("/")
            .substringAfterLast("/")
            .replaceFirst(slugRegex, "")

        val randomSlug = getUrlMap()[slug] ?: slug

        return GET(buildUrl("$mangaUrlDirectory/$randomSlug/"), headers)
    }

    override fun getMangaUrl(manga: SManga): String {
        if (!getRandomUrlPref()) return super.getMangaUrl(manga)

        val slug = manga.url
            .substringBefore("#")
            .removeSuffix("/")
            .substringAfterLast("/")
            .replaceFirst(slugRegex, "")

        val randomSlug = getUrlMap(true)[slug] ?: slug

        return buildUrl("$mangaUrlDirectory/$randomSlug/")
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)
}
