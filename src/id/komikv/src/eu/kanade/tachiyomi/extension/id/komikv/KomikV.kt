package eu.kanade.tachiyomi.extension.id.komikv

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class KomikV : ParsedHttpSource() {

    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h2 a")?.text()?.trim() ?: element.text().trim()
        url = element.selectFirst("a[href]")?.attr("href").orEmpty()
        thumbnail_url = element.selectFirst("img")?.absUrl("data-src")?.takeIf { it.isNotEmpty() } 
            ?: element.selectFirst("img")?.absUrl("src").orEmpty()
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/?page=$page", headers)
    override fun popularMangaSelector(): String = "div.grid div.overflow-hidden"
    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun popularMangaNextPageSelector(): String = "a[rel=next]"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page&latest=1", headers)
    override fun latestUpdatesSelector(): String = "div.grid div.flex.overflow-hidden"
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = "a[rel=next]"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/$encodedQuery/?page=$page", headers)
    }
    override fun searchMangaSelector(): String = "div.grid div.overflow-hidden"
    override fun searchMangaNextPageSelector(): String = "a[rel=next]"

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
        author = document.select("a[href*=\"/tax/author/\"]").joinToString(", ") { it.text().trim() }
        description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()
        genre = document.select(".mt-4.w-full a.text-md.mb-1").joinToString(", ") { it.text().trim() }
        status = when {
            document.selectFirst(".bg-green-800")?.text()?.contains("on-going", true) == true -> SManga.ONGOING
            document.selectFirst(".bg-green-800")?.text()?.contains("complete", true) == true -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = document.selectFirst("img.neu-active")?.absUrl("src").orEmpty()
    }

    override fun chapterListSelector() = "div.mt-4.flex.max-h-96.flex-col > a"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("p")?.text()?.trim() ?: element.text().trim()
        
        val dateText = element.selectFirst("p.text-xs")?.text()?.trim().orEmpty()
        date_upload = parseChapterDate(dateText)
        
        val numberMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(name)
        chapter_number = numberMatch?.value?.replace(",", ".")?.toFloatOrNull() ?: 0f
    }

    private fun parseChapterDate(date: String): Long {
        val match = Regex("""(\d+)\s*(detik|menit|jam|hari|minggu|bulan|tahun)""", RegexOption.IGNORE_CASE)
            .find(date.lowercase())
        
        return match?.let {
            val value = it.groupValues[1].toIntOrNull() ?: 1
            val cal = Calendar.getInstance()
            when (it.groupValues[2]) {
                "detik" -> cal.add(Calendar.SECOND, -value)
                "menit" -> cal.add(Calendar.MINUTE, -value)
                "jam" -> cal.add(Calendar.HOUR_OF_DAY, -value)
                "hari" -> cal.add(Calendar.DATE, -value)
                "minggu" -> cal.add(Calendar.DATE, -value * 7)
                "bulan" -> cal.add(Calendar.MONTH, -value)
                "tahun" -> cal.add(Calendar.YEAR, -value)
            }
            cal.timeInMillis
        } ?: try { dateFormat.parse(date)?.time ?: 0L } catch (_: Exception) { 0L }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        return document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .sortedByDescending { it.chapter_number }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']")
            .mapIndexed { index, img -> Page(index, "", img.absUrl("src")) }
            .filter { it.imageUrl?.isNotEmpty() == true }
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src").orEmpty()
    }
}