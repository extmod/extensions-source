package eu.kanade.tachiyomi.extension.id.mangaid

import eu.kanade.tachiyomi.extension.id.mangaid.sources.CosmicScans
import eu.kanade.tachiyomi.extension.id.mangaid.sources.Kiryuu
import eu.kanade.tachiyomi.extension.id.mangaid.sources.Komikcast
import eu.kanade.tachiyomi.extension.id.mangaid.sources.Shinigami
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class MangaId : HttpSource() {

    override val name    = "MangaID"
    override val baseUrl = "https://c.shinigami.asia"
    override val lang    = "id"
    override val supportsLatest = true

    // ─── SOURCES ─────────────────────────────────────────────────────────────

    private val shinigami   = Shinigami(network.cloudflareClient)
    private val kiryuu      = Kiryuu(network.cloudflareClient)
    private val komikcast   = Komikcast(network.cloudflareClient)
    private val cosmicscans = CosmicScans(network.cloudflareClient)

    // ─── ROUTING ─────────────────────────────────────────────────────────────
    // manga.url format: "sourcePrefix/type/slug"
    // e.g. "shinigami/series/solo-leveling"
    //      "kiryuu/manga/solo-leveling"
    //      "komikcast/series/solo-leveling"
    //      "cosmicscans/manga/solo-leveling"

    private fun routeSource(url: String): Pair<String, String> {
        val parts  = url.trimStart('/').split("/", limit = 3)
        val source = parts.getOrElse(0) { "" }
        val slug   = parts.getOrElse(2) { "" }
        return source to slug
    }

    // ─── AGGREGATE HELPERS ───────────────────────────────────────────────────

    private fun aggregateFetch(
        fetchFns: List<() -> List<MangaEntry>>,
        sortByDate: Boolean = false,
    ): MangasPage {
        val results = fetchFns.map { fn -> runCatching { fn() } }
        val all     = results.mapNotNull { it.getOrNull() }.flatten()

        var deduped = Deduplicator.deduplicate(all).map { it.manga }
        if (sortByDate) {
            val timeMap = all.associate { normalizeTitle(it.manga.title) to it.updatedAt }
            deduped = deduped.sortedByDescending { timeMap[normalizeTitle(it.title)] ?: 0L }
        }

        return MangasPage(deduped, deduped.size >= 24)
    }

    // ─── LATEST ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            aggregateFetch(
                listOf(
                    { shinigami.fetchLatest(page) },
                    { kiryuu.fetchLatest(page) },
                    { komikcast.fetchLatest(page) },
                    { cosmicscans.fetchLatest(page) },
                ),
                sortByDate = true,
            )
        }
    }

    // ─── POPULAR ─────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            aggregateFetch(
                listOf(
                    { shinigami.fetchPopular(page) },
                    { kiryuu.fetchPopular(page) },
                    { komikcast.fetchPopular(page) },
                    { cosmicscans.fetchPopular(page) },
                ),
            )
        }
    }

    // ─── SEARCH ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response): MangasPage =
        throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            aggregateFetch(
                listOf(
                    { shinigami.fetchSearch(page, query) },
                    { kiryuu.fetchSearch(page, query) },
                    { komikcast.fetchSearch(page, query) },
                    { cosmicscans.fetchSearch(page, query) },
                ),
            )
        }
    }

    // ─── MANGA DETAIL ────────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request =
        throw UnsupportedOperationException()

    override fun mangaDetailsParse(response: Response): SManga =
        throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val (source, slug) = routeSource(manga.url)
            when (source) {
                PREFIX_SHINIGAMI   -> shinigami.fetchMangaDetails(slug)
                PREFIX_KIRYUU      -> kiryuu.fetchMangaDetails(slug)
                PREFIX_KOMIKCAST   -> komikcast.fetchMangaDetails(slug)
                PREFIX_COSMICSCANS -> cosmicscans.fetchMangaDetails(slug)
                else -> throw Exception("Unknown source: $source")
            }
        }
    }

    // ─── CHAPTER LIST ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request =
        throw UnsupportedOperationException()

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val (source, slug) = routeSource(manga.url)
            when (source) {
                PREFIX_SHINIGAMI   -> shinigami.fetchChapterList(slug)
                PREFIX_KIRYUU      -> kiryuu.fetchChapterList(slug)
                PREFIX_KOMIKCAST   -> komikcast.fetchChapterList(slug)
                PREFIX_COSMICSCANS -> cosmicscans.fetchChapterList(slug)
                else -> throw Exception("Unknown source: $source")
            }
        }
    }

    // ─── PAGE LIST ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request =
        throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val (source, slug) = routeSource(chapter.url)
            when (source) {
                PREFIX_SHINIGAMI   -> shinigami.fetchPageList(slug)
                PREFIX_KIRYUU      -> kiryuu.fetchPageList(slug)
                PREFIX_KOMIKCAST   -> komikcast.fetchPageList(slug)
                PREFIX_COSMICSCANS -> cosmicscans.fetchPageList(slug)
                else -> throw Exception("Unknown source: $source")
            }
        }
    }

    // ─── IMAGE ───────────────────────────────────────────────────────────────

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()
}
