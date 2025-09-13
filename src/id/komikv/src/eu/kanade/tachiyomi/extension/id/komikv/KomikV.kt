package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document

class KomikV : HttpSource() {

    override val name: String = "KomikV"
    override val baseUrl: String = "https://komikav.net"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    // gunakan super.client (atau ganti dengan network.client jika project menyediakan)
    override val client: OkHttpClient = super.client

    private val defaultHeaders: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Tachiyomi")
        .add("Accept", "*/*")
        .build()

    // ---------- Requests ----------
    override fun popularMangaRequest(page: Int): Request {
        // halaman awal diambil via SSR. Untuk page > 1 harus meniru qfunc (Qwik) request —
        // tapi untuk kompilasi awal: ambil halaman utama dan fallback.
        return GET(baseUrl, defaultHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: com.github.michaelbull.result.Result<*, *>?): Request {
        // signature di beberapa varian Tachiyomi bisa berbeda; jika terjadi compile error
        // pada signature ini, sesuaikan tipe filters sesuai versi ekstensi Anda.
        return GET("$baseUrl/?s=${query}", defaultHeaders)
    }

    override fun mangaDetailsRequest(mangaUrl: String): Request =
        GET(if (mangaUrl.startsWith("http")) mangaUrl else baseUrl + mangaUrl, defaultHeaders)

    override fun chapterListRequest(mangaUrl: String): Request =
        GET(if (mangaUrl.startsWith("http")) mangaUrl else baseUrl + mangaUrl, defaultHeaders)

    override fun pageListRequest(chapterUrl: String): Request =
        GET(if (chapterUrl.startsWith("http")) chapterUrl else baseUrl + chapterUrl, defaultHeaders)

    // ---------- Parsers ----------
    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()

        // Jika response adalah Qwik-json (application/qwik-json), kita bisa parse body
        // dengan heuristik — tapi untuk sekarang fallback ke DOM parsing jika tersedia.
        // Gunakan response.asJsoup() agar Jsoup helper dipakai.
        val doc: Document = try {
            // jika body sudah dibaca .string(), asJsoup() mungkin tidak bisa langsung dipanggil:
            // jadi buat Jsoup langsung dari string
            org.jsoup.Jsoup.parse(body)
        } catch (e: Exception) {
            response.asJsoup()
        }

        // Contoh selector umum — sesuaikan dengan struktur situs
        val elements = doc.select("div.grid div.flex.overflow-hidden, div[class*=list-update_item], article")
        val mangas = elements.map { el ->
            SManga.create().apply {
                title = el.select("h2, .title, .name").firstOrNull()?.text().orEmpty()
                val href = el.select("a").firstOrNull()?.attr("href").orEmpty()
                url = if (href.startsWith("http")) href else href.ifEmpty { "" }
                thumbnail_url = el.select("img").firstOrNull()?.attr("data-src").orEmpty()
                    .ifEmpty { el.select("img").firstOrNull()?.attr("src").orEmpty() }
            }
        }

        // cek tombol load more / indikasi JS-driven pagination
        val hasLoadMore = doc.select("div[q\\:slot=loadmore], button:contains(Load More), [data-load-more]").isNotEmpty()

        return MangasPage(mangas, hasLoadMore)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val manga = SManga.create()
        manga.title = doc.selectFirst("h1")?.text().orEmpty()
        manga.thumbnail_url = doc.selectFirst("img")?.attr("data-src").orEmpty()
            .ifEmpty { doc.selectFirst("img")?.attr("src").orEmpty() }
        // isi field lain sesuai kebutuhan
        return manga
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        // contoh selector; sesuaikan situs
        val elements = doc.select("ul.chapters li, .chapter-list li, .chapter-item")
        for (el in elements) {
            val ch = SChapter.create()
            ch.name = el.selectFirst("a")?.text().orEmpty()
            ch.url = el.selectFirst("a")?.attr("href").orEmpty()
            // waktu dibuat/chap number dapat ditambahkan bila ada
            chapters.add(ch)
        }
        return chapters
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val pages = mutableListOf<Page>()

        // cara sederhana: ambil semua <img> di area baca
        val imgs = doc.select("div#chapter_body img, .main-reading-area img, .page img")
        imgs.forEachIndexed { i, img ->
            val imgUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            pages.add(Page(i, "", imgUrl))
        }

        return pages
    }

    // ---------- Required by HttpSource ----------
    // Implementasi ini diperlukan oleh HttpSource (dipakai untuk image fetching)
    protected override fun imageUrlParse(response: Response): String {
        // sederhana: parse satu gambar dari halaman (bila perlu, sesuaikan)
        val doc = response.asJsoup()
        return doc.selectFirst("img")?.absUrl("src").orEmpty()
    }

    // ---------- utility minimal untuk search signature compatibility ----------
    // Jika signature searchMangaRequest di project Anda berbeda, ganti method di atas
    // sesuai API Tachiyomi yang Anda gunakan.
}

// Jika modul ekstensi Anda butuh factory untuk multi-source, tambahkan juga:
class KomikVFactory : SourceFactory {
    override fun createSources() = listOf( KomikV() )
}
