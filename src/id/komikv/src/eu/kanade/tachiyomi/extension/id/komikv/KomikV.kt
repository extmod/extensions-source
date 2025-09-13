package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class KomikV : ParsedHttpSource() {

    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)
        .add("Accept-Encoding", "gzip, deflate, br")

    companion object {
        private val seenUrls = mutableSetOf<String>()

        fun resetSeen() {
            seenUrls.clear()
        }
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        
        val now = System.currentTimeMillis()
        val cleanStr = dateStr.lowercase().trim()
        
        return when {
            cleanStr.contains("mnt lalu") || cleanStr.contains("menit lalu") -> {
                val minutes = cleanStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                now - TimeUnit.MINUTES.toMillis(minutes.toLong())
            }
            cleanStr.contains("jam lalu") -> {
                val hours = cleanStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                now - TimeUnit.HOURS.toMillis(hours.toLong())
            }
            cleanStr.contains("hari lalu") -> {
                val days = cleanStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                now - TimeUnit.DAYS.toMillis(days.toLong())
            }
            cleanStr.contains("minggu lalu") -> {
                val weeks = cleanStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                now - TimeUnit.DAYS.toMillis(weeks * 7L)
            }
            cleanStr.contains("bulan lalu") -> {
                val months = cleanStr.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                now - TimeUnit.DAYS.toMillis(months * 30L)
            }
            else -> {
                try {
                    val formats = arrayOf(
                        "yyyy-MM-dd'T'HH:mm:ss",
                        "yyyy-MM-dd HH:mm:ss",
                        "dd/MM/yyyy",
                        "dd-MM-yyyy"
                    )
                    for (format in formats) {
                        try {
                            val df = SimpleDateFormat(format, Locale.getDefault())
                            return df.parse(dateStr)?.time ?: 0L
                        } catch (_: Exception) { continue }
                    }
                    0L
                } catch (_: Exception) { 0L }
            }
        }
    }

    // ----------------------
    // Popular Manga
    // ----------------------
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val elements = document.select(popularMangaSelector())
        
        val mangas = elements.mapNotNull { element ->
            try {
                popularMangaFromElement(element)
            } catch (_: Exception) { null }
        }.filter { manga ->
            val key = manga.url.ifEmpty { manga.title }
            if (seenUrls.contains(key)) {
                false
            } else {
                seenUrls.add(key)
                true
            }
        }

        val hasNext = document.select("a:contains(Next), a:contains(Selanjutnya), a[href*='page=']:contains(›)").isNotEmpty()
        return MangasPage(mangas, hasNext && mangas.isNotEmpty())
    }

    override fun popularMangaSelector() = "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.selectFirst("h2.font-bold, h2 a, h2")?.text()?.trim().orEmpty()
            val link = element.selectFirst("a[href*='/comic/'], a[href*='/manga/']") ?: element.selectFirst("a")
            val linkHref = link?.attr("href").orEmpty()
            url = if (linkHref.startsWith(baseUrl)) linkHref.removePrefix(baseUrl) else linkHref
            val img = element.selectFirst("img[data-src], img.lazyimage, img")
            thumbnail_url = img?.attr("data-src")?.ifEmpty { img.attr("src") }.orEmpty()
        }
    }

    override fun popularMangaNextPageSelector() = "a:contains(Next), a:contains(Selanjutnya), a[href*='page=']:contains(›)"

    // ----------------------
    // Latest Updates
    // ----------------------
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        return GET("$baseUrl/latest/?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ----------------------
    // Search
    // ----------------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            GET("$baseUrl/comic-list/?page=$page", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)
    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ----------------------
    // Manga Details
    // ----------------------
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        // Title
        title = document.selectFirst("h1.text-xl, h1.font-tangle, h1, .entry-title")?.text()?.trim() ?: ""

        // Author - look for author links
        author = document.select("a[href*='/tax/author/'], a[href*='/author/']")
            .joinToString(", ") { it.text().trim() }
            .ifEmpty { 
                document.selectFirst(".author, [class*='author']")?.text()?.trim() ?: ""
            }

        // Artist (same as author for most manga)
        artist = author

        // Description
        description = document.selectFirst("p:contains(Apakah kau memiliki), .synopsis, .summary, [itemprop='description']")?.text()?.trim() 
            ?: document.selectFirst("p")?.text()?.trim() ?: ""

        // Genre
        genre = document.select("a[href*='/tax/genre/'], a[href*='/genre/']")
            .joinToString(", ") { it.text().trim() }

        // Status
        val statusText = document.select("div:contains(on-going), div:contains(ongoing), div:contains(completed), div:contains(tamat)")
            .firstOrNull()?.text()?.lowercase() ?: ""
        
        status = when {
            statusText.contains("on-going") || statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("completed") || statusText.contains("tamat") || statusText.contains("selesai") -> SManga.COMPLETED
            statusText.contains("hiatus") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        // Thumbnail - avoid user profile images
        thumbnail_url = document.select("img")
            .firstOrNull { img ->
                val src = img.attr("src").ifEmpty { img.attr("data-src") }
                src.isNotEmpty() && !src.contains("avatar", true) && !src.contains("profile", true) &&
                (src.contains("poster", true) || src.contains("cover", true) || src.contains("manga", true) || 
                 src.contains("komik", true) || src.contains("thumb", true))
            }?.let { img ->
                img.attr("src").ifEmpty { img.attr("data-src") }
            } ?: ""
    }

    // ----------------------
    // Chapter List
    // ----------------------
    override fun chapterListSelector() = "a[href*='/chapter/'], a[href*='/manga/'][href*='/chapter-']"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        name = element.text().trim().ifEmpty { "Chapter" }
        
        url = element.attr("href").let { href ->
            when {
                href.startsWith(baseUrl) -> href.removePrefix(baseUrl)
                href.startsWith("/") -> href
                else -> "/manga/$href"
            }
        }

        // Date parsing
        val dateElement = element.parent()?.selectFirst(".date, .time, p:contains(mnt lalu), p:contains(jam lalu), p:contains(hari lalu)")
        date_upload = parseRelativeDate(dateElement?.text())
    }

    // ----------------------
    // Page List (Images)
    // ----------------------
    override fun pageListParse(document: Document): List<Page> {
        // For reading pages, images are usually loaded dynamically
        // We need to make another request to get the actual images
        
        val currentUrl = document.location()
        
        // Try to find images in common selectors
        val images = document.select(
            ".imgku, " +
            "img.lazyimage, " +
            ".reader-area img, " +
            "#chapter img, " +
            ".main-reading-area img, " +
            ".page-break img, " +
            ".entry-content img, " +
            "img[src*='.jpg'], " +
            "img[src*='.png'], " +
            "img[src*='.webp'], " +
            "img[data-src*='.jpg'], " +
            "img[data-src*='.png'], " +
            "img[data-src*='.webp']"
        )

        val pages = mutableListOf<Page>()
        
        images.forEachIndexed { index, img ->
            val imageUrl = img.attr("data-src").ifEmpty { 
                img.attr("src").ifEmpty { 
                    img.attr("data-lazy-src") 
                }
            }
            
            if (imageUrl.isNotEmpty() && (imageUrl.contains(".jpg") || imageUrl.contains(".png") || imageUrl.contains(".webp"))) {
                val fullUrl = when {
                    imageUrl.startsWith("http") -> imageUrl
                    imageUrl.startsWith("//") -> "https:$imageUrl"
                    imageUrl.startsWith("/") -> "$baseUrl$imageUrl"
                    else -> "$baseUrl/$imageUrl"
                }
                pages.add(Page(index, "", fullUrl))
            }
        }

        // If no images found, try to get them from JavaScript or make API call
        if (pages.isEmpty()) {
            // Try to extract chapter ID from URL and make API request
            val chapterMatch = Regex("""/chapter-(\d+)/""").find(currentUrl)
            if (chapterMatch != null) {
                val chapterId = chapterMatch.groupValues[1]
                // This would require additional API call - for now return empty
                // In real implementation, you'd make GET request to chapter API endpoint
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        } ?: ""
    }
}