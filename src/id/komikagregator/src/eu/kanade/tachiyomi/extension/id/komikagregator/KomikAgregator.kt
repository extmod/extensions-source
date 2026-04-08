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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KomikAgregator : HttpSource() {

    override val id   = 1234567890123456789L
    override val name = "Komik Agregator"
    override val lang = "id"
    override val supportsLatest = true

    // Ganti dengan URL worker kamu (tanpa /api)
    override val baseUrl = "https://123.workers.dev"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseDate(str: String?): Long {
        if (str.isNullOrEmpty()) return 0L
        return try {
            dateFormat.parse(
                str.trimEnd('Z').substringBefore("+").substringBefore("."),
            )?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    // ─── POPULAR ───────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular?page=$page&page_size=24", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseListResponse(response)

    // ─── LATEST ────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest?page=$page&page_size=24", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseListResponse(response)

    // ─── SEARCH ────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?page=$page&page_size=24&query=${encode(query)}", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        parseListResponse(response)

    private fun parseListResponse(response: Response): MangasPage {
        val result = response.parseAs<NormalizedListResponse>()
        return MangasPage(result.data.map { it.toSManga() }, result.hasNextPage)
    }

    // ─── DETAIL ────────────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/detail?url=${encode(manga.url)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val d = response.parseAs<NormalizedDetail>()
        // Gabungkan altTitles ke description
        val altText = if (d.altTitles.isNotEmpty()) {
            "\n\nJudul Alternatif: ${d.altTitles.joinToString(", ")}"
        } else ""
        return SManga.create().apply {
            title         = d.title
            thumbnail_url = d.cover
            status        = d.status
            author        = d.author.orEmpty()
            artist        = d.artist.orEmpty()
            genre         = d.genres.orEmpty()
            description   = (d.description.orEmpty()) + altText
        }
    }

    // Buka di browser: ambil bagian ke-3 dari "source:slug:originalUrl"
    override fun getMangaUrl(manga: SManga): String =
        manga.url.split(":", limit = 3).getOrNull(2) ?: ""

    // ─── CHAPTER LIST ──────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl/chapters?url=${encode(manga.url)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val r = response.parseAs<NormalizedChapterListResponse>()
        return r.chapters.map { ch ->
            SChapter.create().apply {
                name        = ch.name
                url         = ch.url
                date_upload = parseDate(ch.date)
            }
        }
    }

    // ─── PAGE LIST ─────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl/pages?url=${encode(chapter.url)}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val r = response.parseAs<NormalizedPageListResponse>()
        return r.pages.mapIndexed { i, imageUrl -> Page(i, imageUrl = imageUrl) }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList()
}
