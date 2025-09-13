package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class KomikV : HttpSource() {

    override val name: String = "KomikV"
    override val baseUrl: String = "https://komikav.net"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = super.client

    private val defaultHeaders: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Tachiyomi")
        .add("Accept", "*/*")
        .build()

    // ---------- Helpers ----------
    // Parse response body into Jsoup Document (read body once)
    private fun responseToDoc(response: Response): Document {
        val body = response.body?.string().orEmpty()
        return Jsoup.parse(body)
    }

    // Build a simple Qwik payload example (sesuaikan jika implement qfunc)
    private fun buildQwikPayloadExample(qrl: String, entry: Int = 2): RequestBody {
        val obj = """{"_entry":"$entry","_objs":["\u0002_#s_$qrl",3,["0","1"]]}"""
        return obj.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    }

    // ---------- Requests ----------
    override fun popularMangaRequest(page: Int): Request {
        // page 1 -> SSR HTML
        if (page <= 1) {
            return GET(baseUrl, defaultHeaders)
        }

        // contoh: jika sudah punya qrl token, gunakan endpoint ?qfunc=token
        // val qrl = "aBKj8Qeh2MM" // TODO: ambil/detect token secara dinamis
        // return Request.Builder()
        //     .url("$baseUrl/?qfunc=$qrl")
        //     .post(buildQwikPayloadExample(qrl, 2))
        //     .headers(defaultHeaders)
        //     .build()

        // fallback: ambil halaman utama bila belum ada mekanisme qfunc
        return GET(baseUrl, defaultHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/?s=${java.net.URLEncoder.encode(query, "utf-8")}"
        return GET(url, defaultHeaders)
    }

    override fun mangaDetailsRequest(mangaUrl: String): Request =
        GET(if (mangaUrl.startsWith("http")) mangaUrl else baseUrl + mangaUrl, defaultHeaders)

    override fun chapterListRequest(mangaUrl: String): Request =
        GET(if (mangaUrl.startsWith("http")) mangaUrl else baseUrl + mangaUrl, defaultHeaders)

    override fun pageListRequest(chapterUrl: String): Request =
        GET(if (chapterUrl.startsWith("http")) chapterUrl else baseUrl + chapterUrl, defaultHeaders)

    // ---------- Parsers ----------
    override fun popularMangaParse(response: Response): MangasPage {
        val doc = responseToDoc(response)

        // selector contoh — sesuaikan dengan struktur situs
        val elements = doc.select("div.grid div.flex.overflow-hidden, .list-update_item, article")
        val mangas = elements.map { el ->
            SManga.create().apply {
                title = el.selectFirst("h2, .title, .name")?.text().orEmpty()
                val a = el.selectFirst("a")
                val href = a?.attr("href").orEmpty()
                url = if (href.startsWith("/")) href else href
                thumbnail_url = el.selectFirst("img")?.attr("data-src").orEmpty()
                    .ifEmpty { el.selectFirst("img")?.attr("src").orEmpty() }
            }
        }

        // cek tombol load more (JS-driven)
        val hasLoadMore = doc.select("div[q\\:slot=loadmore], button:contains(Load More), [data-load-more]").isNotEmpty()
        return MangasPage(mangas, hasLoadMore)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = responseToDoc(response)
        val manga = SManga.create()
        manga.title = doc.selectFirst("h1")?.text().orEmpty()
        manga.thumbnail_url = doc.selectFirst("img")?.attr("data-src").orEmpty()
            .ifEmpty { doc.selectFirst("img")?.attr("src").orEmpty() }
        manga.description = doc.selectFirst(".summary, .entry-content, .desc, .sinopsis")?.text().orEmpty()
        // Args lain (author, status) dapat ditambahkan bila ditemukan di DOM
        return manga
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = responseToDoc(response)
        val chapters = mutableListOf<SChapter>()
        val elements = doc.select("ul.chapters li, .chapter-list li, .chapter-item, a.chapter")
        for (el in elements) {
            val link = el.selectFirst("a") ?: el
            val ch = SChapter.create()
            ch.name = link.text().orEmpty()
            ch.url = link.attr("href").orEmpty()
            // ch.date_upload = ... (parse jika tersedia)
            chapters.add(ch)
        }
        return chapters
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = responseToDoc(response)
        val pages = mutableListOf<Page>()
        val imgs = doc.select("div#chapter_body img, .main-reading-area img, .page img, img.wp-manga-chapter-img")
        imgs.forEachIndexed { i, img ->
            val imgUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            pages.add(Page(i, "", imgUrl))
        }
        return pages
    }

    // Required by HttpSource
    protected override fun imageUrlParse(response: Response): String {
        val doc = responseToDoc(response)
        return doc.selectFirst("img")?.absUrl("src").orEmpty()
    }
}

// Factory (jika dibutuhkan)
class KomikVFactory : SourceFactory {
    override fun createSources() = listOf(KomikV())
}
