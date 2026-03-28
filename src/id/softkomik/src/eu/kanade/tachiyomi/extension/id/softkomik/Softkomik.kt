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
import org.jsoup.Jsoup

class Softkomik : HttpSource() {
    override val name = "Softkomik"
    override val baseUrl = "https://softkomik.co"
    override val lang = "id"
    override val supportsLatest = true

    private val rscHeaders = headersBuilder()
        .add("rsc", "1")
        .build()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::imageInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

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
        val url = "$baseUrl/komik/library".toHttpUrl().newBuilder()
            .addQueryParameter("sortBy", "newKomik")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, rscHeaders)
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
            return GET(url.build(), unauthHeaders("$baseUrl/"))
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
                    val minValue = filter.selected
                    if (minValue != "0") {
                        url.addQueryParameter("min", minValue)
                    }
                }
                else -> Unit
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

        val slug = response.request.url.pathSegments.lastOrNull()
            ?: throw Exception("Could not find manga slug")

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
        GET("$baseUrl/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val document = Jsoup.parse(body)
        val slug = response.request.url.pathSegments.lastOrNull()
            ?: throw Exception("Could not find chapter slug")

        val chapters = document.select(".chapter-list a").map { el ->
            val href = el.attr("href")
            val text = el.text().trim()
            val rawChapter = text.removePrefix("Chapter ").trim()

            SChapter.create().apply {
                url = href.ifEmpty { "/$slug/chapter/$rawChapter" }
                name = if (text.isNotBlank()) text else "Chapter $rawChapter"
                chapter_number = parseChapterNumber(rawChapter)
            }
        }

        if (chapters.isEmpty()) {
            throw Exception("No chapters found. Coba buka WebView.")
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun parseChapterNumber(raw: String): Float {
        val normalized = raw.trim().replace(',', '.')
        val match = Regex("""\d+(\.\d+)?""").find(normalized) ?: return -1f
        return match.value.toFloatOrNull() ?: -1f
    }

    private fun formatChapterDisplay(chapterStr: String): String {
        val normalized = chapterStr.trim().replace(',', '.')
        val match = Regex("""\d+(\.\d+)?""").find(normalized) ?: return chapterStr

        val floatVal = match.value.toFloatOrNull() ?: return chapterStr
        return if (floatVal == floatVal.toLong().toFloat()) {
            floatVal.toLong().toString()
        } else {
            floatVal.toString().trimEnd('0').trimEnd('.')
        }
    }

    // ======================== Pages ========================
    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val data = response.extractNextJs<ChapterPageDataDto>()
            ?: throw Exception("Could not find chapter data")

        if (data.imageSrc.isEmpty()) {
            throw Exception("No pages found")
        }

        val imageBaseUrl = "https://cd1.softkomik.online/softkomik"

        return data.imageSrc.mapIndexed { i, img ->
            Page(i, imageUrl = "$imageBaseUrl/${img.removePrefix("/")}")
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================= Utilities ==============================

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!cdnUrls.any { request.url.toString().startsWith(it) }) {
            return chain.proceed(request)
        }

        val response = try {
            chain.proceed(request)
        } catch (e: java.net.UnknownHostException) {
            null
        }

        if (response?.isSuccessful == true) return response
        response?.close()

        val currentHost = cdnUrls.firstOrNull { request.url.toString().startsWith(it) }
            ?: return chain.proceed(request)

        val imagePath = request.url.toString().removePrefix(currentHost).removePrefix("/")
        val otherHosts = cdnUrls.filter { it != currentHost }

        var latestResponse: Response? = null
        for (newHost in otherHosts) {
            latestResponse?.close()
            val newUrl = "$newHost/$imagePath".toHttpUrl()
            latestResponse = try {
                chain.proceed(request.newBuilder().url(newUrl).build())
            } catch (e: java.net.UnknownHostException) {
                null
            }
            if (latestResponse?.isSuccessful == true) return latestResponse
        }

        return latestResponse ?: chain.proceed(request)
    }

    private fun unauthHeaders(referer: String = "$baseUrl/"): Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("User-Agent", "Mozilla/5.0")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Origin", baseUrl)
        .add("Referer", referer)
        .build()

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
    private val cdnUrls = listOf(
        "https://cd1.softkomik.online/softkomik",
        "https://psy1.komik.im",
        "https://image.komik.im/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik",
    )
}
