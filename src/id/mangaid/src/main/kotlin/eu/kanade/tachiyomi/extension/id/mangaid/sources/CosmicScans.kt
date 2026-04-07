package eu.kanade.tachiyomi.extension.id.mangaid.sources

import eu.kanade.tachiyomi.extension.id.mangaid.MangaEntry
import eu.kanade.tachiyomi.extension.id.mangaid.PREFIX_COSMICSCANS
import eu.kanade.tachiyomi.extension.id.mangaid.UA
import eu.kanade.tachiyomi.extension.id.mangaid.stripParens
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class CosmicScans(private val client: OkHttpClient) {

    private val baseUrl = "https://lc5.cosmicscans.asia"

    // Cookie dari JS kamu — bisa perlu diupdate berkala
    private val cookie = "wordpress_test_cookie=WP%20Cookie%20check; " +
        "wordpress_logged_in_47f752bf6e3237e7dccb3849919442de=darwin123%7C1775736689%7CV5nH5RL2I8bjDDv8zehOu9Ij6JoTl7XZ8iP9og6DhNy%7C011361c57bf39d40cebed3d32e09d8005761d35f00d830b5211cdaf34135a8b8; " +
        "wpdiscuz_nonce_47f752bf6e3237e7dccb3849919442de=3d39fd1478"

    private val headers = Headers.Builder()
        .add("User-Agent", UA)
        .add("Accept", "*/*")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Cache-Control", "no-cache")
        .add("Pragma", "no-cache")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Cookie", cookie)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private fun get(url: String) =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute()

    private fun parseListPage(html: String): List<MangaEntry> {
        val doc = Jsoup.parse(html)
        return doc.select("div.bsx").mapNotNull { el ->
            val a     = el.selectFirst("a") ?: return@mapNotNull null
            val href  = a.attr("href")
            val slug  = href.trimEnd('/').substringAfterLast('/')
            val title = stripParens(el.selectFirst("div.tt")?.text() ?: return@mapNotNull null)
            if (title.isEmpty()) return@mapNotNull null

            val imgEl = el.selectFirst("img")
            val cover = imgEl?.attr("abs:data-src")?.ifEmpty { imgEl.attr("abs:src") } ?: ""

            val statusText = el.selectFirst("span.status")?.text()?.lowercase() ?: ""
            val timeAttr   = el.selectFirst("time")?.attr("datetime") ?: ""

            MangaEntry(
                manga = SManga.create().apply {
                    this.title         = title
                    this.url           = "$PREFIX_COSMICSCANS/manga/$slug"
                    this.thumbnail_url = cover
                    this.status = when {
                        statusText.contains("ongoing")   -> SManga.ONGOING
                        statusText.contains("completed") -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                },
                updatedAt = runCatching {
                    if (timeAttr.isNotEmpty()) dateFormat.parse(timeAttr)?.time ?: 0L else 0L
                }.getOrDefault(0L),
            )
        }
    }

    // ─── LIST ────────────────────────────────────────────────────────────────

    fun fetchLatest(page: Int): List<MangaEntry> {
        val html = get("$baseUrl/manga/?order=update&page=$page").body.string()
        return parseListPage(html)
    }

    fun fetchPopular(page: Int): List<MangaEntry> {
        val html = get("$baseUrl/manga/?order=popular&page=$page").body.string()
        return parseListPage(html)
    }

    fun fetchSearch(page: Int, query: String): List<MangaEntry> {
        val html = get("$baseUrl/?s=${query.encodeUrl()}&post_type=wp-manga").body.string()
        return parseListPage(html)
    }

    // ─── DETAIL ──────────────────────────────────────────────────────────────

    fun fetchMangaDetails(slug: String): SManga {
        val html = get("$baseUrl/manga/$slug/").body.string()
        val doc  = Jsoup.parse(html)

        return SManga.create().apply {
            title         = stripParens(doc.selectFirst("h1.entry-title")?.text() ?: "")
            url           = "$PREFIX_COSMICSCANS/manga/$slug"
            thumbnail_url = doc.selectFirst("div.thumb img")?.attr("abs:src") ?: ""
            description   = doc.selectFirst("div.entry-content p")?.text() ?: ""

            author = doc.select("div.tsinfo.bixbox div.imptdt")
                .firstOrNull { it.selectFirst("i")?.text()?.contains("Author", ignoreCase = true) == true }
                ?.selectFirst("a")?.text() ?: ""

            genre = doc.select("div.wd-full span.mgen a").joinToString(", ") { it.text() }

            val statusText = doc.select("div.tsinfo.bixbox div.imptdt")
                .firstOrNull { it.selectFirst("i")?.text()?.contains("Status", ignoreCase = true) == true }
                ?.selectFirst("a")?.text()?.lowercase() ?: ""

            status = when {
                statusText.contains("ongoing")   -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ─── CHAPTERS ────────────────────────────────────────────────────────────

    fun fetchChapterList(slug: String): List<SChapter> {
        val html = get("$baseUrl/manga/$slug/").body.string()
        val doc  = Jsoup.parse(html)

        return doc.select("div#chapterlist ul li").map { li ->
            val a        = li.selectFirst("a") ?: return@map null
            val href     = a.attr("href")
            val chSlug   = href.trimEnd('/').substringAfterLast('/')
            val name     = li.selectFirst("span.chapternum")?.text()?.trim() ?: a.text().trim()
            val dateText = li.selectFirst("span.chapterdate")?.text()?.trim() ?: ""

            SChapter.create().apply {
                this.url    = "$PREFIX_COSMICSCANS/chapter/$chSlug"
                this.name   = name
                date_upload = runCatching {
                    SimpleDateFormat("MMMM dd, yyyy", Locale.US).parse(dateText)?.time ?: 0L
                }.getOrDefault(0L)
                chapter_number = name.substringAfterLast("Chapter ").trim().toFloatOrNull() ?: -1f
            }
        }.filterNotNull()
    }

    // ─── PAGES ───────────────────────────────────────────────────────────────

    fun fetchPageList(chapterSlug: String): List<Page> {
        // resolve full chapter URL — slug might be full path or just slug
        val url  = if (chapterSlug.startsWith("http")) chapterSlug
                   else "$baseUrl/$chapterSlug/"
        val html = get(url).body.string()
        val doc  = Jsoup.parse(html)

        return doc.select("div#readerarea img").mapIndexed { idx, img ->
            val imgUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            Page(idx, imageUrl = imgUrl.trim())
        }
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")
}
