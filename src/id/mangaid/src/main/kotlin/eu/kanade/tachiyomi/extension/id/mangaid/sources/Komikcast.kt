package eu.kanade.tachiyomi.extension.id.mangaid.sources

import eu.kanade.tachiyomi.extension.id.mangaid.MangaEntry
import eu.kanade.tachiyomi.extension.id.mangaid.PREFIX_KOMIKCAST
import eu.kanade.tachiyomi.extension.id.mangaid.UA
import eu.kanade.tachiyomi.extension.id.mangaid.stripParens
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class Komikcast(private val client: OkHttpClient) {

    private val baseApi = "https://be.komikcast.fit"
    private val baseWeb = "https://v1.komikcast.fit"

    private val headers = Headers.Builder()
        .add("Origin", baseWeb)
        .add("Referer", "$baseWeb/")
        .add("Accept", "application/json")
        .add("User-Agent", UA)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private fun get(url: String) =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute()

    private fun parseEntries(json: JsonObject): List<MangaEntry> {
        return json["data"]?.jsonArray?.map { el ->
            val item = el.jsonObject
            val data = item["data"]?.jsonObject
            val slug = data?.get("slug")?.jsonPrimitive?.content ?: ""

            MangaEntry(
                manga = SManga.create().apply {
                    title         = stripParens(data?.get("title")?.jsonPrimitive?.content ?: "")
                    url           = "$PREFIX_KOMIKCAST/series/$slug"
                    thumbnail_url = data?.get("coverImage")?.jsonPrimitive?.content ?: ""
                    status = when (data?.get("status")?.jsonPrimitive?.content?.lowercase()) {
                        "ongoing"   -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else        -> SManga.UNKNOWN
                    }
                },
                updatedAt = runCatching {
                    dateFormat.parse(item["updatedAt"]?.jsonPrimitive?.content ?: "")?.time ?: 0L
                }.getOrDefault(0L),
            )
        } ?: emptyList()
    }

    // ─── LIST ────────────────────────────────────────────────────────────────

    fun fetchLatest(page: Int): List<MangaEntry> {
        val resp = get("$baseApi/series?take=24&page=$page&sort=latest&sortOrder=desc&includeMeta=true")
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        return parseEntries(json)
    }

    fun fetchPopular(page: Int): List<MangaEntry> {
        val resp = get("$baseApi/series?take=24&page=$page&sort=popularity&sortOrder=desc&includeMeta=true")
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        return parseEntries(json)
    }

    fun fetchSearch(page: Int, query: String): List<MangaEntry> {
        val q    = query.encodeUrl()
        val filter = "title=like=%22$q%22,nativeTitle=like=%22$q%22"
        val resp = get("$baseApi/series?take=24&page=$page&includeMeta=true&filter=$filter")
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        return parseEntries(json)
    }

    // ─── DETAIL ──────────────────────────────────────────────────────────────

    fun fetchMangaDetails(slug: String): SManga {
        val resp = get("$baseApi/series/$slug?includeMeta=true")
        val root = Json.parseToJsonElement(resp.body.string()).jsonObject
        val data = root["data"]?.jsonObject ?: throw Exception("Komikcast: detail not found")
        val meta = root["meta"]?.jsonObject

        return SManga.create().apply {
            title         = stripParens(data["title"]?.jsonPrimitive?.content ?: "")
            url           = "$PREFIX_KOMIKCAST/series/$slug"
            thumbnail_url = data["coverImage"]?.jsonPrimitive?.content ?: ""
            description   = data["synopsis"]?.jsonPrimitive?.content ?: ""
            author        = meta?.get("author")?.jsonPrimitive?.content ?: ""
            genre         = meta?.get("genres")?.jsonArray
                ?.joinToString(", ") { it.jsonPrimitive.content }
            status = when (data["status"]?.jsonPrimitive?.content?.lowercase()) {
                "ongoing"   -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else        -> SManga.UNKNOWN
            }
        }
    }

    // ─── CHAPTERS ────────────────────────────────────────────────────────────

    fun fetchChapterList(slug: String): List<SChapter> {
        val resp     = get("$baseApi/chapter?seriesSlug=$slug&take=9999&page=1&sortOrder=desc")
        val jsonRoot = Json.parseToJsonElement(resp.body.string()).jsonObject
        val chapters = jsonRoot["data"]?.jsonArray ?: return emptyList()

        return chapters.map { el ->
            val item      = el.jsonObject
            val chSlug    = item["slug"]?.jsonPrimitive?.content ?: ""
            val chNum     = item["chapter"]?.jsonPrimitive?.content ?: ""

            SChapter.create().apply {
                name        = "Chapter $chNum"
                url         = "$PREFIX_KOMIKCAST/chapter/$chSlug"
                date_upload = runCatching {
                    dateFormat.parse(item["createdAt"]?.jsonPrimitive?.content ?: "")?.time ?: 0L
                }.getOrDefault(0L)
                chapter_number = chNum.toFloatOrNull() ?: -1f
            }
        }
    }

    // ─── PAGES ───────────────────────────────────────────────────────────────

    fun fetchPageList(chSlug: String): List<Page> {
        val resp     = get("$baseApi/chapter/$chSlug")
        val jsonRoot = Json.parseToJsonElement(resp.body.string()).jsonObject
        val images   = jsonRoot["data"]?.jsonObject?.get("images")?.jsonArray ?: return emptyList()

        return images.mapIndexed { idx, el ->
            Page(idx, imageUrl = el.jsonPrimitive.content)
        }
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
