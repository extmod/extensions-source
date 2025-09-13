package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KomikV : ParsedHttpSource() {

    override val name: String = "KomikV"
    override val baseUrl: String = "https://komikav.net"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = super.client

    private val defaultHeaders: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Android) Tachiyomi")
        .add("Accept", "*/*")
        .build()

    // -------------------
    // Requests (simple)
    // -------------------
    override fun popularMangaRequest(page: Int): Request {
        // Halaman 1 SSR, halaman >1 mungkin butuh qfunc (Qwik). Untuk sekarang fallback ke query param page.
        return GET("$baseUrl/?page=$page", defaultHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "utf-8")
        return GET("$baseUrl/?s=$encoded&page=$page", defaultHeaders)
    }

    // -------------------
    // Popular parsers (ParsedHttpSource style)
    // -------------------
    override fun popularMangaSelector(): String = "div.list-update_items .list-update_item, article, .bs"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            // title
            title = element.selectFirst("h2, .title, .name")?.text().orEmpty()

            // url - prefer href attribute of first anchor
            val href = element.selectFirst("a")?.attr("href").orEmpty()
            url = if (href.startsWith("http")) href else href

            // thumbnail
            thumbnail_url = element.selectFirst("img")?.attr("data-src").orEmpty()
                .ifEmpty { element.selectFirst("img")?.attr("src").orEmpty() }
        }
    }

    // Qwik-driven sites often have no real "next page" selector; keep a heuristic selector
    override fun popularMangaNextPageSelector(): String? = "div[q\\:slot=loadmore], button:contains(Load More), [data-load-more]"

    // -------------------
    // Latest - reuse popular
    // -------------------
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector(): String? = popularMangaNextPageSelector()

    // -------------------
    // Search - reuse popular
    // -------------------
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // -------------------
    // Manga detail
    // -------------------
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("h1")?.text().orEmpty()
        manga.thumbnail_url = document.selectFirst("img")?.attr("data-src").orEmpty()
            .ifEmpty { document.selectFirst("img")?.attr("src").orEmpty() }
        manga.description = document.selectFirst(".summary, .entry-content, .desc, .sinopsis")?.text().orEmpty()

        // optional: author, status, genre parsing (tambahkan bila DOM jelas)
        manga.author = document.selectFirst(".author, .meta-author")?.text().orEmpty()
        val statusText = document.selectFirst(".status, .meta-status")?.text().orEmpty().lowercase()
        manga.status = when {
            statusText.contains("ongoing") || statusText.contains("on-going") || statusText.contains("on going") || statusText.contains("on going") ->  SManga.ONGOING
            statusText.contains("completed") || statusText.contains("finish") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        return manga
    }

    // -------------------
    // Chapters
    // -------------------
    override fun chapterListSelector(): String = "ul.chapters li a, .chapter-list a, .wp-manga-chapter a, .chapters a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        // element might be an <a> or contain <a>
        val a = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
        chapter.name = a.text().orEmpty()
        chapter.url = a.attr("href").orEmpty()
        // date parsing optional
        return chapter
    }

    // -------------------
    // Pages
    // -------------------
    override fun pageListParse(document: Document): List<Page> {
        // Ambil gambar pembaca (SSR area)
        val imgs = document.select("div#chapter_body img, .main-reading-area img, .page img, img.wp-manga-chapter-img")
        if (imgs.isEmpty()) {
            // fallback: ambil semua img di konten
            return document.select("img").mapIndexed { i, el ->
                val src = el.absUrl("data-src").ifEmpty { el.absUrl("src") }
                Page(i, "", src)
            }
        }
        return imgs.mapIndexed { i, el ->
            val src = el.absUrl("data-src").ifEmpty { el.absUrl("src") }
            Page(i, "", src)
        }
    }

    // parsed variant of imageUrlParse takes Document
    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("img")?.absUrl("data-src").orEmpty()
            .ifEmpty { document.selectFirst("img")?.absUrl("src").orEmpty() }
    }
}

// Optional: factory if repo expects SourceFactory
class KomikVFactory : SourceFactory {
    override fun createSources() = listOf(KomikV())
}
