package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.net.URLDecoder
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
        private var lastSearchLastUrl: String? = null

        fun resetSeen() {
            seenUrls.clear()
            lastSearchLastUrl = null
        }
    }

    // Flag internal untuk menangani paging search
    private var searchFinished: Boolean = false
    private var currentSearchQuery: String? = null

    // dateFormat untuk parsing tanggal absolut (sesuaikan pattern & locale bila perlu)
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

    // ---------------------------
    // Unified element parser implemented as searchMangaFromElement
    // popularMangaFromElement & latestUpdatesFromElement call this function
    // ---------------------------
    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        // Title: coba beberapa selector umum (h2 a, h2, a[title], dll)
        val title = listOf(
            "h2 a", "h2", "a.title", "a[title]", "div.title a", "div > a > h2"
        ).firstNotNullOfOrNull { sel ->
            element.selectFirst(sel)?.text()?.trim()
        } ?: element.text().trim()

        // URL: ambil dari <a> pertama yang punya href
        val url = listOf(
            "a[href]", "h2 a[href]", "div > a[href]"
        ).mapNotNull { sel ->
            element.selectFirst(sel)?.attr("href")
        }.firstOrNull().orEmpty()

        // Thumbnail: coba data-src lalu src
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
    // Search
    // ---------------------------
    private var qfuncId: String? = null // Simpan qfunc yang ditemukan
    
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
    if (page == 1) {
        resetSeen()
        currentSearchQuery = query // Perbarui query
        qfuncId = null // Atur ulang qfuncId
    }

    val words = currentSearchQuery?.trim()?.split("\\s+".toRegex()) ?: listOf("")
    val firstWord = words.first()
    val encodedFirstWord = URLEncoder.encode(firstWord, "UTF-8").replace("+", "%20")
    
    // Permintaan GET untuk halaman pertama
    if (page == 1) {
        return GET("$baseUrl/search/$encodedFirstWord", headers)
    }

    // Permintaan POST untuk halaman berikutnya
    val qfunc = qfuncId ?: throw IllegalStateException("qfuncId not available")
    
    // Payload untuk permintaan POST
    val payload = "{\"_entry\":\"2\",\"_objs\":[\"\\u0002_#s_$qfunc\",$page,[\"0\",\"${page - 1}\"]]}"
    val requestBody = payload.toRequestBody("application/qwik-json".toMediaType())
    
    val requestUrl = "$baseUrl/search/$encodedFirstWord?qfunc=$qfunc"

    return Request.Builder()
        .url(requestUrl)
        .headers(headers)
        .post(requestBody)
        .build()
}

    override fun searchMangaSelector(): String = "div.grid div.overflow-hidden"

    // Selector spesifik untuk tombol "Load More" (gunakan kelas yang valid dari markup)
    override fun searchMangaNextPageSelector(): String? =
        "span.mx-auto.mt-4.cursor-pointer"

    override fun searchMangaParse(response: Response): MangasPage {
    if (response.request.url.toString().contains("about:blank")) {
        return MangasPage(emptyList(), false)
    }

    val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
    
    val allResults = document.select(searchMangaSelector())
        .map { searchMangaFromElement(it) }
        .filter { it.url.isNotBlank() && it.title.isNotBlank() }

    val newMangas = allResults
        .distinctBy { it.url }
        .filter { seenUrls.add(it.url) }

    val hasNextPage = document.selectFirst("span.mx-auto.mt-4.cursor-pointer") != null
    
    // Hanya ambil qfuncId saat memproses halaman pertama (page=1)
    if (response.request.method == "GET" && response.request.url.queryParameter("page") == null) {
        // Cari elemen tombol "Load More"
        val loadMoreButton = document.selectFirst("span.mx-auto.mt-4.cursor-pointer[data-qrl]")
        if (loadMoreButton != null) {
            val qrlAttr = loadMoreButton.attr("data-qrl")
            // qfuncId biasanya ada setelah _#s_
            qfuncId = qrlAttr.substringAfter("_#s_")
        }
    }

    return MangasPage(newMangas, hasNextPage)
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

    override fun chapterListSelector() = "div.mt-4.flex.max-h-96.flex-col > a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        // url
        val url = element.attr("href")
        chapter.setUrlWithoutDomain(url)

        // Judul: p pertama di dalam <a> atau fallback ke teks elemen
        val titleEl = element.selectFirst("div > p:first-of-type, p:first-of-type")
        chapter.name = titleEl?.text()?.trim() ?: element.text().trim()

        // Cari teks tanggal di beberapa kemungkinan selector
        val dateCandidates = listOf(
            "p.text-xs.font-medium",
            "p.text-xs",
            "div > p:nth-of-type(2)",
            "time",
            "span.time",
            "small"
        )
        var dateText: String? = null
        for (sel in dateCandidates) {
            val e = element.selectFirst(sel)
            if (e != null && e.text().isNotBlank()) {
                dateText = e.text().trim()
                break
            }
        }

        // Fallback: cari pola relatif waktu di keseluruhan teks elemen (contoh: "35 mnt lalu", "2 jam")
        if (dateText.isNullOrBlank()) {
            val r = Regex("""(?:(\d+)\s*)?(detik|dtk|menit|mnt|jam|hari|mgg|minggu|bln|bulan|thn|tahun)\b""", RegexOption.IGNORE_CASE)
            val m = r.find(element.text())
            if (m != null) dateText = m.value
        }

        // Set tanggal (epoch millis). Jika null/invalid -> 0L
        chapter.date_upload = parseChapterDate(dateText ?: "")

        // --- Parse chapter number dari judul (jika ada) agar urutan bisa diatur dengan tepat ---
        val numberRegex = Regex("""(\d+(?:[.,]\d+)?)""")
        val numberMatch = numberRegex.find(chapter.name)
        chapter.chapter_number = numberMatch?.value?.replace(",", ".")?.toFloatOrNull() ?: 0f

        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        val txt = date.lowercase().trim()

        // Regex yang menangkap angka opsional + satuan
        val regex = Regex("""(?:(\d+)\s*)?(detik|dtk|menit|mnt|jam|hari|mgg|minggu|bln|bulan|thn|tahun)\b""")
        val match = regex.find(txt)

        if (match != null) {
            val valueStr = match.groupValues[1]
            val value = if (valueStr.isBlank()) 1 else {
                try { valueStr.toInt() } catch (_: Exception) { 1 }
            }

            val unit = match.groupValues[2]
            val cal = Calendar.getInstance()
            when (unit) {
                "detik", "dtk" -> cal.add(Calendar.SECOND, -value)
                "menit", "mnt" -> cal.add(Calendar.MINUTE, -value)
                "jam" -> cal.add(Calendar.HOUR_OF_DAY, -value)
                "hari" -> cal.add(Calendar.DATE, -value)
                "mgg", "minggu" -> cal.add(Calendar.DATE, -value * 7)
                "bln", "bulan" -> cal.add(Calendar.MONTH, -value)
                "thn", "tahun" -> cal.add(Calendar.YEAR, -value)
                else -> return 0L
            }
            return cal.timeInMillis
        }

        // Jika tidak cocok pola relatif, coba parse sebagai tanggal absolut
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        val chapters = document.select(chapterListSelector())
            .map { chapterFromElement(it) }
            .filter { it.url.isNotEmpty() && it.name.isNotEmpty() }

        // Urutkan:
        // 1) jika ada chapter_number -> urutkan berdasarkan chapter_number desc
        // 2) jika tidak ada, tapi ada date_upload -> urutkan berdasarkan date_upload desc
        // 3) fallback -> reversed()
        return when {
            chapters.any { it.chapter_number != 0f } -> chapters.sortedByDescending { it.chapter_number }
            chapters.any { it.date_upload > 0L } -> chapters.sortedByDescending { it.date_upload }
            else -> chapters.reversed()
        }
    }

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