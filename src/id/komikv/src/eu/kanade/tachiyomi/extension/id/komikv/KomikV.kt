package eu.kanade.tachiyomi.extension.id.komikv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Calendar

class KomikV : ParsedHttpSource() {
    override val name = "KomikV"
    override val baseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    private val ITEMS_PER_PAGE = 18

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/popular/" else "$baseUrl/popular/?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotEmpty()) {
            val q = URLEncoder.encode(query, "UTF-8")
            val baseSearch = "$baseUrl/search/$q/"
            val url = if (page <= 1) baseSearch else "$baseSearch?page=$page"
            GET(url, headers)
        } else {
            val url = if (page <= 1) baseUrl else "$baseUrl/?page=$page"
            GET(url, headers)
        }
    }

    override fun popularMangaSelector(): String =
        "div.grid > div.flex > div:first-child a.relative, div.grid a.relative"

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        // thumbnail: coba beberapa atribut (src, data-src, data-original) dengan absUrl
        val imgElem = element.selectFirst("div.limit img") ?: element.selectFirst("img")
        val thumb = imgElem?.let { img ->
            img.absUrl("src").ifEmpty {
                img.absUrl("data-src").ifEmpty {
                    img.absUrl("data-original").ifEmpty {
                        img.attr("src").trim()
                    }
                }
            }
        } ?: ""

        // title: prefer h2 a, fallback teks element
        val title = element.selectFirst("h2 a")?.text()?.trim().orEmpty().ifEmpty {
            element.text().trim()
        }

        // url: cari anchor khusus (hindari a.text-sm), aman tanpa !!
        val href = element.selectFirst("h2.items-center a:not(.text-sm)")?.attr("href")?.trim()
            ?: element.selectFirst("a.relative")?.attr("href")?.trim()
            ?: element.selectFirst("a")?.attr("href")?.trim()

        if (!href.isNullOrEmpty()) {
            manga.setUrlWithoutDomain(href)
        }

        manga.title = title
        manga.thumbnail_url = thumb

        return manga
    }

    override fun popularMangaNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchMangaNextPageSelector(): String? = null

    private fun <T> chunkForPage(all: List<T>, page: Int): List<T> {
        val per = ITEMS_PER_PAGE
        if (all.isEmpty()) return emptyList()
        val start = (page - 1) * per
        val end = (page * per).coerceAtMost(all.size)
        return if (start < all.size) all.subList(start, end) else emptyList()
    }

    private fun parsePagedResponse(response: Response, selector: String, mapper: (Element) -> SManga): MangasPage {
        val doc = response.asJsoup()
        val pageNum = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mapped = doc.select(selector).map { mapper(it) }
        val items = chunkForPage(mapped, pageNum)
        val hasNext = items.isNotEmpty() && items.size >= ITEMS_PER_PAGE
        return MangasPage(items, hasNext)
    }

    override fun popularMangaParse(response: Response): MangasPage =
        parsePagedResponse(response, popularMangaSelector(), ::popularMangaFromElement)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parsePagedResponse(response, latestUpdatesSelector(), ::latestUpdatesFromElement)

    override fun searchMangaParse(response: Response): MangasPage =
        parsePagedResponse(response, searchMangaSelector(), ::searchMangaFromElement)

    override fun mangaDetailsParse(document: Document): SManga {
    val manga = SManga.create()
    
    manga.title = document.selectFirst("h1.text-xl")?.text()?.trim() ?: ""
    
    manga.thumbnail_url = document.selectFirst("img[alt*='${manga.title}'], img.w-full.rounded-md")?.attr("src") ?: ""
    
    val descriptionElement = document.selectFirst("div.mt-4.w-full p")
    manga.description = descriptionElement?.text()?.trim() ?: ""
    
    val genreElements = document.select("a[href*='/tax/genre/']")
    val genres = genreElements.map { it.text().trim() }.filter { it.isNotEmpty() }
    
    val typeElement = document.selectFirst("div.w-full.rounded-l-full.bg-red-800")
    val comicType = typeElement?.text()?.trim() ?: ""
    
    val allGenres = mutableListOf<String>()
    if (comicType.isNotEmpty()) {
        allGenres.add(comicType)
    }
    allGenres.addAll(genres)
    manga.genre = allGenres.joinToString(", ")
    
    val description = manga.description
    val genre = manga.genre
    if (!description.isNullOrEmpty() && !genre.isNullOrEmpty()) {
        manga.description = description + "\n\n" + genre
    } else if (!genre.isNullOrEmpty()) {
        manga.description = genre
    }
    
    val statusElement = document.selectFirst("div.w-full.rounded-r-full")
    val statusText = statusElement?.text()?.trim() ?: ""
    manga.status = parseStatus(statusText)
    
    val authorElements = document.select("a[href*='/tax/author/']")
    val authors = mutableListOf<String>()
    val artists = mutableListOf<String>()
    
    authorElements.forEach { element ->
        val fullText = element.text().trim()
        when {
            fullText.contains("(Author)", false) -> {
                val cleanAuthor = fullText.substringBefore("(").trim()
                if (cleanAuthor.isNotEmpty()) {
                    authors.add(cleanAuthor)
                }
            }
            fullText.contains("(Artist)", false) -> {
                val cleanArtist = fullText.substringBefore("(").trim()
                if (cleanArtist.isNotEmpty()) {
                    artists.add(cleanArtist)
                }
            }
        }
    }
    
    manga.author = authors.joinToString(", ")
    manga.artist = artists.joinToString(", ")
    
    return manga
}

private fun parseStatus(statusString: String): Int = when {
    statusString.contains("on-going") -> SManga.ONGOING
    statusString.contains("complete") -> SManga.COMPLETED
    else -> SManga.UNKNOWN
}

    override fun chapterFromElement(element: Element): SChapter {
    val chapter = SChapter.create()

    val href = element.attr("href").trim()
    try {
        chapter.setUrlWithoutDomain(href)
    } catch (_: Throwable) {
        chapter.url = href
    }

    chapter.name = element.selectFirst("p")?.text()?.trim()
        ?: element.text().trim()

    val timeText = element.selectFirst("p.text-xs")?.text()?.trim() ?: ""
    chapter.date_upload = parseDate(timeText)

    return chapter
}

    override fun chapterListSelector(): String = "div.grid.gap-y-3 a"

    private fun parseDate(date: String): Long {
        val trimmed = date.trim()
        val now = System.currentTimeMillis()
        val parts = trimmed.split(" ")
        if (parts.size < 2) return 0L

        val number = parts[0].toIntOrNull() ?: return 0L
        val unit = parts[1]

        val multiplier = when (unit) {
            "dtk"  -> 1000L
            "mnt"  -> 60_000L
            "jam"  -> 3_600_000L
            "hari" -> 86_400_000L
            "mgg"  -> 604_800_000L
            "bln"  -> 2_592_000_000L
            "thn"  -> 31_536_000_000L
            else   -> 0L
        }

        return now - (number * multiplier)
    }

    override fun pageListParse(document: Document): List<Page> {
        val imgs = document.select("img.imgku")
        return imgs.mapIndexed { i, img ->
            val src = img.absUrl("src")
            Page(i, src)
        }
    }

    override fun imageUrlParse(document: Document): String {
        throw UnsupportedOperationException("imageUrlParse is not implemented for this source")
    }
}