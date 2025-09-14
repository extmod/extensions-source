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

    // Menggunakan cloudflareClient untuk handle protection jika ada
    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .add("Referer", baseUrl)

    companion object {
        // Simple deduplication untuk avoid duplicate manga entries
        private val seenUrls = mutableSetOf<String>()

        fun resetSeen() {
            seenUrls.clear()
        }
    }

    // === POPULAR MANGA SECTION ===
    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        
        // Halaman pertama menggunakan base URL sesuai observasi bahwa komikav.net sama dengan komikav.net/?page=1
        return if (page <= 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/?page=$page", headers)
        }
    }

    override fun popularMangaSelector(): String =
        "div.grid div.flex.overflow-hidden, div.grid div.neu, .list-update_item, .bsx, div[class*='grid'] > div"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            // Extract title dari multiple possible selectors
            title = element.selectFirst("h2.font-bold, h2 a, h2, .title, .entry-title")?.text()?.trim().orEmpty()
            
            // Extract manga URL dengan priority pada pattern yang umum
            val link = element.selectFirst("a[href*='/comic/'], a[href*='/manga/'], a[href*='/series/']") 
                ?: element.selectFirst("a")
            val linkHref = link?.attr("href").orEmpty()
            
            // Normalize URL untuk consistency
            url = if (linkHref.startsWith(baseUrl)) {
                linkHref.removePrefix(baseUrl)
            } else {
                linkHref
            }
            
            // Extract thumbnail dengan priority pada lazy loading attributes
            val img = element.selectFirst("img[data-src], img.lazyimage, img")
            thumbnail_url = when {
                img?.attr("data-src")?.isNotEmpty() == true -> img.absUrl("data-src")
                img?.attr("src")?.isNotEmpty() == true -> img.absUrl("src")
                else -> ""
            }
        }
    }

    override fun popularMangaNextPageSelector(): String = ""

    /**
     * OPTIMIZED PATTERN MATCHING DETECTION
     * Berdasarkan testing, kita tahu bahwa pattern matching berhasil untuk situs ini.
     * Versi ini dioptimize dengan focus pada patterns yang paling reliable dan
     * menghilangkan complexity yang tidak diperlukan.
     */
    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        // Parse manga list dengan filtering yang robust
        val allMangas = doc.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { 
                it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) 
            }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val nextPageNumber = currentPage + 1

        // Streamlined pattern detection - focus pada patterns yang terbukti bekerja
        var hasNext = false

        // Pattern 1: URL-based detection (most reliable untuk server-side pagination)
        if (Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body)) {
            hasNext = true
        }

        // Pattern 2: Qwik.js specific pattern (terbukti effective untuk situs ini)
        if (body.contains("qwik", ignoreCase = true) && body.contains("scroll", ignoreCase = true)) {
            hasNext = true
        }

        // Pattern 3: Load more indicators (backup detection)
        if (body.contains("Load More", ignoreCase = true) || body.contains("load-more", ignoreCase = true)) {
            hasNext = true
        }

        // Optional debug logging (bisa dicomment jika tidak diperlukan)
        if (allMangas.isNotEmpty()) {
            println("KomikV Page $currentPage: ${allMangas.size} manga, hasNext=$hasNext")
        }

        return MangasPage(allMangas, hasNext)
    }

    // === LATEST UPDATES SECTION ===
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        
        // Latest updates bisa menggunakan parameter khusus atau endpoint berbeda
        return if (page <= 1) {
            GET("$baseUrl/?latest=1", headers)
        } else {
            GET("$baseUrl/?page=$page&latest=1", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = ""

    // Latest updates menggunakan logic yang sama dengan popular manga
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // === SEARCH SECTION ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        
        return if (query.isNotEmpty()) {
            // Search dengan query parameter
            GET("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            // Browse all manga jika tidak ada query
            if (page <= 1) {
                GET("$baseUrl/comic-list/", headers)
            } else {
                GET("$baseUrl/comic-list/?page=$page", headers)
            }
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = ""
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // === MANGA DETAILS SECTION ===
    /**
     * ENHANCED MANGA DETAILS PARSING
     * Diperbaiki untuk memastikan status parsing bekerja dengan baik.
     * Menggunakan multiple selectors dan fallback logic untuk robustness.
     */
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            // Title extraction dengan multiple fallbacks
            title = document.selectFirst("h1, .entry-title, .post-title, .manga-title, .comic-title")?.text()?.trim().orEmpty()

            // Author/Artist extraction - expanded selectors untuk better coverage
            author = document.selectFirst(
                ".author, .mt-4 .text-sm a, .manga-author, .artist, " +
                ".info-item:contains(Author) + .info-value, " +
                ".details .author, [class*='author']"
            )?.text()?.trim().orEmpty()

            // Description extraction dengan preference untuk paragraph yang substantial
            val descriptionSelectors = listOf(
                ".description p", ".summary p", ".mt-4.w-full p", 
                ".manga-summary p", ".synopsis p", ".content p"
            )
            
            for (selector in descriptionSelectors) {
                val desc = document.selectFirst(selector)?.text()?.trim()
                if (!desc.isNullOrBlank() && desc.length > 50) {
                    description = desc
                    break
                }
            }

            // Genre extraction dengan comprehensive approach
            val genres = mutableListOf<String>()
            
            // Extract dari berbagai possible genre containers
            document.select(
                ".genre a, .genres a, .tag a, .tags a, " +
                ".manga-genres a, .categories a, [class*='genre'] a"
            ).forEach { element ->
                val genreText = element.text().trim()
                if (genreText.isNotEmpty()) {
                    genres.add(genreText)
                }
            }
            
            // Tambahkan type/format sebagai genre jika ada
            val typeSelectors = listOf(
                ".type", ".manga-type", ".format", 
                ".w-full.rounded-l-full.bg-red-800", // specific untuk situs ini
                "[class*='type']"
            )
            
            for (selector in typeSelectors) {
                val typeText = document.selectFirst(selector)?.text()?.trim()
                if (!typeText.isNullOrBlank()) {
                    genres.add(typeText)
                    break
                }
            }
            
            genre = genres.filter { it.isNotEmpty() }.distinct().joinToString(", ")

            // ENHANCED STATUS PARSING - ini adalah bagian yang diperbaiki
            val statusSelectors = listOf(
                ".status", ".manga-status", ".publication-status",
                ".w-full.rounded-r-full", ".bg-green-800", // specific selectors untuk situs ini
                ".info-item:contains(Status) + .info-value",
                "[class*='status']", ".details .status"
            )
            
            var statusText = ""
            for (selector in statusSelectors) {
                val element = document.selectFirst(selector)
                if (element != null) {
                    statusText = element.text().lowercase().trim()
                    if (statusText.isNotEmpty()) break
                }
            }
            
            // Improved status mapping dengan lebih banyak variations
            status = when {
                statusText.contains("ongoing") || statusText.contains("on-going") || 
                statusText.contains("berlanjut") || statusText.contains("update") -> SManga.ONGOING
                
                statusText.contains("completed") || statusText.contains("complete") ||
                statusText.contains("tamat") || statusText.contains("selesai") || 
                statusText.contains("finished") -> SManga.COMPLETED
                
                statusText.contains("hiatus") || statusText.contains("pause") ||
                statusText.contains("stopped") -> SManga.ON_HIATUS
                
                statusText.contains("dropped") || statusText.contains("cancelled") ||
                statusText.contains("dibatalkan") -> SManga.CANCELLED
                
                else -> SManga.UNKNOWN
            }

            // Thumbnail extraction dengan comprehensive fallbacks
            val thumbnailSelectors = listOf(
                "img.cover", ".manga-cover img", ".thumbnail img",
                ".poster img", ".comic-cover img", "img[data-src]", "img"
            )
            
            for (selector in thumbnailSelectors) {
                val imgElement = document.selectFirst(selector)
                if (imgElement != null) {
                    val thumbUrl = when {
                        imgElement.absUrl("data-src").isNotEmpty() -> imgElement.absUrl("data-src")
                        imgElement.absUrl("src").isNotEmpty() -> imgElement.absUrl("src")
                        else -> ""
                    }
                    if (thumbUrl.isNotEmpty()) {
                        thumbnail_url = thumbUrl
                        break
                    }
                }
            }
        }
    }

    // === CHAPTER LIST SECTION ===
    override fun chapterListSelector(): String =
        ".chapter-list a, .chapters a, ul.chapters li a, .wp-manga-chapter a, a[href*='/chapter/'], .episode-list a"

    override fun chapterFromElement(element: Element): SChapter {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        val name = link.text()?.trim().orEmpty()
        val hrefRaw = link.attr("href").orEmpty()
        
        val url = if (hrefRaw.startsWith(baseUrl)) {
            hrefRaw.removePrefix(baseUrl)
        } else {
            hrefRaw
        }
        
        return SChapter.create().apply {
            this.name = if (name.isNotEmpty()) name else "Chapter ${link.attr("data-chapter") ?: "Unknown"}"
            this.url = url
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)
        
        return doc.select(chapterListSelector())
            .mapNotNull { element ->
                try {
                    chapterFromElement(element)
                } catch (e: Exception) {
                    // Skip chapters yang error parsing
                    null
                }
            }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .reversed() // Reverse untuk urutan chronological yang benar
    }

    // === PAGE LIST SECTION ===
    override fun pageListParse(document: Document): List<Page> {
        // Comprehensive selector untuk berbagai kemungkinan structure
        val images = document.select(
            "img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, " +
            ".page-break img, .entry-content img, .chapter-content img, " +
            "img[data-src*='.jpg'], img[data-src*='.png'], img[data-src*='.webp'], " +
            "img[src*='.jpg'], img[src*='.png'], img[src*='.webp']"
        )

        val pages = mutableListOf<Page>()
        images.forEachIndexed { index, img ->
            val imageUrl = when {
                img.absUrl("data-src").isNotEmpty() -> img.absUrl("data-src")
                img.absUrl("src").isNotEmpty() -> img.absUrl("src")
                else -> ""
            }
            
            // Filter untuk memastikan hanya image URLs yang valid
            if (imageUrl.isNotEmpty() && 
                (imageUrl.contains(".jpg") || imageUrl.contains(".png") || 
                 imageUrl.contains(".webp") || imageUrl.contains(".jpeg"))) {
                pages.add(Page(index, "", imageUrl))
            }
        }
        
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        // Fallback method untuk single image extraction
        return document.selectFirst("img[data-src], img")?.let { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }.orEmpty()
    }
}