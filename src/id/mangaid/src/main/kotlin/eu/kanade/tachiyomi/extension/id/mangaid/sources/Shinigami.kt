package eu.kanade.tachiyomi.extension.id.mangaid.sources

import eu.kanade.tachiyomi.extension.id.mangaid.MangaEntry
import eu.kanade.tachiyomi.extension.id.mangaid.PREFIX_SHINIGAMI
import eu.kanade.tachiyomi.extension.id.mangaid.UA
import eu.kanade.tachiyomi.extension.id.mangaid.stripParens
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class Shinigami(private val client: OkHttpClient) {

    private val baseApi  = "https://api.shngm.io"
    private val baseWeb  = "https://c.shinigami.asia"

    private val headers = Headers.Builder()
        .add("Origin", baseWeb)
        .add("Referer", "$baseWeb/")
        .add("Accept", "application/json")
        .add("User-Agent", UA)
        .build()

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private fun get(url: String) =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute()

    private fun parseEntries(json: JsonObject): List<MangaEntry> {
        return json["data"]?.jsonArray?.map { el ->
            val item = el.jsonObject
            val id   = item["manga_id"]?.jsonPrimitive?.content
                ?: item["mangaId"]?.jsonPrimitive?.content ?: ""

            MangaEntry(
                manga = SManga.create().apply {
                    title         = stripParens(item["title"]?.jsonPrimitive?.content ?: "")
                    url           = "$PREFIX_SHINIGAMI/series/$id"
                    thumbnail_url = item["cover_portrait_url"]?.jsonPrimitive?.content
                        ?: item["cover_image_url"]?.jsonPrimitive?.content ?: ""
                    status = when (item["status"]?.jsonPrimitive?.intOrNull) {
                        1    -> SManga.ONGOING
                        2    -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                },
                updatedAt = item["latest_chapter_time"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        } ?: emptyList()
    }

    // ─── LIST ────────────────────────────────────────────────────────────────

    fun fetchLatest(page: Int): List<MangaEntry> {
        val resp = get("$baseApi/v1/manga/list?page=$page&page_size=24&sort=latest")
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        return parseEntries(json)
    }

    fun fetchPopular(page: Int): List<MangaEntry> {
        val resp = get("$baseApi/v1/manga/list?page=$page&page_size=24&sort=popularity")
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        return parseEntries(json)
    }

    fun fetchSearch(page: Int, query: String): List<MangaEntry> {
        val resp = get("$baseApi/v1/manga/list?page=$page&page_size=24&q=${query.encodeUrl()}")
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        return parseEntries(json)
    }

    // ─── DETAIL ──────────────────────────────────────────────────────────────

    fun fetchMangaDetails(slug: String): SManga {
        val resp = get("$baseApi/v1/manga/detail/$slug")
        val item = Json.parseToJsonElement(resp.body.string()).jsonObject["data"]?.jsonObject
            ?: throw Exception("Shinigami: manga detail not found")

        return SManga.create().apply {
            title         = stripParens(item["title"]?.jsonPrimitive?.content ?: "")
            url           = "$PREFIX_SHINIGAMI/series/$slug"
            thumbnail_url = item["cover_portrait_url"]?.jsonPrimitive?.content
                ?: item["cover_image_url"]?.jsonPrimitive?.content ?: ""
            description   = item["synopsis"]?.jsonPrimitive?.content ?: ""
            author        = item["author"]?.jsonPrimitive?.content ?: ""
            artist        = item["artist"]?.jsonPrimitive?.content ?: ""
            genre         = item["genres"]?.jsonArray
                ?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
            status = when (item["status"]?.jsonPrimitive?.intOrNull) {
                1    -> SManga.ONGOING
                2    -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ─── CHAPTERS ────────────────────────────────────────────────────────────

    fun fetchChapterList(slug: String): List<SChapter> {
        val resp     = get("$baseApi/v1/chapter/list/$slug?page=1&page_size=9999&sort=desc")
        val jsonRoot = Json.parseToJsonElement(resp.body.string()).jsonObject
        val chapters = jsonRoot["data"]?.jsonArray ?: return emptyList()

        return chapters.map { el ->
            val item      = el.jsonObject
            val chapterId = item["chapter_id"]?.jsonPrimitive?.content ?: ""
            val chNum     = item["chapter"]?.jsonPrimitive?.content ?: ""

            SChapter.create().apply {
                name        = "Chapter $chNum"
                url         = "$PREFIX_SHINIGAMI/chapter/$chapterId"
                date_upload = item["published_at"]?.jsonPrimitive?.longOrNull?.times(1000) ?: 0L
                chapter_number = chNum.toFloatOrNull() ?: -1f
            }
        }
    }

    // ─── PAGES ───────────────────────────────────────────────────────────────

    fun fetchPageList(chapterId: String): List<Page> {
        val resp     = get("$baseApi/v1/chapter/detail/$chapterId")
        val jsonRoot = Json.parseToJsonElement(resp.body.string()).jsonObject
        val images   = jsonRoot["data"]?.jsonObject?.get("images")?.jsonArray ?: return emptyList()

        return images.mapIndexed { idx, el ->
            Page(idx, imageUrl = el.jsonPrimitive.content)
        }
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
