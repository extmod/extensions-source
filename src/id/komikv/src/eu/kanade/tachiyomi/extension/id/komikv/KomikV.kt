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
        return GET("$baseUrl/manga/?page=$page", defaultHeaders)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga/?page=$page&order=latest", defaultHeaders)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "utf-8")
        return GET("$baseUrl/?s=$encoded&page=$page", defaultHeaders)
    }

    // -------------------
    // Popular parsers (ParsedHttpSource style)
    // -------------------
    // Sesuai view-source: tiap item ada di div.grid > div.flex.overflow-hidden
    override fun popularMangaSelector(): String = "div.grid > div.flex.overflow-hidden"

    override fun popularMangaFromElement(element: Element): SManga {
        // Prefer anchor that links to manga item (bisa /manga/ atau /komik/)
        val a = element.selectFirst("a[href^=/manga/], a[href^=/komik/]") ?: element.selectFirst("a")
        val href = a?.attr("href").orEmpty()

        val img = element.selectFirst("img")
        val titleFromImg = img?.attr("alt").orEmpty()
        val titleFromH2 = element.selectFirst("h2")?.text().orEmpty()
        val title = titleFromImg.ifEmpty { titleFromH2 }

        val thumb = img?.attr("data-src").orEmpty().ifEmpty { img?.attr("src").orEmpty() }

        return SManga.create().apply {
            this.title = title
            // keep relative url if provided
            url = if (href.startsWith("http")) href else href
            thumbnail_url = thumb
        }
    }

    // Qwik-driven sites often have no real "next page" link; keep a heuristic selector for load-more
    override fun popularMangaNextPageSelector(): String? = "div[q\\:slot=loadmore], [q\\:slot=loadmore], button:contains(Load More)"

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

        manga.author = document.selectFirst(".author, .meta-author")?.text().orEmpty()
        val statusText = document.selectFirst(".status, .meta-status")?.text().orEmpty().lowercase()
        manga.status = when {
            statusText.contains("ongoing") || statusText.contains("on-going") || statusText.contains("on going") ->  SManga.ONGOING
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
        val a = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
        chapter.name = a.text().orEmpty()
        chapter.url = a.attr("href").orEmpty()
        return chapter
    }

    // -------------------
    // Pages
    // -------------------
    override fun pageListParse(document: Document): List<Page> {
        val imgs = document.select("img.lazyimage, img.wp-manga-chapter-img, #chapter_body img, .main-reading-area img, .page img")
        if (imgs.isEmpty()) {
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