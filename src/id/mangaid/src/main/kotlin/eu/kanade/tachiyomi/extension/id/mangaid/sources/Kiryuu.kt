package eu.kanade.tachiyomi.extension.id.mangaid.sources

import eu.kanade.tachiyomi.extension.id.mangaid.MangaEntry
import eu.kanade.tachiyomi.extension.id.mangaid.PREFIX_KIRYUU
import eu.kanade.tachiyomi.extension.id.mangaid.UA
import eu.kanade.tachiyomi.extension.id.mangaid.decodeHtml
import eu.kanade.tachiyomi.extension.id.mangaid.stripParens
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class Kiryuu(private val client: OkHttpClient) {

    private val baseUrl = "https://v2.kiryuu.to"

    private val headers = Headers.Builder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/html")
        .add("User-Agent", UA)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private fun get(url: String) =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute()

    private fun parseItems(jsonStr: String): List<MangaEntry> {
        val arr = Json.parseToJsonElement(jsonStr).jsonArray

        // build updatedAt map from recent chapters (same logic as JS)
        val chapterMap = mutableMapOf<String, Long>()
        runCatching {
            val chRes = get("$baseUrl/wp-json/wp/v2/chapter?per_page=50&orderby=date&order=desc")
            val chArr = Json.parseToJsonElement(chRes.body.string()).jsonArray
            for (ch in chArr) {
                val slug      = ch.jsonObject["slug"]?.jsonPrimitive?.content ?: continue
                val date      = ch.jsonObject["date_gmt"]?.jsonPrimitive?.content ?: continue
                val mangaSlug = slug.replace(Regex("-chapter-[\\d-]+$"), "")
                if (!chapterMap.containsKey(mangaSlug)) {
                    chapterMap[mangaSlug] = runCatching {
                        dateFormat.parse(date)?.time ?: 0L
                    }.getOrDefault(0L)
                }
            }
        }

        return arr.map { el ->
            val item   = el.jsonObject
            val slug   = item["slug"]?.jsonPrimitive?.content ?: ""
            val clsRaw = item["class_list"]

            // class_list bisa array atau object
            val classList: List<String> = when {
                clsRaw?.jsonArray != null -> clsRaw.jsonArray.map { it.jsonPrimitive.content }
                clsRaw?.jsonObject != null -> clsRaw.jsonObject.values.map { it.jsonPrimitive.content }
                else -> emptyList()
            }

            val featuredMedia = runCatching {
                item["_embedded"]?.jsonObject
                    ?.get("wp:featuredmedia")?.jsonArray
                    ?.get(0)?.jsonObject
                    ?.get("source_url")?.jsonPrimitive?.content ?: ""
            }.getOrDefault("")

            MangaEntry(
                manga = SManga.create().apply {
                    title         = stripParens(decodeHtml(item["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""))
                    url           = "$PREFIX_KIRYUU/manga/$slug"
                    thumbnail_url = featuredMedia
                    status = when {
                        classList.contains("status-ongoing")   -> SManga.ONGOING
                        classList.contains("status-completed") -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                },
                updatedAt = chapterMap[slug] ?: 0L,
            )
        }
    }

    // ─── LIST ────────────────────────────────────────────────────────────────

    fun fetchLatest(page: Int): List<MangaEntry> {
        val resp = get("$baseUrl/wp-json/wp/v2/manga?per_page=24&page=$page&orderby=modified&order=desc&_embed")
        return parseItems(resp.body.string())
    }

    fun fetchPopular(page: Int): List<MangaEntry> {
        val resp = get("$baseUrl/wp-json/wp/v2/manga?per_page=24&page=$page&orderby=meta_value_num&meta_key=manga_views&order=desc&_embed")
        return parseItems(resp.body.string())
    }

    fun fetchSearch(page: Int, query: String): List<MangaEntry> {
        val resp = get("$baseUrl/wp-json/wp/v2/manga?search=${query.encodeUrl()}&per_page=24&page=$page&_embed")
        return parseItems(resp.body.string())
    }

    // ─── DETAIL ──────────────────────────────────────────────────────────────

    fun fetchMangaDetails(slug: String): SManga {
        val resp = get("$baseUrl/wp-json/wp/v2/manga?slug[]=$slug&_embed")
        val arr  = Json.parseToJsonElement(resp.body.string()).jsonArray
        val item = arr.firstOrNull()?.jsonObject ?: throw Exception("Kiryuu: manga not found")

        val featuredMedia = runCatching {
            item["_embedded"]?.jsonObject
                ?.get("wp:featuredmedia")?.jsonArray
                ?.get(0)?.jsonObject
                ?.get("source_url")?.jsonPrimitive?.content ?: ""
        }.getOrDefault("")

        val termMap = runCatching {
            item["_embedded"]?.jsonObject
                ?.get("wp:term")?.jsonArray
                ?.flatMap { it.jsonArray }
                ?.groupBy { it.jsonObject["taxonomy"]?.jsonPrimitive?.content ?: "" }
        }.getOrNull()

        return SManga.create().apply {
            title         = stripParens(decodeHtml(item["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""))
            url           = "$PREFIX_KIRYUU/manga/$slug"
            thumbnail_url = featuredMedia
            description   = Jsoup.parse(item["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: "").text()
            author        = termMap?.get("manga_author")?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }
            genre         = termMap?.get("manga_genres")?.joinToString(", ") { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }

            val clsRaw     = item["class_list"]
            val classList: List<String> = when {
                runCatching { clsRaw?.jsonArray }.getOrNull() != null ->
                    clsRaw!!.jsonArray.map { it.jsonPrimitive.content }
                runCatching { clsRaw?.jsonObject }.getOrNull() != null ->
                    clsRaw!!.jsonObject.values.map { it.jsonPrimitive.content }
                else -> emptyList()
            }
            status = when {
                classList.contains("status-ongoing")   -> SManga.ONGOING
                classList.contains("status-completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ─── CHAPTERS ────────────────────────────────────────────────────────────

    fun fetchChapterList(slug: String): List<SChapter> {
        // Step 1: resolve numeric ID from slug
        val wpRes  = get("$baseUrl/wp-json/wp/v2/manga?slug[]=$slug&_fields=id")
        val wpArr  = Json.parseToJsonElement(wpRes.body.string()).jsonArray
        val numericId = wpArr.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw Exception("Kiryuu: manga ID not found for $slug")

        // Step 2: fetch chapter list via AJAX
        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?manga_id=$numericId&page=999&action=chapter_list"
        val html    = get(ajaxUrl).body.string()
        val doc     = Jsoup.parse(html)

        return doc.select("li.wp-manga-chapter").mapIndexed { idx, li ->
            val a    = li.selectFirst("a") ?: return@mapIndexed null
            val href = a.attr("href")
            val name = a.text().trim()
            val date = li.selectFirst("span.chapter-release-date i, span.chapter-release-date a")
                ?.attr("title")?.trim() ?: ""

            SChapter.create().apply {
                this.url    = "$PREFIX_KIRYUU/chapter/${href.trimEnd('/').substringAfterLast('/')}"
                this.name   = name
                date_upload = runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
                chapter_number = name.substringAfterLast("Chapter ").trim().toFloatOrNull() ?: idx.toFloat()
            }
        }.filterNotNull()
    }

    // ─── PAGES ───────────────────────────────────────────────────────────────

    fun fetchPageList(chapterSlug: String): List<Page> {
        // reconstruct full URL — chapter slug format: manga-slug-chapter-N
        val mangaSlug = chapterSlug.replace(Regex("-chapter-[\\d-]+$"), "")
        val url       = "$baseUrl/manga/$mangaSlug/$chapterSlug/"
        val html      = get(url).body.string()
        val doc       = Jsoup.parse(html)

        return doc.select("div.reading-content img").mapIndexed { idx, img ->
            val imgUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            Page(idx, imageUrl = imgUrl.trim())
        }
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
