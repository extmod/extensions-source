package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

class KomikV : HttpSource() {
    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    private fun parseMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.title = element.selectFirst("h2")?.text()?.trim() ?: ""
        manga.url = element.selectFirst("a")?.attr("href") ?: ""
        val img = element.selectFirst("img")?.absUrl("src") ?: ""
        manga.thumbnail_url = if (img.isNotEmpty()) "https://wsrv.nl/?w=150&h=110&url=${img.replace(".lol", ".li")}" else ""
        return manga
    }

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page&latest=1", headers)
    
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        val mangas = document.select("div.flex.overflow-hidden")
            .map { parseMangaFromElement(it) }
            .filter { it.url.isNotEmpty() && it.title.isNotEmpty() }
        
        // Cek page dari URL
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        // Hanya lanjut ke halaman berikutnya jika manga penuh (18) dan belum mencapai batas
        val hasNextPage = mangas.size == 18 && currentPage < 10
        
        return MangasPage(mangas, hasNextPage)
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/?page=$page", headers)
    
    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        val mangas = document.select("div.overflow-hidden")
            .map { parseMangaFromElement(it) }
            .filter { it.url.isNotEmpty() && it.title.isNotEmpty() }
        
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        // Sama seperti latest, cek apakah manga penuh
        val hasNextPage = mangas.size == 18 && currentPage < 10
        
        return MangasPage(mangas, hasNextPage)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (page > 1) {
            "$baseUrl/search/${query.trim()}/?page=$page"
        } else {
            "$baseUrl/search/${query.trim()}/"
        }
        return GET(url, headers)
    }
    
    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        val mangas = document.select("div.overflow-hidden")
            .map { parseMangaFromElement(it) }
            .filter { it.url.isNotEmpty() && it.title.isNotEmpty() }
        
        val url = response.request.url.toString()
        val currentPage = if (url.contains("?page=")) {
            url.substringAfter("?page=").substringBefore("&").toIntOrNull() ?: 1
        } else 1
        
        // Untuk search, mungkin jumlah per halaman berbeda, jadi cek juga manga.size
        val hasNextPage = mangas.size >= 15 && currentPage < 20
        
        return MangasPage(mangas, hasNextPage)
    }

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)
    
    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.string())
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
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)
    
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("a.group").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                name = element.selectFirst("p")?.text()?.trim() ?: ""
                
                val dateText = element.selectFirst("p.text-xs")?.text()?.trim() ?: ""
                date_upload = parseDate(dateText)
                
                val numberMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(name)
                chapter_number = numberMatch?.value?.replace(",", ".")?.toFloatOrNull() ?: 0f
            }
        }.sortedByDescending { it.chapter_number }
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
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)
    
    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("img.imgku").mapIndexedNotNull { index, img ->
            val imageUrl = img.absUrl("src")
            if (imageUrl.isNotEmpty()) {
                val resizedUrl = "https://images.weserv.nl/?w=300&q=70&url=$imageUrl"
                Page(index, "", resizedUrl)
            } else null
        }
    }

    override fun imageUrlParse(response: Response): String = ""
}