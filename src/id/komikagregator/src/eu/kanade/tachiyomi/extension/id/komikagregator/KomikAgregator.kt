package eu.kanade.tachiyomi.extension.id.komikagregator

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class KomikAgregator : HttpSource() {

    override val id = 1234567890123456789L
    override val name = "Komik Agregator"
    override val lang = "id"
    override val supportsLatest = true

    override val baseUrl = "https://123.komikmix.workers.dev"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json")

    private val cursorCache = mutableMapOf<String, MutableMap<Int, String?>>()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun cacheKey(route: String, query: String? = null): String {
        return if (query.isNullOrBlank()) route else "$route|$query"
    }

    private fun getCursor(route: String, page: Int, query: String? = null): String? {
        if (page <= 1) return null
        return cursorCache[cacheKey(route, query)]?.get(page)
    }

    private fun setCursor(route: String, page: Int, cursor: String?, query: String? = null) {
        if (cursor.isNullOrBlank()) return
        val key = cacheKey(route, query)
        val map = cursorCache.getOrPut(key) { mutableMapOf() }
        map[page + 1] = cursor
    }

    private fun buildListRequest(route: String, page: Int, query: String? = null): Request {
        val url = buildString {
            append("$baseUrl/$route?limit=24&page=$page")
            if (!query.isNullOrBlank()) {
                append("&query=${encode(query)}")
            }
        }
        return GET(url, headers)
    }
    
    private fun parseListResponse(response: Response): MangasPage {
        val result = response.parseAs<NormalizedListResponse>()
        return MangasPage(
            result.data.map { it.toSManga() },
            !result.nextCursor.isNullOrBlank()
        )
    }

    override fun popularMangaRequest(page: Int): Request =
        buildListRequest("popular", page)

    override fun popularMangaParse(response: Response): MangasPage =
        parseListResponse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        buildListRequest("latest", page)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseListResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        buildListRequest("search", page, query)

    override fun searchMangaParse(response: Response): MangasPage =
        parseListResponse(response)

    private fun parseListResponse(response: Response): MangasPage {
        val result = response.parseAs<NormalizedListResponse>()

        val url = response.request.url
        val route = url.encodedPath.substringAfterLast('/')
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = url.queryParameter("query")

        setCursor(route, page, result.nextCursor, query)

        return MangasPage(
            result.data.map { it.toSManga() },
            !result.nextCursor.isNullOrBlank()
        )
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/detail?url=${encode(manga.url)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val d = response.parseAs<NormalizedDetail>()

        val altText = if (d.altTitles.isNotEmpty()) {
            "\n\nJudul Alternatif: ${d.altTitles.joinToString(", ")}"
        } else {
            ""
        }

        return SManga.create().apply {
            title = d.title
            thumbnail_url = d.cover
            status = d.status
            author = d.author.orEmpty()
            artist = d.artist.orEmpty()
            genre = d.genres.orEmpty()
            description = d.description.orEmpty() + altText
        }
    }

    override fun getMangaUrl(manga: SManga): String =
        manga.url.split(":", limit = 3).getOrNull(2) ?: ""

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl/chapters?url=${encode(manga.url)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val r = response.parseAs<NormalizedChapterListResponse>()
        return r.chapters.map { ch ->
            SChapter.create().apply {
                name = ch.name
                url = ch.url
                date_upload = ch.date ?: 0L
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl/pages?url=${encode(chapter.url)}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val r = response.parseAs<NormalizedPageListResponse>()
        return r.pages.mapIndexed { i, imageUrl ->
            Page(i, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList()
}
