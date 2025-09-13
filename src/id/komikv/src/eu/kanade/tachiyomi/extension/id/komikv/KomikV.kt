package eu.kanade.tachiyomi.extension.id.komikv

import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.regex.Pattern
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.OkHttpClient
import okhttp3.Request

class KomikV : HttpSource() {
    override val name = "KomikAV (example)"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true

    // headers biasa
    private val defaultHeaders = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Tachiyomi")
        .add("Accept", "*/*")
        .build()

    override fun client(): OkHttpClient = super.client()

    // page 1 -> ambil halaman biasa
    override fun popularMangaRequest(page: Int): Request {
        return if (page <= 1) {
            GET(baseUrl, defaultHeaders)
        } else {
            // untuk page > 1: kita akan panggil qfunc (Qwik) endpoint
            // tetapi kita butuh 'qrl' token — ambil dari halaman awal jika belum ada
            // NOTE: dalam implementasi final, simpan token di memory / generate per-session
            val qrl = "" // akan diisi oleh popularMangaParse jika perlu (lihat catatan)
            val payload = buildQwikPayload(qrl, page)
            Request.Builder()
                .url("$baseUrl/?qfunc=$qrl")
                .post(payload)
                .headers(defaultHeaders)
                .build()
        }
    }

    // Helper: membangun body JSON mirip contoh yang kamu temukan.
    private fun buildQwikPayload(qrl: String, page: Int): RequestBody {
        // Saya menggunakan pola umum dari contoh: {"_entry":"2","_objs":["\u0002_#s_<QRL>",3,["0","1"]]}
        // _entry kemungkinan merepresentasikan action/entry index — gunakan page sebagai default
        val entryValue = page.toString()
        val qrlEscaped = "\\u0002_#s_$qrl" // literal dengan escape seperti di traffic
        val obj = JSONObject()
        obj.put("_entry", entryValue)
        val objs = org.json.JSONArray()
        objs.put(qrlEscaped)
        objs.put(3)
        val inner = org.json.JSONArray()
        inner.put("0")
        inner.put("1")
        objs.put(inner)
        obj.put("_objs", objs)

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        return obj.toString().toRequestBody(mediaType)
    }

    // Parser: tangani baik HTML biasa (page 1) atau response Qwik JSON (load more)
    override fun popularMangaParse(response: Response): MangasPage {
        val bodyString = response.body?.string().orEmpty()

        // Jika ini response Qwik-json (response dari ?qfunc=...), maka header atau content berisi "application/qwik-json"
        val contentType = response.header("Content-Type", "") ?: ""
        if (contentType.contains("application/qwik-json") || bodyString.trim().startsWith("{") || bodyString.contains("\"_objs\"")) {
            // parse dengan heuristik regex: ambil all posters, slugs, titles dalam urutan kemunculan lalu zip
            return parseQwikJsonList(bodyString)
        }

        // fallback: parse DOM SSR (page 1)
        val doc: Document = response.asJsoup()
        // Coba cari token QRL di HTML (id atau atribut yang menyertakan _#s_... )
        val qrlFromHtml = findQrlInHtml(doc)
        // (opsional) simpan token di cache / field sehingga popularMangaRequest bisa menggunakannya
        // TODO: implementasikan penyimpanan token jika ingin memanggil qfunc nanti

        val elements = doc.select("div.grid div.flex.overflow-hidden, div.your-item-selector") // ganti selector sesuai situs
        val mangas = elements.map { el ->
            SManga.create().apply {
                title = el.select("h2, .title").text()
                url = el.select("a").attr("href")
                thumbnail_url = el.select("img").attr("data-src").ifEmpty { el.select("img").attr("src") }
            }
        }

        // tentukan ada next page? cek apakah tombol load more ada
        val hasLoadMore = doc.select("div[q\\:slot=loadmore], button:contains(Load More), [data-load-more]").isNotEmpty()
        return MangasPage(mangas, hasLoadMore)
    }

    // Heuristik: parse body Qwik JSON (string) dan ekstrak poster/slug/title menggunakan regex
    private fun parseQwikJsonList(body: String): MangasPage {
        // normalisasi escaped slashes
        val normalized = body.replace("\\/", "/")

        // regex sederhana — ambil semua occurrence di urutan yang sama seperti di payload
        val posterRegex = "\"poster\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val slugRegex = "\"slug\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val titleRegex = "\"([^\"]+)\"\\s*,\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"".toRegex()
        // NOTE: titleRegex di atas adalah heuristik; kalau gagal, gunakan alternatif:
        val altTitleRegex = "\"title\"\\s*:\\s*\"([^\"]+)\"".toRegex()

        val posters = posterRegex.findAll(normalized).map { it.groupValues[1] }.toList()
        val slugs = slugRegex.findAll(normalized).map { it.groupValues[1] }.toList()
        // ambil title dengan prioritas: langsung "title": "..." kalau ada, kalau tidak coba fallback
        val titlesDirect = altTitleRegex.findAll(normalized).map { it.groupValues[1] }.toList()
        val titlesFallback = mutableListOf<String>()
        if (titlesDirect.isEmpty()) {
            // fallback: cari pola di mana judul tampak sebagai string besar di array
            // (cukup ambil setiap string yang tampak seperti judul: huruf + spasi dan panjang > 3)
            val genericStringRegex = "\"([A-Za-z0-9\\s'.,:!\\-()\\u0080-\\uFFFF]{4,})\"".toRegex()
            genericStringRegex.findAll(normalized).forEach {
                val s = it.groupValues[1]
                // ambil kandidat yang kemungkinan besar judul (heuristik)
                if (s.length < 200 && s.any { ch -> ch.isLetterOrDigit() } && s.contains(' ')) {
                    titlesFallback.add(s)
                }
            }
        }
        val titles = if (titlesDirect.isNotEmpty()) titlesDirect else titlesFallback

        // Ambil minimal length dari ketiga list
        val count = listOf(posters.size, slugs.size, titles.size).minOrNull() ?: 0
        val mangas = (0 until count).map { i ->
            SManga.create().apply {
                title = titles.getOrNull(i) ?: slugs.getOrNull(i)?.replace('-', ' ') ?: "No title"
                url = slugs.getOrNull(i)?.let { if (it.startsWith("/")) it else "/manga/$it/" } ?: ""
                thumbnail_url = posters.getOrNull(i) ?: ""
            }
        }

        // HasNext: heuristik sederhana — bila jumlah items >= typical page size (mis. 20) -> ada next
        // Better: periksa payload response apakah terdapat id/array ekstra yang menandakan cursor
        val hasNext = (count >= 20) // sesuaikan angka 20 jika situs menampilkan per-page berbeda
        return MangasPage(mangas, hasNext)
    }

    // Cari token QRL di HTML (mis. id yang mengandung "_#s_")
    private fun findQrlInHtml(doc: Document): String? {
        // coba cari atribut/element yang mengandung '_#s_' pattern
        val pattern = Pattern.compile("_#s_([A-Za-z0-9]+)")
        // cari di attribute 'id' semua elemen
        for (el in doc.select("*[id]")) {
            val id = el.id()
            val m = pattern.matcher(id)
            if (m.find()) return m.group(1)
        }
        // coba cari di inline script
        for (script in doc.select("script")) {
            val txt = script.html()
            val m = pattern.matcher(txt)
            if (m.find()) return m.group(1)
        }
        return null
    }

    // ---- fungsi lain (wajib override, minimal) ----
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, defaultHeaders)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaRequest(page: Int, query: String, filters: javax.inject.Inject?) = GET("$baseUrl/?s=${query}", defaultHeaders)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    override fun mangaDetailsRequest(mangaUrl: String): Request = GET(baseUrl + mangaUrl, defaultHeaders)
    override fun mangaDetailsParse(response: Response) = throw NotImplementedError("Implement if needed")
    override fun chapterListRequest(mangaUrl: String): Request = GET(baseUrl + mangaUrl, defaultHeaders)
    override fun chapterListParse(response: Response) = throw NotImplementedError("Implement if needed")
    override fun pageListRequest(chapterUrl: String): Request = GET(baseUrl + chapterUrl, defaultHeaders)
    override fun pageListParse(response: Response) = throw NotImplementedError("Implement if needed")
}
