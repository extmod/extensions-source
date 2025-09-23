package eu.kanade.tachiyomi.extension.id.av

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Calendar

class AV : ParsedHttpSource() {
    override val name = "AV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page&latest=1", headers)
    override fun latestUpdatesSelector(): String = "div.flex.overflow-hidden"
    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.selectFirst("h2")?.text()?.trim() ?: ""
        manga.url = element.selectFirst("a")?.attr("href") ?: ""
        val img = element.selectFirst("img")?.absUrl("src") ?: ""
        manga.thumbnail_url = if (img.isNotEmpty()) "https://wsrv.nl/?w=150&h=110&url=${img.replace(".lol", ".li")}" else ""
        return manga
    }
    override fun latestUpdatesNextPageSelector(): String? = null

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/?page=$page", headers)
    override fun popularMangaSelector(): String = "div.overflow-hidden"
    override fun popularMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)
    override fun popularMangaNextPageSelector(): String? = null

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/search/$encodedQuery/?page=$page", headers)
    }
    override fun searchMangaSelector(): String = "div.overflow-hidden"
    override fun searchMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)
    override fun searchMangaNextPageSelector(): String? = null

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: ""
            author = document.selectFirst("a[href*=\"/tax/author/\"]")?.text()?.trim() ?: ""
            description = document.selectFirst("p")?.text()?.trim() ?: ""
            genre = document.select("a[href*=\"/tax/genre/\"]").joinToString(", ") { it.text().trim() }
            status = when {
                document.selectFirst(".bg-green-800")?.text()?.contains("on-going", ignoreCase = true) == true -> SManga.ONGOING
                document.selectFirst(".bg-green-800")?.text()?.contains("complete", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            val img = document.selectFirst("img")?.absUrl("src") ?: ""
            thumbnail_url = if (img.isNotEmpty()) "https://wsrv.nl/?w=150&h=110&url=${img.replace(".lol", ".li")}" else ""
        }
    }

    // Chapters
    override fun chapterListSelector() = "a.group"
    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.selectFirst("p")?.text()?.trim() ?: ""
        
        val dateText = element.selectFirst("p.text-xs")?.text()?.trim() ?: ""
        chapter.date_upload = parseDate(dateText)
        
        val numberMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(chapter.name)
        chapter.chapter_number = numberMatch?.value?.replace(",", ".")?.toFloatOrNull() ?: 0f
        return chapter
    }

    private fun parseDate(date: String): Long {
        val regex = Regex("""(\d+)\s*(mnt|jam|hari|bln|minggu)""")
        val match = regex.find(date.lowercase()) ?: return 0L
        val value = match.groupValues[1].toIntOrNull() ?: 1
        val unit = match.groupValues[2]
        
        val cal = Calendar.getInstance()
        when (unit) {
            "mnt" -> cal.add(Calendar.MINUTE, -value)
            "jam" -> cal.add(Calendar.HOUR_OF_DAY, -value)
            "hari" -> cal.add(Calendar.DATE, -value)
            "minggu" -> cal.add(Calendar.DATE, -value * 7)
            "bln" -> cal.add(Calendar.MONTH, -value)
        }
        return cal.timeInMillis
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.imgku").mapIndexedNotNull { index, img ->
            val imageUrl = img.absUrl("src")
            if (imageUrl.isNotEmpty()) {
                val resizedUrl = "https://images.weserv.nl/?w=300&q=70&url=$imageUrl"
                Page(index, "", resizedUrl)
            } else null
        }
    }

    override fun imageUrlParse(document: Document): String = ""
}