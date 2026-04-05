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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KomikAgregator : HttpSource() {

    override val id   = 1234567890123456789L
    override val name = "Komik Agregator"
    override val lang = "id"
    override val supportsLatest = true

    override val baseUrl = "https://komik-mauve.vercel.app"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            dateFormat.parse(
                dateStr.trimEnd('Z').substringBefore("+").substringBefore("."),
            )?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ─── POPULAR ─────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/popular".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "24")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage =
        parseAggregatorResponse(response)

    // ─── LATEST ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/latest".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "24")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseAggregatorResponse(response)

    // ─── SEARCH ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "24")
            .addQueryParameter("query", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseAggregatorResponse(response)

    // ─── PARSE LIST ──────────────────────────────────────────────────────────

    private fun parseAggregatorResponse(response: Response): MangasPage {
        val result = response.parseAs<AggregatorListResponse>()
        val mangas = result.data.map { it.toSManga() }
        return MangasPage(mangas, result.hasNextPage)
    }

    // ─── DETAIL ──────────────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request {
        val parts  = manga.url.split(":", limit = 4)
        val source = parts[0]
        val slug   = parts[1]
        val path = when (source) {
            "shinigami" -> "/api/shinigami?path=${encode("/v1/manga/detail/$slug")}"
            "komikcast" -> "/api/komikcast?path=${encode("/series/$slug")}"
            "kiryuu"    -> "/api/kiryuu?path=${encode("/wp-json/wp/v2/manga?slug[]=$slug&_embed")}"
            else        -> throw Exception("Source tidak dikenal: $source")
        }
        return GET("$baseUrl$path", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val url = response.request.url.toString()
        val source = when {
            url.contains("/api/shinigami") -> "shinigami"
            url.contains("/api/komikcast") -> "komikcast"
            else -> "kiryuu"
        }

        return when (source) {
            "shinigami" -> {
                val r = response.parseAs<ShinigamiDetailResponse>()
                val d = r.data
                val tax = d.taxonomy ?: emptyMap()
                SManga.create().apply {
                    title       = d.title ?: ""
                    status      = when (d.status) {
                        1    -> SManga.ONGOING
                        2    -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                    author      = tax["Author"]?.joinToString { it.name }.orEmpty()
                    artist      = tax["Artist"]?.joinToString { it.name }.orEmpty()
                    description = d.description
                    genre       = listOf(
                        tax["Genre"]?.joinToString { it.name }.orEmpty(),
                        tax["Format"]?.joinToString { it.name }.orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString()
                }
            }
            "komikcast" -> {
                val r = response.parseAs<KomikcastDetailResponse>()
                val d = r.data?.data ?: throw Exception("Data komikcast kosong")
                SManga.create().apply {
                    title         = d.title ?: ""
                    thumbnail_url = d.coverImage
                    status        = when (d.status?.lowercase()) {
                        "ongoing"   -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else        -> SManga.UNKNOWN
                    }
                    author      = d.author
                    description = d.synopsis ?: d.description
                    genre       = listOf(
                        d.genres?.mapNotNull { it.data?.name ?: it.name }?.joinToString().orEmpty(),
                        d.format ?: "",
                    ).filter { it.isNotBlank() }.joinToString()
                }
            }
            else -> {
                val list = response.parseAs<List<KiryuuMangaDto>>()
                val d = list.firstOrNull() ?: throw Exception("Data kiryuu kosong")
                val wpTerm = d.embedded?.wpTerm ?: emptyList()
                SManga.create().apply {
                    title         = d.title?.rendered ?: ""
                    thumbnail_url = d.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
                    status        = when {
                        d.classList?.contains("status-ongoing") == true   -> SManga.ONGOING
                        d.classList?.contains("status-completed") == true -> SManga.COMPLETED
                        else                                               -> SManga.UNKNOWN
                    }
                    author      = wpTerm.getOrNull(3)?.firstOrNull()?.name
                    artist      = wpTerm.getOrNull(4)?.firstOrNull()?.name
                    description = d.content?.rendered?.replace(Regex("<[^>]+>"), "")
                    genre       = listOf(
                        wpTerm.getOrNull(2)?.joinToString { it.name ?: "" }.orEmpty(),
                        wpTerm.getOrNull(1)?.firstOrNull()?.name ?: "",
                    ).filter { it.isNotBlank() }.joinToString()
                }
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        val parts = manga.url.split(":", limit = 4)
        return parts.getOrNull(3) ?: ""
    }

    // ─── CHAPTER LIST ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val parts  = manga.url.split(":", limit = 4)
        val source = parts[0]
        val slug   = parts[1]
        val id     = parts.getOrNull(2) ?: ""
        val path = when (source) {
            "shinigami" -> "/api/shinigami?path=${encode("/v1/chapter/$slug/list?page_size=3000")}"
            "komikcast" -> "/api/komikcast?path=${encode("/series/$id/chapters")}&slug=$slug"
            "kiryuu"    -> "/api/kiryuu?action=chapter_list&manga_id=$slug"
            else        -> throw Exception("Source tidak dikenal: $source")
        }
        return GET("$baseUrl$path", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url.toString()
        val source = when {
            url.contains("/api/shinigami") -> "shinigami"
            url.contains("/api/komikcast") -> "komikcast"
            else -> "kiryuu"
        }

        return when (source) {
            "shinigami" -> {
                val r = response.parseAs<ShinigamiChapterListResponse>()
                val list = r.chapterList.ifEmpty { r.data }
                list.map { ch ->
                    val num = ch.chapterNumber?.toString()?.replace(".0", "") ?: "?"
                    SChapter.create().apply {
                        name        = "Chapter $num ${ch.title ?: ""}".trim()
                        this.url    = "shinigami:${ch.chapterId}"
                        date_upload = parseDate(ch.releaseDate)
                    }
                }
            }
            "komikcast" -> {
                val r = response.parseAs<KomikcastChapterListResponse>()
                val mangaSlug = response.request.url.queryParameter("slug") ?: ""
                r.data.map { ch ->
                    val idx = ch.data?.index?.toString() ?: "?"
                    SChapter.create().apply {
                        name        = "Chapter $idx"
                        this.url    = "komikcast:$mangaSlug:$idx"
                        date_upload = parseDate(ch.createdAt)
                    }
                }
            }
            else -> {
                val r = response.parseAs<KiryuuChapterListResponse>()
                r.data.map { ch ->
                    SChapter.create().apply {
                        name        = ch.name ?: ""
                        this.url    = "kiryuu:${ch.url}"
                        date_upload = parseDate(ch.date)
                    }
                }
            }
        }
    }

    // ─── PAGE LIST ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val parts  = chapter.url.split(":", limit = 3)
        val source = parts[0]

        val path = when (source) {
            "shinigami" -> {
                val chapterId = parts[1]
                "/api/shinigami?path=${encode("/v1/chapter/detail/$chapterId")}"
            }
            "komikcast" -> {
                val mangaSlug  = parts[1]
                val chapterIdx = parts[2]
                "/api/komikcast?path=${encode("/series/$mangaSlug/chapters/$chapterIdx")}"
            }
            else -> {
                val chapterUrl = parts.drop(1).joinToString(":")
                return GET(chapterUrl, headers)
            }
        }
        return GET("$baseUrl$path", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString()
        return when {
            url.contains("/api/shinigami") -> {
                val r = response.parseAs<ShinigamiPageListResponse>()
                val baseUrl = r.data?.baseUrl ?: ""
                val path    = r.data?.chapter?.path ?: ""
                val pages   = r.data?.chapter?.data ?: emptyList()
                pages.mapIndexed { i, filename ->
                    Page(i, imageUrl = "$baseUrl$path$filename")
                }
            }
            url.contains("/api/komikcast") -> {
                val r = response.parseAs<KomikcastPageListResponse>()
                val images = r.data?.data?.images ?: r.data?.images ?: emptyList()
                images.mapIndexed { i, imageUrl -> Page(i, imageUrl = imageUrl) }
            }
            else -> {
                val html = response.body.string()
                val document = Jsoup.parseBodyFragment(html, "https://v2.kiryuu.to")
                document.select("main .relative section > img")
                    .mapIndexed { i, img ->
                        Page(i, imageUrl = img.absUrl("src"))
                    }
            }
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList()
}
