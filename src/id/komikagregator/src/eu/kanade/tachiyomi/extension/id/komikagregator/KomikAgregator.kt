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

class KomikAgregator : HttpSource() {

    override val id   = 1234567890123456789L
    override val name = "Komik Agregator"
    override val lang = "id"
    override val supportsLatest = true

    override val baseUrl = "https://komik-mauve.vercel.app"

    private val shinigamiUrl = "https://c.shinigami.asia"
    private val komikcastUrl = "https://v1.komikcast.fit"
    private val kiryuuUrl    = "https://v2.kiryuu.to"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json")

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
        val (source, slug) = manga.url.split(":", limit = 2)
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
                    title         = d.title ?: ""
                    thumbnail_url = d.coverPortraitUrl ?: d.coverImageUrl
                    status        = when (d.status) {
                        1    -> SManga.ONGOING
                        2    -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                    author      = tax["Author"]?.joinToString { it.name }.orEmpty()
                    description = d.description
                    genre       = tax["Genre"]?.joinToString { it.name }.orEmpty()
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
                    genre       = d.genres
                        ?.mapNotNull { it.data?.name ?: it.name }
                        ?.joinToString()
                        .orEmpty()
                }
            }
            else -> {
                val list = response.parseAs<List<KiryuuMangaDto>>()
                val d = list.firstOrNull() ?: throw Exception("Data kiryuu kosong")
                val cls = d.classList ?: emptyList()
                SManga.create().apply {
                    title         = d.title?.rendered ?: ""
                    thumbnail_url = d.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
                    status        = when {
                        cls.contains("status-ongoing")   -> SManga.ONGOING
                        cls.contains("status-completed") -> SManga.COMPLETED
                        else                             -> SManga.UNKNOWN
                    }
                    description = d.content?.rendered?.replace(Regex("<[^>]+>"), "")
                    genre       = cls
                        .filter { it.startsWith("genre-") }
                        .joinToString { it.removePrefix("genre-").replace("-", " ") }
                }
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        val (source, slug) = manga.url.split(":", limit = 2)
        return when (source) {
            "shinigami" -> "$shinigamiUrl/series/$slug"
            "komikcast" -> "$komikcastUrl/series/$slug"
            else        -> "$kiryuuUrl/manga/$slug"
        }
    }

    // ─── CHAPTER LIST ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val (source, slug) = manga.url.split(":", limit = 2)
        val path = when (source) {
            "shinigami" -> "/api/shinigami?path=${encode("/v1/chapter/$slug/list?page_size=3000")}"
            "komikcast" -> "/api/komikcast?path=${encode("/series/$slug/chapters")}"
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
                    SChapter.create().apply {
                        val num = ch.chapterNumber?.toString()?.replace(".0", "") ?: "?"
                        name        = "Chapter $num ${ch.title ?: ""}".trim()
                        this.url    = "shinigami:${ch.chapterId}"
                        date_upload = 0L
                    }
                }
            }
            "komikcast" -> {
                val r = response.parseAs<KomikcastChapterListResponse>()
                val mangaSlug = url
                    .substringAfter("/series/")
                    .substringBefore("/chapters")
                r.data.map { ch ->
                    val idx = ch.data?.index ?: ch.chapterIndex ?: "?"
                    SChapter.create().apply {
                        name        = "Chapter $idx"
                        this.url    = "komikcast:$mangaSlug:$idx"
                        date_upload = 0L
                    }
                }
            }
            else -> {
                val r = response.parseAs<KiryuuChapterListResponse>()
                r.data.map { ch ->
                    SChapter.create().apply {
                        name        = ch.name ?: ""
                        this.url    = "kiryuu:${ch.url}"
                        date_upload = 0L
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
                val path = r.data?.chapter?.path ?: ""
                val pages = r.data?.chapter?.data ?: emptyList()
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
                val regex = Regex("""src="(https?://[^"]+\.(jpg|png|webp)[^"]*)"""")
                regex.findAll(html)
                    .map { it.groupValues[1] }
                    .filter { it.contains("yuucdn.com") || it.contains("wp-content/uploads/imgsc") }
                    .distinct()
                    .mapIndexed { i, imgUrl -> Page(i, imageUrl = imgUrl) }
                    .toList()
            }
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList()

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
