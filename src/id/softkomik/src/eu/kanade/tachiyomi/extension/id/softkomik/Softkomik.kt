package eu.kanade.tachiyomi.extension.id.softkomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class Softkomik : HttpSource() {
    override val name = "Softkomik"
    override val baseUrl = "https://softkomik.co"
    override val lang = "id"
    override val supportsLatest = true

    // ============ VERCEL CONFIG ============
    private val vercelUrl = "https://project-qvmcp.vercel.app/api/token"

    private var cachedSession: SessionDto? = null
    private val cacheLock = Any()

    private val rscHeaders = headersBuilder()
        .add("rsc", "1")
        .build()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::imageInterceptor)
        .addInterceptor(::apiAuthInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============ TOKEN ============
    private fun getSession(): SessionDto {
        synchronized(cacheLock) {
            cachedSession?.let {
                if (it.ex > System.currentTimeMillis()) {
                    return it
                }
            }

            val request = GET(vercelUrl, headers)
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                throw Exception("Gagal fetch token dari Vercel (HTTP ${response.code})")
            }

            val session = response.use { it.parseAs<SessionDto>() }
            cachedSession = session
            return session
        }
    }

    // ============ AUTH INTERCEPTOR ============
    private fun apiAuthInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!request.url.toString().startsWith(apiUrl)) {
            return chain.proceed(request)
        }

        val session = getSession()
        val newRequest = request.newBuilder()
            .header("X-Token", session.token)
            .header("X-Sign", session.sign)
            .build()

        return chain.proceed(newRequest)
    }

    // ============ IMAGE CDN FALLBACK ============
    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val response = try {
            chain.proceed(originalRequest)
        } catch (e: Exception) {
            null
        }

        if (response?.isSuccessful == true) return response

        val currentHost = cdnUrls.firstOrNull { originalRequest.url.toString().startsWith(it) }
        if (currentHost == null) {
            return response ?: throw Exception("Failed to load image: ${originalRequest.url}")
        }

        response?.close()

        val imagePath = originalRequest.url.toString().removePrefix(currentHost).removePrefix("/")
        val otherHosts = cdnUrls.filter { it != currentHost }

        for (newHost in otherHosts) {
            val newUrl = "$newHost/$imagePath".toHttpUrl()
            try {
                val newResponse = chain.proceed(
                    originalRequest.newBuilder().url(newUrl).build()
                )
                if (newResponse.isSuccessful) return newResponse
                newResponse.close()
            } catch (e: Exception) {
                // Lanjutkan CDN berikutnya
            }
        }

        throw Exception("All CDN hosts failed for: $imagePath")
    }

    // ============ POPULAR ============
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/komik/library".toHttpUrl().newBuilder()
            .addQueryParameter("sortBy", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, rscHeaders)
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // ============ LATEST ============
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/komik/library".toHttpUrl().newBuilder()
            .addQueryParameter("sortBy", "newKomik")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, rscHeaders)
    }

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // ============ SEARCH ============
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$apiUrl/komik".toHttpUrl().newBuilder()
                .addQueryParameter("name", query)
                .addQueryParameter("search", "true")
                .addQueryParameter("limit", "20")
                .addQueryParameter("page", page.toString())
            return GET(url.build(), headers)
        }

        val url = "$baseUrl/komik/library".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> url.addQueryParameter("status", filter.selected)
                is TypeFilter -> url.addQueryParameter("type", filter.selected)
                is GenreFilter -> url.addQueryParameter("genre", filter.selected)
                is SortFilter -> url.addQueryParameter("sortBy", filter.selected)
                is MinChapterFilter -> {
                    val min = filter.state.toIntOrNull()
                    if (min != null && min > 0) {
                        url.addQueryParameter("min", min.toString())
                    }
                }
                else -> {} // ← perbaikan error 1
            }
        }

        return GET(url.build(), rscHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val libData = if (response.request.url.toString().contains(apiUrl)) {
            response.parseAs<LibDataDto>()
        } else {
            response.extractNextJs<LibDataDto>()
        } ?: throw Exception("Could not find library data")

        val mangas = libData.data.map { manga ->
            SManga.create().apply {
                setUrlWithoutDomain(manga.title_slug)
                title = manga.title
                thumbnail_url = "$coverUrl/${manga.gambar.removePrefix("/")}"
            }
        }
        return MangasPage(mangas, libData.page < libData.maxPage)
    }

    // ============ MANGA DETAILS ============
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/${manga.url}", rscHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = response.extractNextJs<MangaDetailsDto>()
            ?: throw Exception("Could not find manga details")

        val slug = response.request.url.pathSegments.lastOrNull()!!
        return SManga.create().apply {
            setUrlWithoutDomain(slug)
            title = manga.title
            author = manga.author
            description = manga.sinopsis
            genre = manga.Genre?.joinToString()
            status = when (manga.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "tamat" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = "$coverUrl/${manga.gambar.removePrefix("/")}"
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    // ============ CHAPTERS ============
    override fun chapterListRequest(manga: SManga): Request {
        val url = "$apiUrl/komik/${manga.url}/chapter?limit=9999999"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<ChapterListDto>()
        val slug = response.request.url.pathSegments[1]

        return dto.chapter.map { chapter ->
            val chapterNumStr = chapter.chapter
            val chapterNum = chapterNumStr.substringBefore(".").toFloatOrNull() ?: -1f
            val displayNum = formatChapterDisplay(chapterNumStr)

            SChapter.create().apply {
                url = "/$slug/chapter/$chapterNumStr"
                name = "Chapter $displayNum"
                chapter_number = chapterNum
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun formatChapterDisplay(chapterStr: String): String {
        val parts = chapterStr.split(".")
        val numPart = parts[0]
        val suffix = parts.drop(1).joinToString(".")

        val floatVal = numPart.toFloatOrNull() ?: return chapterStr
        val formatted = if (floatVal == floatVal.toLong().toFloat()) {
            floatVal.toLong().toString()
        } else {
            floatVal.toString().trimEnd('0').trimEnd('.')
        }

        return if (suffix.isNotEmpty()) "$formatted.$suffix" else formatted
    }

    // ============ PAGES ============
    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val data = response.extractNextJs<ChapterPageDataDto>()
            ?: throw Exception("Could not find chapter data")

        val imageSrc = data.imageSrc.ifEmpty {
            val slug = response.request.url.pathSegments[0]
            val chapter = response.request.url.pathSegments[2]
            val urlApi = "$apiUrl/komik/$slug/chapter/$chapter/img/${data._id}"

            client.newCall(GET(urlApi, headers)).execute().use {
                it.parseAs<ChapterPageImagesDto>().imageSrc
            }
        }

        if (imageSrc.isEmpty()) {
            throw Exception("Chapter kosong atau memerlukan login")
        }

        val imageBaseUrl = if (data.storageInter2 == true) cdnUrls[2] else cdnUrls[0]

        return imageSrc.mapIndexed { i, img ->
            Page(i, imageUrl = "$imageBaseUrl/${img.removePrefix("/")}")
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============ FILTERS ============
    override fun getFilterList() = FilterList(
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        SortFilter(),
        Filter.Separator(),  // ← perbaikan error 2
        Filter.Header("Filter tidak dapat digabung dengan pencarian teks"),  // ← perbaikan error 2
        MinChapterFilter(),
    )

    // ============ CONSTANTS ============
    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverUrl = "https://cover.softdevices.my.id/softkomik-cover"

    private val cdnUrls = listOf(
        "https://psy1.komik.im",
        "https://image.komik.im/softkomik",
        "https://cdn1.softkomik.online/softkomik",
        "https://cd1.softkomik.online/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik",
    )
}
