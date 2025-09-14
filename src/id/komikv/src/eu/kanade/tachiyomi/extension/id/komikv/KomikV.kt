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
        // Menyimpan kombinasi page+type untuk tracking sudah fetch page mana saja
        private val fetchedPages = mutableSetOf<String>()
        
        // Menyimpan URL yang sudah ditampilkan untuk dedupe
        private val seenUrls = mutableSetOf<String>()

        fun resetTracking() {
            fetchedPages.clear()
            seenUrls.clear()
        }
    }

    // === POPULAR MANGA SECTION ===
    override fun popularMangaRequest(page: Int): Request {
        // Reset tracking hanya untuk halaman pertama
        if (page <= 1) resetTracking()
        
        // Untuk halaman pertama, gunakan base URL (tanpa query parameter)
        // Karena komikav.net sama dengan komikav.net/?page=1
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
            // Ambil judul dari berbagai kemungkinan selector
            title = element.selectFirst("h2.font-bold, h2 a, h2, .title, .entry-title")?.text()?.trim().orEmpty()
            
            // Ambil link manga dengan berbagai pattern
            val link = element.selectFirst("a[href*='/comic/'], a[href*='/manga/'], a[href*='/series/']") 
                ?: element.selectFirst("a")
            val linkHref = link?.attr("href").orEmpty()
            
            // Normalisasi URL - hapus baseUrl jika ada untuk konsistensi
            url = if (linkHref.startsWith(baseUrl)) {
                linkHref.removePrefix(baseUrl)
            } else if (linkHref.startsWith("http")) {
                linkHref // URL absolut dari domain lain, keep as-is
            } else {
                linkHref // URL relatif, keep as-is
            }
            
            // Ambil thumbnail dengan prioritas data-src (lazy loading)
            val img = element.selectFirst("img[data-src], img.lazyimage, img")
            thumbnail_url = when {
                img?.attr("data-src")?.isNotEmpty() == true -> img.absUrl("data-src")
                img?.attr("src")?.isNotEmpty() == true -> img.absUrl("src")
                else -> ""
            }
        }
    }

    override fun popularMangaNextPageSelector(): String =
        "a[rel=next], .pagination a[rel=next], .next, a:contains(Next), a:contains(›), .load-more, [data-next-page]"

    /**
     * Override popularMangaParse untuk menangani infinite scroll dengan strategi hybrid:
     * 1. Coba deteksi DOM elements untuk next page (traditional pagination)
     * 2. Gunakan pattern matching untuk mencari indikasi ada page selanjutnya
     * 3. Fallback: assume ada next page jika current page berhasil load manga (untuk infinite scroll)
     */
    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        // Parse manga list dan filter duplicates
        val allMangas = doc.select(popularMangaSelector())
            .map { popularMangaFromElement(it) }
            .filter { 
                // Filter manga yang valid dan belum pernah ditampilkan
                it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) 
            }

        // Ekstrak current page number dari request URL
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val nextPageNumber = currentPage + 1
        
        // Track bahwa kita sudah fetch page ini
        val pageKey = "popular-$currentPage"
        fetchedPages.add(pageKey)

        // Strategi 1: Traditional DOM-based detection
        val hasNextByDom = doc.select(popularMangaNextPageSelector()).isNotEmpty()

        // Strategi 2: Pattern matching dalam HTML body untuk infinite scroll
        // Cari pattern yang menunjukkan ada page selanjutnya
        val hasNextByPattern = when {
            // Pattern 1: Explicit reference to next page number
            Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body) -> true
            Regex("""/page/${nextPageNumber}(/|["'])""").containsMatchIn(body) -> true
            
            // Pattern 2: JavaScript load more functionality indicators
            body.contains("Load More", ignoreCase = true) -> true
            body.contains("load-more", ignoreCase = true) -> true
            body.contains("infinite", ignoreCase = true) -> true
            
            // Pattern 3: Qwik-specific patterns for dynamic loading
            body.contains("qwik", ignoreCase = true) && body.contains("scroll", ignoreCase = true) -> true
            
            else -> false
        }

        // Strategi 3: Content-based heuristic untuk infinite scroll
        // Jika kita dapat manga dan ini bukan page yang terlalu tinggi, assume ada next
        val hasNextByContent = when {
            allMangas.isEmpty() -> false // Tidak ada manga = tidak ada next page
            currentPage >= 50 -> false // Safety limit untuk prevent infinite loop
            allMangas.size < 5 -> false // Terlalu sedikit manga, mungkin sudah habis
            else -> true // Default assume ada next untuk infinite scroll
        }

        // Kombinasi semua strategi
        val hasNext = hasNextByDom || hasNextByPattern || hasNextByContent

        // Logging untuk debugging (akan muncul di log Tachiyomi jika debug enabled)
        if (allMangas.isNotEmpty()) {
            println("KomikV Popular Page $currentPage: ${allMangas.size} manga, hasNext=$hasNext (DOM=$hasNextByDom, Pattern=$hasNextByPattern, Content=$hasNextByContent)")
        }

        return MangasPage(allMangas, hasNext)
    }

    // === LATEST UPDATES SECTION ===
    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) resetTracking()
        
        // Untuk latest, kita bisa menggunakan parameter khusus atau endpoint berbeda
        // Sesuaikan dengan struktur situs yang sebenarnya
        return if (page <= 1) {
            GET("$baseUrl/?latest=1", headers)
        } else {
            GET("$baseUrl/?page=$page&latest=1", headers)
        }
    }

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    /**
     * Latest updates menggunakan logic yang sama dengan popular manga
     * karena struktur DOM dan infinite scroll behavior kemungkinan sama
     */
    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val doc = Jsoup.parse(body, baseUrl)

        val allMangas = doc.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() && seenUrls.add(it.url) }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val nextPageNumber = currentPage + 1
        val pageKey = "latest-$currentPage"
        fetchedPages.add(pageKey)

        // Same detection strategies as popular manga
        val hasNextByDom = doc.select(latestUpdatesNextPageSelector()).isNotEmpty()
        
        val hasNextByPattern = when {
            Regex("""[?&]page=${nextPageNumber}\b""").containsMatchIn(body) -> true
            body.contains("Load More", ignoreCase = true) -> true
            body.contains("qwik", ignoreCase = true) && body.contains("scroll", ignoreCase = true) -> true
            else -> false
        }

        val hasNextByContent = when {
            allMangas.isEmpty() -> false
            currentPage >= 50 -> false
            allMangas.size < 5 -> false
            else -> true
        }

        val hasNext = hasNextByDom || hasNextByPattern || hasNextByContent

        if (allMangas.isNotEmpty()) {
            println("KomikV Latest Page $currentPage: ${allMangas.size} manga, hasNext=$hasNext")
        }

        return MangasPage(allMangas, hasNext)
    }

    // === SEARCH SECTION ===
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page <= 1) resetTracking()
        
        return if (query.isNotEmpty()) {
            // Jika ada query, gunakan search endpoint
            GET("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        } else {
            // Jika tidak ada query, tampilkan daftar semua manga
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
    
    // Search juga menggunakan logic parsing yang sama
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // === MANGA DETAILS SECTION ===
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            // Ambil judul dari berbagai kemungkinan lokasi
            title = document.selectFirst("h1, .entry-title, .post-title, .manga-title")?.text()?.trim().orEmpty()

            // Author/Artist - cari di berbagai lokasi yang mungkin
            author = document.selectFirst(".author, .mt-4 .text-sm a, .manga-author, .artist")?.text()?.trim().orEmpty()

            // Deskripsi - ambil dari paragraph pertama yang panjang
            val descElements = document.select(".description p, .summary p, .mt-4.w-full p, .manga-summary p")
            description = descElements.firstOrNull { it.text().length > 50 }?.text()?.trim().orEmpty()

            // Genre - kumpulkan dari berbagai sumber
            val genres = mutableListOf<String>()
            
            // Ambil dari tag genre
            document.select(".genre a, .genres a, .tag a, .manga-genres a")
                .forEach { genres.add(it.text().trim()) }
            
            // Tambahkan tipe komik jika ada
            val type = document.selectFirst(".type, .manga-type, .w-full.rounded-l-full.bg-red-800")?.text()?.trim()
            if (!type.isNullOrBlank()) genres.add(type)
            
            genre = genres.filter { it.isNotEmpty() }.distinct().joinToString(", ")

            // Status - cari dari berbagai kemungkinan lokasi
            val statusText = document.selectFirst(
                ".status, .manga-status, .w-full.rounded-r-full, .bg-green-800, .publication-status"
            )?.text()?.lowercase().orEmpty()
            
            status = when {
                statusText.contains("ongoing") || statusText.contains("on-going") || 
                statusText.contains("berlanjut") -> SManga.ONGOING
                statusText.contains("completed") || statusText.contains("tamat") || 
                statusText.contains("selesai") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                statusText.contains("dropped") || statusText.contains("dibatalkan") -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            // Thumbnail - prioritaskan data-src untuk lazy loading
            val imgElement = document.selectFirst("img.cover, .manga-cover img, .thumbnail img, img[data-src], img")
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
        
        // Normalisasi URL chapter
        val url = if (hrefRaw.startsWith(baseUrl)) {
            hrefRaw.removePrefix(baseUrl)
        } else {
            hrefRaw
        }
        
        return SChapter.create().apply {
            this.name = name.ifEmpty { "Chapter ${link.attr("data-chapter") ?: "Unknown"}" }
            this.url = url
            
            // Tambahkan tanggal jika tersedia (optional)
            val dateElement = element.selectFirst(".chapter-date, .date, time")
            if (dateElement != null) {
                // Implementasi parsing tanggal bisa ditambahkan di sini jika diperlukan
                // date_upload = parseChapterDate(dateElement.text())
            }
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
                    // Skip chapter yang error parsing
                    null
                }
            }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }
            .reversed() // Biasanya chapter list perlu dibalik untuk urutan yang benar
    }

    // === PAGE LIST SECTION ===
    override fun pageListParse(document: Document): List<Page> {
        // Selector yang lebih comprehensive untuk berbagai kemungkinan struktur
        val images = document.select(
            "img.lazyimage, .reader-area img, #chapter img, .main-reading-area img, " +
            ".page-break img, .entry-content img, .chapter-content img, " +
            "img[data-src*='.jpg'], img[data-src*='.png'], img[data-src*='.webp'], " +
            "img[src*='.jpg'], img[src*='.png'], img[src*='.webp']"
        )

        val pages = mutableListOf<Page>()
        
        images.forEachIndexed { index, img ->
            // Prioritaskan data-src (lazy loading) kemudian src
            val imageUrl = when {
                img.absUrl("data-src").isNotEmpty() -> img.absUrl("data-src")
                img.absUrl("src").isNotEmpty() -> img.absUrl("src")
                else -> ""
            }
            
            // Hanya tambahkan jika URL valid dan seperti image
            if (imageUrl.isNotEmpty() && 
                (imageUrl.contains(".jpg") || imageUrl.contains(".png") || 
                 imageUrl.contains(".webp") || imageUrl.contains(".jpeg"))) {
                pages.add(Page(index, "", imageUrl))
            }
        }
        
        // Fallback: jika tidak ada image yang ditemukan, coba cari dalam script atau data attributes
        if (pages.isEmpty()) {
            val scriptContent = document.select("script").joinToString(" ") { it.html() }
            // Pattern untuk mencari URL image dalam JavaScript
            val imagePattern = Regex("""["'](https?://[^"']*\.(jpg|jpeg|png|webp)[^"']*)["']""")
            imagePattern.findAll(scriptContent).forEachIndexed { index, match ->
                pages.add(Page(index, "", match.groupValues[1]))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String {
        // Fallback method jika pageListParse tidak berhasil
        return document.selectFirst("img[data-src], img")?.let { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }.orEmpty()
    }
}