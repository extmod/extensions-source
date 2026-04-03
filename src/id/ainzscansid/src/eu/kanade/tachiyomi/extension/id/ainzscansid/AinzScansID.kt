package eu.kanade.tachiyomi.extension.id.ainzscansid

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.double
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AinzScansID : HttpSource() {

    override val name = "AinzScansID"
    override val baseUrl = "https://v1.ainzscans01.com"
    override val lang = "id"
    override val supportsLatest = true

    private val apiUrl = "https://api.ainzscans01.com/api"
    private val json: Json by injectLazy()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("sort", "view_count")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", "22")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = json.parseToJsonElement(response.body.string()).jsonObject
        val data = json["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val page = json["page"]?.jsonPrimitive?.intOrNull ?: 1
        val totalPages = json["total_pages"]?.jsonPrimitive?.intOrNull ?: 1
        val mangas = data.map { it.jsonObject.toSManga() }
        return MangasPage(mangas, page < totalPages)
    }

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("sort", "latest")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", "22")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ==================== SEARCH ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("type", "COMIC")
            .addQueryParameter("q", query)
            .addQueryParameter("sort", "latest")
            .addQueryParameter("order", "desc")
            .addQueryParameter("limit", "22")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ==================== DETAIL ====================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/comic/")
        return GET("$apiUrl/series/comic/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        return SManga.create().apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            thumbnail_url = obj["poster_image_url"]?.jsonPrimitive?.content
            description = obj["synopsis"]?.jsonPrimitive?.content
            author = obj["author_name"]?.jsonPrimitive?.content
            artist = obj["artist_name"]?.jsonPrimitive?.content
            status = when (obj["comic_status"]?.jsonPrimitive?.content) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                "DROPPED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            genre = obj["genres"]?.jsonArray
                ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
        }
    }

    // ==================== CHAPTER LIST ====================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/comic/")
        return GET("$apiUrl/series/comic/$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val seriesSlug = obj["slug"]?.jsonPrimitive?.content ?: ""
        val units = obj["units"]?.jsonArray ?: return emptyList()

        return units.map { unit ->
            val u = unit.jsonObject
            SChapter.create().apply {
                val chapterSlug = u["slug"]?.jsonPrimitive?.content ?: ""
                url = "/comic/$seriesSlug/chapter/$chapterSlug"
                val num = u["number"]?.jsonPrimitive?.double
                name = if (num != null) {
                    val numStr = if (num % 1.0 == 0.0) num.toInt().toString() else num.toString()
                    "Chapter $numStr"
                } else {
                    u["title"]?.jsonPrimitive?.content ?: "Chapter ?"
                }
                chapter_number = u["number"]?.jsonPrimitive?.double?.toFloat() ?: -1f
                date_upload = runCatching {
                    dateFormat.parse(u["created_at"]?.jsonPrimitive?.content ?: "")?.time ?: 0L
                }.getOrDefault(0L)
            }
        }
    }

    // ==================== PAGES ====================

    override fun pageListRequest(chapter: SChapter): Request {
        // url format: /comic/{manga-slug}/chapter/{chapter-slug}
        val path = chapter.url.removePrefix("/comic/")
        // path = {manga-slug}/chapter/{chapter-slug}
        return GET("$apiUrl/series/comic/$path", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val pages = obj["chapter"]?.jsonObject?.get("pages")?.jsonArray ?: return emptyList()

        return pages.mapIndexed { index, page ->
            val p = page.jsonObject
            Page(
                index = index,
                imageUrl = p["image_url"]?.jsonPrimitive?.content,
            )
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ==================== HELPER ====================

    private fun kotlinx.serialization.json.JsonObject.toSManga(): SManga {
        return SManga.create().apply {
            title = this@toSManga["title"]?.jsonPrimitive?.content ?: ""
            thumbnail_url = this@toSManga["poster_image_url"]?.jsonPrimitive?.content
            description = this@toSManga["synopsis"]?.jsonPrimitive?.content
            author = this@toSManga["author_name"]?.jsonPrimitive?.content
            status = when (this@toSManga["comic_status"]?.jsonPrimitive?.content) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                "DROPPED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            url = "/comic/${this@toSManga["slug"]?.jsonPrimitive?.content}"
        }
    }
}
