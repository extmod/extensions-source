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
            title = element.selectFirst("h2.font-bold, h2 a, h2, .title, .entry-title")?.text()?.trim().orEmpty()
            
            val link = element.selectFirst("a[href*='/comic/'], a[href*='/manga/'], a[href*='/series/']") 
                ?: element.selectFirst("a")
            val linkHref = link?.attr("href").orEmpty()
            
            url = if (linkHref.startsWith(baseUrl)) {
                linkHref.removePrefix(baseUrl)
            } else {
                linkHref
            }
            
            val img = element.selectFirst("img[data-src], img.lazyimage, img")
            thumbnail_url = when {
                img?.attr("data-src")?.isNotEmpty() == true -> img.absUrl("data-src")
                img?.attr("src")?.isNotEmpty() == true -> img.absUrl("src")
                else -> ""
            }
        }
    }

    override fun popularMangaNextPageSelector(): String =
        "a[rel=next], .pagination a[rel=next], .next, a:contains(Next), a:contains(›)"

    /**
     * STRATEGI 2: PATTERN MATCHING DETECTION ONLY
     * 
     * Pendekatan ini bekerja dengan mencari indikasi dalam source code HTML bahwa terdapat
     * functionality untuk memuat konten tambahan. Berbeda dari DOM detection yang mencari
     * elemen yang terlihat, pattern matching mencari "jejak" atau "petunjuk" dalam kode
     * yang menunjukkan adanya sistem pagination atau infinite scroll.
     * 
     * Konsep ini penting untuk situs modern yang menggunakan JavaScript frameworks seperti
     * Qwik.js, dimana functionality sering tersembunyi dalam script atau data attributes
     * yang tidak terlihat sebagai elemen HTML tradisional.
     */
    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        val allMangas = doc.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { 
                it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) 
            }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val nextPageNumber = currentPage + 1

        // Inisialisasi variabel untuk tracking hasil detection
        var hasNext = false
        val foundPatterns = mutableListOf<String>()

        // === PATTERN 1: URL-based Pattern Detection ===
        // Mencari referensi eksplisit ke nomor halaman selanjutnya dalam URL atau query parameters
        // Ini mengindikasikan bahwa situs mendukung pagination melalui URL parameters
        if (Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body)) {
            hasNext = true
            foundPatterns.add("URL query pattern: ?page=${nextPageNumber}")
        }
        
        // Mencari pattern dalam path URL yang menunjukkan struktur pagination
        if (Regex("""/page/${nextPageNumber}(/|["'])""").containsMatchIn(body)) {
            hasNext = true
            foundPatterns.add("URL path pattern: /page/${nextPageNumber}")
        }

        // === PATTERN 2: Load More Functionality Detection ===
        // Mencari keywords yang umum digunakan untuk infinite scroll atau pagination manual
        // "Load More" adalah pattern yang sangat umum untuk situs dengan infinite scroll
        if (body.contains("Load More", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("Load More button text detected")
        }
        
        // Mencari CSS class names yang umum digunakan untuk load more functionality
        if (body.contains("load-more", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("load-more CSS class detected")
        }

        // === PATTERN 3: Infinite Scroll Keywords Detection ===
        // Situs dengan infinite scroll sering memiliki keywords tertentu dalam source code
        // yang mengindikasikan functionality ini
        if (body.contains("infinite", ignoreCase = true) && body.contains("scroll", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("Infinite scroll keywords combination")
        }

        // === PATTERN 4: Qwik.js Specific Pattern Detection ===
        // Berdasarkan analisis sebelumnya, situs ini menggunakan Qwik.js dengan scroll-based loading
        // Kombinasi "qwik" dan "scroll" dalam source code mengindikasikan dynamic loading capability
        if (body.contains("qwik", ignoreCase = true) && body.contains("scroll", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("Qwik.js scroll-based loading pattern")
        }

        // === PATTERN 5: JavaScript Function Pattern Detection ===
        // Mencari function calls JavaScript yang mengindikasikan pagination functionality
        // Pattern ini mencari function dengan nama seperti loadPage() dengan parameter page number
        if (Regex("""loadPage\s*\(\s*${nextPageNumber}\s*\)""").containsMatchIn(body)) {
            hasNext = true
            foundPatterns.add("JavaScript loadPage(${nextPageNumber}) function call")
        }

        // Mencari function calls lain yang umum untuk pagination
        if (Regex("""loadNextPage\s*\(""").containsMatchIn(body)) {
            hasNext = true
            foundPatterns.add("JavaScript loadNextPage() function detected")
        }

        // === PATTERN 6: Data Attributes Pattern Detection ===
        // Modern web applications sering menggunakan data attributes untuk menyimpan
        // informasi pagination yang akan digunakan oleh JavaScript
        if (Regex("""data-page\s*=\s*["']${nextPageNumber}["']""").containsMatchIn(body)) {
            hasNext = true
            foundPatterns.add("HTML data-page=${nextPageNumber} attribute")
        }

        // Mencari data attributes lain yang mengindikasikan pagination capability
        if (body.contains("data-next-page", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("data-next-page attribute detected")
        }

        // === PATTERN 7: JSON Data Structure Detection ===
        // Situs modern sering embed JSON data dalam HTML yang berisi informasi pagination
        // Pattern ini mencari struktur JSON yang mengandung informasi page
        if (body.contains("\"page\":${nextPageNumber}") || body.contains("'page':${nextPageNumber}")) {
            hasNext = true
            foundPatterns.add("JSON pagination data: page:${nextPageNumber}")
        }

        // Mencari structure JSON yang mengindikasikan ada halaman selanjutnya
        if (body.contains("\"hasNextPage\":true", ignoreCase = true) || 
            body.contains("\"has_next\":true", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("JSON hasNextPage:true flag")
        }

        // === PATTERN 8: Framework-specific Patterns ===
        // Mencari pattern yang spesifik untuk framework atau library tertentu
        // yang umum digunakan untuk infinite scroll atau pagination
        
        // Pattern untuk intersection observer API (umum untuk infinite scroll)
        if (body.contains("IntersectionObserver", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("IntersectionObserver API detected (infinite scroll)")
        }

        // Pattern untuk event listeners yang terkait dengan scroll
        if (body.contains("addEventListener", ignoreCase = true) && 
            body.contains("scroll", ignoreCase = true)) {
            hasNext = true
            foundPatterns.add("Scroll event listener detected")
        }

        // === ENHANCED DEBUG LOGGING ===
        // Logging yang comprehensive untuk memahami behavior pattern matching
        println("=== KomikV PATTERN-ONLY DEBUG ANALYSIS ===")
        println("Current page: $currentPage")
        println("Looking for next page: $nextPageNumber")
        println("Manga successfully parsed: ${allMangas.size}")
        println("Total DOM elements found: ${doc.select(popularMangaSelector()).size}")
        
        // Analisis source code characteristics
        println("\n--- Source Code Analysis ---")
        println("HTML body size: ${body.length} characters")
        val scriptTags = doc.select("script").size
        println("JavaScript <script> tags found: $scriptTags")
        val totalLinks = doc.select("a").size
        println("Total <a> links found: $totalLinks")
        
        // Pattern detection results
        println("\n--- Pattern Detection Results ---")
        println("Patterns found: ${foundPatterns.size}")
        if (foundPatterns.isEmpty()) {
            println("No pagination patterns detected in source code")
        } else {
            foundPatterns.forEach { pattern ->
                println("  ✓ $pattern")
            }
        }
        
        println("\n--- Final Decision ---")
        println("Has next page: $hasNext")
        println("Decision basis: ${if (hasNext) "Pattern-based evidence found" else "No supporting patterns detected"}")
        
        // Additional diagnostic information
        if (!hasNext && allMangas.isNotEmpty()) {
            println("\n--- Diagnostic Info ---")
            println("WARNING: Found manga but no pagination patterns")
            println("This might indicate:")
            println("  - Site uses non-standard pagination method")
            println("  - Patterns need adjustment for this site")
            println("  - Site might be using pure client-side rendering")
        }
        
        println("==========================================")

        return MangasPage(allMangas, hasNext)
    }

    // === LATEST UPDATES SECTION ===
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetSeen()
        
        return if (page <= 1) {
            GET("$baseUrl/?latest=1", headers)
        } else {
            GET("$baseUrl/?page=$page&latest=1", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        val allMangas = doc.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val nextPageNumber = currentPage + 1

        // Simplified pattern matching untuk latest updates menggunakan patterns yang paling umum
        var hasNext = false
        val patterns = mutableListOf<String>()

        // Check for the most reliable patterns first
        if (Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body)) {
            hasNext = true
            patterns.add("URL pattern")
        }
        
        if (body.contains("Load More", ignoreCase = true) || 
            body.contains("load-more", ignoreCase = true)) {
            hasNext = true
            patterns.add("Load more indicators")
        }

        if (body.contains("qwik", ignoreCase = true) && body.contains("scroll", ignoreCase = true)) {
            hasNext = true
            patterns.add("Qwik scroll functionality")
        }

        println("=== KomikV LATEST PATTERN-ONLY DEBUG ===")
        println("Page: $currentPage, Manga: ${allMangas.size}")
        println("Patterns found: ${patterns.joinToString(", ")}")
        println("Has next page: $hasNext")
        println("============================================")
        
        return MangasPage(allMangas, hasNext)
    }

    // === SEARCH SECTION ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetSeen()
        
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            if (page <= 1) {
                GET("$baseUrl/comic-list/", headers)
            } else {
                GET("$baseUrl/comic-list/?page=$page", headers)
            }
        }
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // === MANGA DETAILS SECTION ===
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1, .entry-title, .post-title, .manga-title")?.text()?.trim().orEmpty()
            author = document.selectFirst(".author, .mt-4 .text-sm a, .manga-author")?.text()?.trim().orEmpty()
            
            val descElements = document.select(".description p, .summary p, .mt-4.w-full p")
            description = descElements.firstOrNull { it.text().length > 50 }?.text()?.trim().orEmpty()

            val genres = mutableListOf<String>()
            document.select(".genre a, .genres a, .tag a").forEach { genres.add(it.text().trim()) }
            val type = document.selectFirst(".type, .w-full.rounded-l-full.bg-red-800")?.text()?.trim()
            if (!type.isNullOrBlank()) genres.add(type)
            genre = genres.filter { it.isNotEmpty() }.distinct().joinToString(", ")

            val statusText = document.selectFirst(".status, .manga-status, .w-full.rounded-r-full, .bg-green-800")?.text()?.lowercase().orEmpty()
            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") || statusText.contains("tamat") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            val imgElement = document.selectFirst("img.cover, .manga-cover img, img[data-src], img")
            thumbnail_url = when {
                imgElement?.absUrl("data-src")?.isNotEmpty() == true -> imgElement.absUrl("data-src")
                imgElement?.absUrl("src")?.isNotEmpty() == true -> imgElement.absUrl("src")
                else -> ""
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
            this.name = name.ifEmpty { "Chapter ${link.attr("data-chapter") ?: "Unknown"}" }
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
                    null
                }
            }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .reversed()
    }

    // === PAGE LIST SECTION ===
    override fun pageListParse(document: Document): List<Page> {
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
            
            if (imageUrl.isNotEmpty() && 
                (imageUrl.contains(".jpg") || imageUrl.contains(".png") || 
                 imageUrl.contains(".webp") || imageUrl.contains(".jpeg"))) {
                pages.add(Page(index, "", imageUrl))
            }
        }
        
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img[data-src], img")?.let { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }.orEmpty()
    }
}