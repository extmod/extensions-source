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

    companion object {
        private val seenUrls = mutableSetOf<String>()

        fun resetSeen() {
            seenUrls.clear()
        }
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

    // ---------------------------
    // Search
    // ---------------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page > 1) {
            "$baseUrl/?s=$encodedQuery&page=$page"
        } else {
            "$baseUrl/?s=$encodedQuery"
        }

        return GET(url, headers)
    }

    override fun searchMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.code == 404) {
            return MangasPage(emptyList(), false)
        }

        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(searchMangaSelector())
            .map { searchMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        val title = element.selectFirst("h2 a")?.text()?.trim() ?: element.text().trim()
        val url = element.selectFirst("a[href]")?.attr("href").orEmpty()
        val thumb = element.selectFirst("img")?.let { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }.orEmpty()

        manga.title = title
        manga.url = url
        manga.thumbnail_url = thumb

        return manga
    }

    // ---------------------------
    // Popular
    // ---------------------------
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/popular/?page=$page", headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        return MangasPage(mangas, true)
    }

    // ---------------------------
    // Latest
    // ---------------------------
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun latestUpdatesSelector(): String = "div.grid div.flex.overflow-hidden"

    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        return MangasPage(mangas, true)
    }

    // ---------------------------
    // Manga details / chapters / pages (tetap seperti sebelumnya)
    // ---------------------------
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
            author = document.select("a[href*=\"/tax/author/\"]").joinToString(", ") { it.text().trim() }
            description = document.selectFirst(".mt-4.w-full p")?.text()?.trim().orEmpty()
            genre = (document.select(".mt-4.w-full a.text-md.mb-1").map { it.text().trim() } +
                    document.select(".bg-red-800").map { it.text().trim() })
                .joinToString(", ")
            status = parseStatus(document.selectFirst(".bg-green-800")?.text().orEmpty())
            thumbnail_url = document.selectFirst("img.neu-active")?.absUrl("src").orEmpty()
        }
    }

    private fun parseStatus(statusString: String): Int = when {
        statusString.contains("on-going", ignoreCase = true) -> SManga.ONGOING
        statusString.contains("complete", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }
}