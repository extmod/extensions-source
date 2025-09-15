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
        val linkElement = element.selectFirst("a[href]")
        title = linkElement?.selectFirst("h2")?.text()?.trim() 
            ?: element.selectFirst("h2")?.text()?.trim()
            ?: linkElement?.text()?.trim().orEmpty()
        url = linkElement?.attr("href").orEmpty()
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.absUrl("data-src").takeIf { it.isNotEmpty() } ?: img.absUrl("src")
        }.orEmpty()
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/?page=$page", headers)
    override fun popularMangaSelector(): String = "div.grid > div:has(a[href*='/komik/'])"
    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun popularMangaNextPageSelector(): String? = null
    
    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        
        val hasNext = document.selectFirst("span.cursor-pointer:contains(Load More)") != null ||
                     document.selectFirst("[x-on\\:click*='loadMore'], [\\@click*='loadMore']") != null
        
        return MangasPage(mangas, hasNext)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page&latest=1", headers)
    override fun latestUpdatesSelector(): String = "div.grid > div:has(a[href*='/komik/'])"
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = null
    
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        
        val hasNext = document.selectFirst("span.cursor-pointer:contains(Load More)") != null ||
                     document.selectFirst("[x-on\\:click*='loadMore'], [\\@click*='loadMore']") != null
        
        return MangasPage(mangas, hasNext)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/$encodedQuery/?page=$page", headers)
    }
    override fun searchMangaSelector(): String = "div.grid > div:has(a[href*='/komik/'])"
    override fun searchMangaNextPageSelector(): String? = null
    
    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(searchMangaSelector())
            .map { searchMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
        
        val hasNext = document.selectFirst("span.cursor-pointer:contains(Load More)") != null ||
                     document.selectFirst("[x-on\\:click*='loadMore'], [\\@click*='loadMore']") != null
        
        return MangasPage(mangas, hasNext)
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        author = document.select("a[href*='/tax/author/']").joinToString(", ") { it.text().trim() }
        description = document.selectFirst("div.mt-4 p, .description p")?.text()?.trim().orEmpty()
        genre = document.select("a[href*='/tax/'], .genre a").joinToString(", ") { it.text().trim() }
        status = document.selectFirst(".status, .bg-green-800")?.text()?.let { statusText ->
            when {
                statusText.contains("ongoing", true) || statusText.contains("berlangsung", true) -> SManga.ONGOING
                statusText.contains("complete", true) || statusText.contains("tamat", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        } ?: SManga.UNKNOWN
        thumbnail_url = document.selectFirst("img.cover, img.thumbnail, .poster img")?.absUrl("src").orEmpty()
    }

    override fun chapterListSelector() = "a[href*='/chapter/'], div.chapter a"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.title, .chapter-title")?.text()?.trim() 
            ?: element.text().trim()
        
        val dateText = element.selectFirst("span.date, .chapter-date, span.text-xs")?.text()?.trim().orEmpty()
        date_upload = parseChapterDate(dateText)
        
        val numberMatch = Regex("""chapter\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""(\d+(?:[.,]\d+)?)""").find(name)
        chapter_number = numberMatch?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull() ?: 0f
    }

    private fun parseChapterDate(date: String): Long {
        if (date.isBlank()) return 0L
        
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
        } ?: try { 
            dateFormat.parse(date)?.time ?: 0L 
        } catch (_: Exception) { 
            0L 
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        return document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .sortedByDescending { it.chapter_number }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp'], .page-image img")
            .mapIndexed { index, img -> Page(index, "", img.absUrl("src")) }
            .filter { it.imageUrl?.isNotEmpty() == true }
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src").orEmpty()
    }
}