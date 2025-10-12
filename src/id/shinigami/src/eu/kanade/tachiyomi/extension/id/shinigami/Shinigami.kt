package eu.kanade.tachiyomi.extension.id.shinigami

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Dns
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Locale

class Shinigami : HttpSource(), ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val baseUrl: String
        get() = preferences.getString("overrideBaseUrl", "https://app.shinigami.asia")!!

    override val id = 3411809758861089969
    override val name = "Shinigami"

    private val apiUrl = "https://api.shngm.io"
    private val cdnUrl = "https://delivery.shngm.id"

    override val lang = "id"
    override val supportsLatest = true

    private val apiHeaders: Headers by lazy { apiHeadersBuilder().build() }

    companion object {
        private const val TAG = "ShinigamiExtension"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
    }

    // Custom DNS Resolver dengan DNS over HTTPS dan Logging
    private class DoHResolver(private val fallbackClient: OkHttpClient) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Daftar domain yang perlu menggunakan DoH
            val dohDomains = listOf(
                "wsrv.nl",
                "images.weserv.nl",
                "img.weserv.nl",
                "delivery.shngm.id",
            )

            if (!dohDomains.any { hostname.contains(it) }) {
                return Dns.SYSTEM.lookup(hostname)
            }

            Log.d(TAG, "🔍 DNS Lookup untuk: $hostname")

            return try {
                // Menggunakan Cloudflare DNS over HTTPS
                val url = "https://1.1.1.1/dns-query?name=$hostname&type=A"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()

                Log.d(TAG, "📡 Menggunakan DoH (DNS over HTTPS) via Cloudflare")
                val response = fallbackClient.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")

                if (json.has("Answer")) {
                    val answers = json.getJSONArray("Answer")
                    val ips = (0 until answers.length()).mapNotNull { i ->
                        try {
                            val ip = answers.getJSONObject(i).getString("data")
                            InetAddress.getByName(ip)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (ips.isNotEmpty()) {
                        Log.i(TAG, "✅ DoH Success - Resolved $hostname ke ${ips.joinToString { it.hostAddress }}")
                        return ips
                    }
                }

                Log.w(TAG, "⚠️ DoH gagal, fallback ke DNS sistem")
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                Log.e(TAG, "❌ DoH Error: ${e.message}")
                // Fallback ke DNS sistem
                try {
                    val systemResult = Dns.SYSTEM.lookup(hostname)
                    Log.i(TAG, "✅ Fallback DNS Sistem Success - $hostname ke ${systemResult.joinToString { it.hostAddress }}")
                    systemResult
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ DNS Sistem juga gagal: ${e2.message}")
                    // Fallback manual ke IP known untuk wsrv.nl
                    when {
                        hostname.contains("wsrv.nl") || hostname.contains("weserv.nl") -> {
                            Log.w(TAG, "🔄 Fallback Manual - Menggunakan IP hardcoded untuk wsrv.nl: 178.21.17.10")
                            listOf(InetAddress.getByName("178.21.17.10"))
                        }
                        else -> throw e2
                    }
                }
            }
        }
    }

    // Client untuk DoH resolver
    private val dohClient = OkHttpClient.Builder()
        .build()

    override val client = network.cloudflareClient.newBuilder()
        .dns(DoHResolver(dohClient))
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            // Log request untuk proxy image
            if (url.contains("wsrv.nl") || url.contains("weserv.nl")) {
                Log.d(TAG, "🖼️ Image Request via Proxy: ${request.url.host}")
            }

            val headers = request.headers.newBuilder().apply {
                removeAll("X-Requested-With")
            }.build()

            try {
                val response = chain.proceed(request.newBuilder().headers(headers).build())

                // Log response status untuk proxy
                if (url.contains("wsrv.nl") || url.contains("weserv.nl")) {
                    if (response.isSuccessful) {
                        Log.i(TAG, "✅ Image Proxy Success: ${response.code}")
                    } else {
                        Log.e(TAG, "❌ Image Proxy Failed: ${response.code} - ${response.message}")
                    }
                }

                response
            } catch (e: Exception) {
                Log.e(TAG, "❌ Request Failed untuk ${request.url.host}: ${e.message}")
                throw e
            }
        }
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("X-Requested-With", randomString((1..20).random()))

    private fun randomString(length: Int) = buildString {
        val charPool = ('a'..'z') + ('A'..'Z')
        repeat(length) { append(charPool.random()) }
    }

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .add("Accept", "application/json")
        .add("DNT", "1")
        .add("Origin", baseUrl)
        .add("Sec-GPC", "1")

    // Image Proxy Manager dengan multiple fallback dan logging
    private fun getImageProxyUrl(originalUrl: String, width: Int = 300, quality: Int = 75): String {
        val proxyMode = preferences.getString("proxy_mode", "wsrv")
        val customProxy = preferences.getString("resize_service_url", null)

        // Jika ada custom proxy, gunakan itu
        if (!customProxy.isNullOrBlank()) {
            Log.d(TAG, "📦 Menggunakan Custom Proxy: $customProxy")
            return "$customProxy$originalUrl"
        }

        // Pilih proxy berdasarkan mode
        val proxyUrl = when (proxyMode) {
            "wsrv" -> "https://wsrv.nl/?w=$width&q=$quality&url=$originalUrl"
            "images" -> "https://images.weserv.nl/?w=$width&q=$quality&url=$originalUrl"
            "img" -> "https://img.weserv.nl/?w=$width&q=$quality&url=$originalUrl"
            "direct" -> {
                Log.d(TAG, "🔗 Direct Mode - Tanpa proxy")
                originalUrl
            }
            else -> "https://wsrv.nl/?w=$width&q=$quality&url=$originalUrl"
        }

        if (proxyMode != "direct") {
            Log.d(TAG, "🔄 Proxy Mode: $proxyMode (${proxyUrl.split("?").firstOrNull()?.split("://")?.lastOrNull() ?: "unknown"})")
        }

        return proxyUrl
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
            .addQueryParameter("sort", "popularity")
            .build()
        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val rootObject = response.parseAs<ShinigamiBrowseDto>()
        val projectList = rootObject.data.map(::popularMangaFromObject)
        val hasNextPage = rootObject.meta.page < rootObject.meta.totalPage
        return MangasPage(projectList, hasNextPage)
    }

    private fun popularMangaFromObject(obj: ShinigamiBrowseDataDto): SManga = SManga.create().apply {
        title = obj.title ?: ""
        thumbnail_url = obj.thumbnail?.let {
            getImageProxyUrl(it, width = 150, quality = 75)
        }
        url = obj.mangaId ?: ""
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
            .addQueryParameter("sort", "latest")
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        return GET(url.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/series/${manga.url}"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }
        return GET("$apiUrl/v1/manga/detail/${manga.url}", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaDetailsResponse = response.parseAs<ShinigamiMangaDetailDto>()
        val mangaDetails = mangaDetailsResponse.data
        return SManga.create().apply {
            author = mangaDetails.taxonomy["Author"]?.joinToString { it.name }.orEmpty()
            artist = mangaDetails.taxonomy["Artist"]?.joinToString { it.name }.orEmpty()
            status = mangaDetails.status.toStatus()
            description = mangaDetails.description
            val genres = mangaDetails.taxonomy["Genre"]?.joinToString { it.name }.orEmpty()
            val type = mangaDetails.taxonomy["Format"]?.joinToString { it.name }.orEmpty()
            genre = listOf(genres, type).filter { it.isNotBlank() }.joinToString()
        }
    }

    private fun Int.toStatus(): Int {
        return when (this) {
            1 -> SManga.ONGOING
            2 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/v1/chapter/${manga.url}/list?page_size=3000", apiHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ShinigamiChapterListDto>()
        return result.chapterList.map(::chapterFromObject)
    }

    private fun chapterFromObject(obj: ShinigamiChapterListDataDto): SChapter = SChapter.create().apply {
        date_upload = dateFormat.tryParse(obj.date)
        name = "Chapter ${obj.name.toString().replace(".0", "")} ${obj.title}"
        url = obj.chapterId
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }
        return GET("$apiUrl/v1/chapter/detail/${chapter.url}", apiHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ShinigamiPageListDto>()
        val useProxy = preferences.getBoolean("use_image_proxy", true)

        Log.d(TAG, "📚 Loading ${result.pageList.chapterPage.pages.size} pages, Proxy: ${if (useProxy) "Enabled" else "Disabled"}")

        return result.pageList.chapterPage.pages.mapIndexed { index, imageName ->
            val originalImageUrl = "$cdnUrl${result.pageList.chapterPage.path}$imageName"
            val finalImageUrl = if (useProxy) {
                getImageProxyUrl(originalImageUrl, width = 1200, quality = 85)
            } else {
                originalImageUrl
            }
            Page(index = index, imageUrl = finalImageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .add("DNT", "1")
            .add("referer", "$baseUrl/")
            .add("sec-fetch-dest", "empty")
            .add("Sec-GPC", "1")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Info preference untuk menampilkan status
        val infoPref = androidx.preference.Preference(screen.context).apply {
            key = "info_logs"
            title = "Cara Melihat Logs"
            summary = "Buka Logcat dengan filter 'ShinigamiExtension' untuk melihat status DNS dan proxy fallback"
            isEnabled = false
        }
        screen.addPreference(infoPref)

        // Toggle untuk menggunakan proxy
        val useProxyPref = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = "use_image_proxy"
            title = "Gunakan Image Proxy"
            summary = "Aktifkan untuk menggunakan proxy image (bypass blocking). Cek logcat untuk status."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Log.i(TAG, "⚙️ Image Proxy: ${if (enabled) "ENABLED" else "DISABLED"}")
                true
            }
        }
        screen.addPreference(useProxyPref)

        // Pilihan proxy mode
        val proxyModePref = ListPreference(screen.context).apply {
            key = "proxy_mode"
            title = "Pilih Image Proxy"
            entries = arrayOf(
                "wsrv.nl (Default)",
                "images.weserv.nl (Alternatif 1)",
                "img.weserv.nl (Alternatif 2)",
                "Direct (Tanpa Proxy)"
            )
            entryValues = arrayOf("wsrv", "images", "img", "direct")
            setDefaultValue("wsrv")
            summary = "Ganti proxy jika yang satu diblokir. Cek logcat untuk status DNS & proxy."
            setOnPreferenceChangeListener { _, newValue ->
                val mode = newValue as String
                val modeName = when(mode) {
                    "wsrv" -> "wsrv.nl"
                    "images" -> "images.weserv.nl"
                    "img" -> "img.weserv.nl"
                    "direct" -> "Direct (No Proxy)"
                    else -> "Unknown"
                }
                Log.i(TAG, "⚙️ Proxy Mode Changed: $modeName")
                true
            }
        }
        screen.addPreference(proxyModePref)

        // Custom proxy URL
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Custom Proxy URL (Opsional)"
            summary = "Masukkan URL proxy custom. Contoh: https://your-worker.workers.dev/?url="
            setDefaultValue(null)
            dialogTitle = "Custom Proxy URL"
            dialogMessage = "Kosongkan untuk menggunakan proxy bawaan. URL akan digabungkan dengan URL gambar asli."
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue as? String
                if (!url.isNullOrBlank()) {
                    Log.i(TAG, "⚙️ Custom Proxy Set: $url")
                } else {
                    Log.i(TAG, "⚙️ Custom Proxy Cleared - Using default")
                }
                true
            }
        }
        screen.addPreference(resizeServicePref)

        // Base URL override
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = "Update domain untuk ekstensi ini"
            dialogMessage = "Original: https://app.shinigami.asia"
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                preferences.edit().putString("overrideBaseUrl", newUrl).apply()
                Log.i(TAG, "⚙️ Base URL Changed: $newUrl")
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }
}
