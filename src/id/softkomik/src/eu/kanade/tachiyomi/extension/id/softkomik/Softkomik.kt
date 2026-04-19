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

    private var sessionCache: VercelTokenDto? = null

    private val sessionClient by lazy {
        network.cloudflareClient.newBuilder().build()
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::retryInterceptor)
        .addInterceptor(::apiAuthInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val rscHeaders = headersBuilder()
        .add("rsc", "1")
        .build()

    // ======================== Popular ========================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/komik/library".toHttpUrl().newBuilder()
            .addQueryParameter("sortBy", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, rscHeaders)
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // ======================== Latest ========================
    override fun latestUpdatesRequest(page: Int): Request {
    val url = "$apiUrl/komik".toHttpUrl().newBuilder()
        .addQueryParameter("sortBy", "new")
        .addQueryParameter("limit", "24")
        .addQueryParameter("page", page.toString())
        .build()
    return GET(url, headers)
}

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // ======================== Search ========================
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
                    val minValue = filter.state.toIntOrNull()
                    if (minValue != null && minValue > 0) {
                        url.addQueryParameter("min", minValue.toString())
                    }
                }
                else -> {}
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

    // ======================== Details ========================
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

    // ======================== Chapters ========================
    override fun chapterListRequest(manga: SManga): Request =
        GET("$vercelUrl/api/token?slug=${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val tokenDto = response.parseAs<VercelTokenDto>()
        val slug = response.request.url.queryParameter("slug")!!

        val chHeaders = headersBuilder()
            .set("X-Token", tokenDto.token)
            .set("X-Sign", tokenDto.sign)
            .build()

        val chResponse = sessionClient.newCall(
            GET("$apiUrl/komik/$slug/chapter?limit=9999999", chHeaders),
        ).execute()

        val dto = chResponse.parseAs<ChapterListDto>()
        return dto.chapter.map { chapter ->
            val chapterNumStr = chapter.chapter
            val chapterNum = chapterNumStr.substringBefore(".").toFloatOrNull() ?: -1f
            SChapter.create().apply {
                url = "/$slug/chapter/$chapterNumStr"
                name = "Chapter ${formatChapterDisplay(chapterNumStr)}"
                chapter_number = chapterNum
            }
        }.sortedByDescending { it.chapter_number }
    }

    // ======================== Pages ========================
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val slug = parts[1]
        val chapterParam = parts.drop(3).joinToString("/")
        val url = "$vercelUrl/api/token".toHttpUrl().newBuilder()
            .addQueryParameter("slug", slug)
            .addQueryParameter("chapter", chapterParam)
            .build()
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<VercelTokenDto>()
        if (dto.images.isEmpty()) throw Exception("Tidak ada gambar ditemukan")
        return dto.images.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ======================== Interceptor ========================
    private fun apiAuthInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.contains("softdevices.my.id")) return chain.proceed(request)

        val session = getSession()
        var response = chain.proceed(
            request.newBuilder()
                .header("X-Token", session.token)
                .header("X-Sign", session.sign)
                .build(),
        )
        if (!response.isSuccessful) {
            response.close()
            sessionCache = null
            val fresh = fetchSession()
            response = chain.proceed(
                request.newBuilder()
                    .header("X-Token", fresh.token)
                    .header("X-Sign", fresh.sign)
                    .build(),
            )
        }
        return response
    }
    
    private fun retryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.contains("project-qvmcp")) return chain.proceed(request)

        var response = chain.proceed(request)
        if (!response.isSuccessful) {
            response.close()
            response = chain.proceed(request)
        }
        return response
    }

    private fun getSession(): VercelTokenDto {
        sessionCache?.takeIf { it.exp > System.currentTimeMillis() }?.let { return it }
        return fetchSession()
    }

    private fun fetchSession(): VercelTokenDto {
        val response = sessionClient.newCall(GET("$vercelUrl/api/token", headers)).execute()
        if (!response.isSuccessful) throw Exception("Gagal mendapatkan session (${response.code})")
        return response.parseAs<VercelTokenDto>().also { sessionCache = it }
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

    override fun getFilterList() = FilterList(
        Filter.Header("Filter tidak bisa digabungkan dengan pencarian teks."),
        Filter.Separator(),
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        MinChapterFilter(),
    )

    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverUrl = "https://cover.softdevices.my.id/softkomik-cover"
    private val vercelUrl = "https://project-qvmcp.vercel.app"
}
