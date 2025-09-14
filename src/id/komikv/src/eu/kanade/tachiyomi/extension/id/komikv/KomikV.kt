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

    // dateFormat untuk parsing tanggal absolut (sesuaikan pattern & locale bila perlu)
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

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