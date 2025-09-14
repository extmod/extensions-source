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

    // === POPULAR MANGA SECTION ===
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/popular/?page=$page", headers)
    }

    override fun popularMangaSelector(): String = "div.grid div.overflow-hidden"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2")?.text()?.trim().orEmpty()
            url = element.selectFirst("a")?.attr("href").orEmpty()
            thumbnail_url = element.selectFirst("img")?.let { img ->
                img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            }.orEmpty()
        }
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        return MangasPage(mangas, true)
    }

    // === LATEST UPDATES SECTION ===
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page&latest=1", headers)
    }

    override fun latestUpdatesSelector(): String = "div.grid div.flex.overflow-hidden"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2 a")?.text()?.trim().orEmpty()
            url = element.selectFirst("a")?.attr("href").orEmpty()
            thumbnail_url = element.selectFirst("img")?.absUrl("data-src").orEmpty()
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        return MangasPage(mangas, true)
    }

    // === SEARCH SECTION ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page > 1) {
            "$baseUrl/search/$encodedQuery/?page=$page"
        } else {
            "$baseUrl/search/$encodedQuery/"
        }
        return GET(url, headers)
    }

    override fun searchMangaSelector(): String = "div.grid div.overflow-hidden"
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = null

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val mangas = document.select(searchMangaSelector())
            .map { searchMangaFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        return MangasPage(mangas, true)
    }

    // === MANGA DETAILS SECTION ===
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

    // === CHAPTER LIST SECTION ===
    override fun chapterListSelector() = "#chapter_list li"

    override fun chapterFromElement(element: Element): SChapter {
    val urlElement = element.selectFirst(".lchx a") ?: element.selectFirst("a") ?: throw Exception("No link element")
    val chapter = SChapter.create()
    chapter.setUrlWithoutDomain(urlElement.attr("href"))
    chapter.name = urlElement.text().trim()
    // Kirim Element, bukan String
    chapter.date_upload = element.selectFirst(".dt a")?.let { parseChapterDate(it) } ?: 0L
    return chapter
}

private fun parseChapterDate(element: Element): Long {
    // Cari teks tanggal dari element atau parent jika perlu
    val dateText = element.selectFirst(".chapter-date, .date, .time")?.text()?.trim()
        ?: element.parent()?.selectFirst(".chapter-date, .date, .time")?.text()?.trim()
        ?: element.text().split(" - ").getOrNull(1)?.trim()

    if (dateText.isNullOrEmpty()) return 0L

    return try {
        when {
            dateText.contains("hari", ignoreCase = true) && dateText.contains("lalu", ignoreCase = true) -> {
                val days = dateText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            }
            dateText.contains("jam", ignoreCase = true) && dateText.contains("lalu", ignoreCase = true) -> {
                val hours = dateText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
            }
            dateText.contains("menit", ignoreCase = true) && dateText.contains("lalu", ignoreCase = true) -> {
                val minutes = dateText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                System.currentTimeMillis() - (minutes * 60 * 1000L)
            }
            dateText.contains("detik", ignoreCase = true) && dateText.contains("lalu", ignoreCase = true) -> {
                System.currentTimeMillis()
            }
            else -> {
                // Kalau ada format absolut (mis. "12 Jan 2025"), bisa di-parse di sini.
                0L
            }
        }
    } catch (e: Exception) {
        0L
    }
}

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        return document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .reversed()
    }

    // === PAGE LIST SECTION ===
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']")
            .forEachIndexed { index, img ->
                val imageUrl = img.absUrl("src")
                if (imageUrl.isNotEmpty()) {
                    pages.add(Page(index, "", imageUrl))
                }
            }
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("src").orEmpty()
    }
}