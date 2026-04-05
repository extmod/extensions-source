package eu.kanade.tachiyomi.extension.id.komikagregator

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── AGREGATOR ───────────────────────────────────────────────────────────────

@Serializable
data class AggregatorListResponse(
    val data: List<AggregatorManga>,
    val hasNextPage: Boolean = false,
)

@Serializable
data class AggregatorManga(
    val source: String,
    val id: String,
    val slug: String,
    val title: String,
    val cover: String = "",
    val status: String = "",
    val updatedAt: String = "",
    val url: String = "",
) {
    fun toSManga(): SManga = SManga.create().apply {
        url           = "${this@AggregatorManga.source}:${this@AggregatorManga.slug}:${this@AggregatorManga.url}"
        title         = this@AggregatorManga.title
        thumbnail_url = this@AggregatorManga.cover
        status        = when (this@AggregatorManga.status.lowercase()) {
            "ongoing"   -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else        -> SManga.UNKNOWN
        }
    }
}

// ─── SHINIGAMI ───────────────────────────────────────────────────────────────

@Serializable
data class ShinigamiDetailResponse(val data: ShinigamiMangaDetail)

@Serializable
data class ShinigamiMangaDetail(
    val title: String? = null,
    val status: Int? = null,
    val description: String? = null,
    val taxonomy: Map<String, List<ShinigamiTaxonomy>>? = null,
)

@Serializable
data class ShinigamiTaxonomy(val name: String = "")

@Serializable
data class ShinigamiChapterListResponse(
    @SerialName("chapter_list") val chapterList: List<ShinigamiChapter> = emptyList(),
    val data: List<ShinigamiChapter> = emptyList(),
)

@Serializable
data class ShinigamiChapter(
    @SerialName("chapter_id")     val chapterId: String? = null,
    @SerialName("chapter_number") val chapterNumber: Double? = null,
    val title: String? = null,
    @SerialName("release_date")   val releaseDate: String? = null,
)

@Serializable
data class ShinigamiPageListResponse(val data: ShinigamiPageData? = null)

@Serializable
data class ShinigamiPageData(
    @SerialName("base_url") val baseUrl: String? = null,
    val chapter: ShinigamiChapterPages? = null,
)

@Serializable
data class ShinigamiChapterPages(
    val path: String? = null,
    val data: List<String> = emptyList(),
)

// ─── KOMIKCAST ───────────────────────────────────────────────────────────────

@Serializable
data class KomikcastDetailResponse(val data: KomikcastDetailWrapper? = null)

@Serializable
data class KomikcastDetailWrapper(
    val data: KomikcastMangaDetail? = null,
)

@Serializable
data class KomikcastMangaDetail(
    val title: String? = null,
    val coverImage: String? = null,
    val status: String? = null,
    val author: String? = null,
    val format: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val genres: List<KomikcastGenre>? = null,
)

@Serializable
data class KomikcastGenre(
    val data: KomikcastGenreData? = null,
    val name: String? = null,
)

@Serializable
data class KomikcastGenreData(val name: String? = null)

@Serializable
data class KomikcastChapterListResponse(val data: List<KomikcastChapter> = emptyList())

@Serializable
data class KomikcastChapter(
    val id: Int? = null,
    val data: KomikcastChapterData? = null,
    val createdAt: String? = null,
)

@Serializable
data class KomikcastChapterData(
    val index: Int? = null,
    val title: String? = null,
    val slug: String? = null,
)

@Serializable
data class KomikcastPageListResponse(val data: KomikcastPageWrapper? = null)

@Serializable
data class KomikcastPageWrapper(
    val data: KomikcastPageData? = null,
    val images: List<String>? = null,
)

@Serializable
data class KomikcastPageData(val images: List<String>? = null)

// ─── KIRYUU ──────────────────────────────────────────────────────────────────

@Serializable
data class KiryuuMangaDto(
    val slug: String? = null,
    val title: KiryuuTitle? = null,
    val content: KiryuuContent? = null,
    @SerialName("class_list") val classList: List<String>? = null,
    @SerialName("_embedded")  val embedded: KiryuuEmbedded? = null,
)

@Serializable
data class KiryuuTitle(val rendered: String? = null)

@Serializable
data class KiryuuContent(val rendered: String? = null)

@Serializable
data class KiryuuEmbedded(
    @SerialName("wp:featuredmedia") val featuredMedia: List<KiryuuMedia>? = null,
    @SerialName("wp:term")          val wpTerm: List<List<KiryuuTerm>>? = null,
)

@Serializable
data class KiryuuMedia(@SerialName("source_url") val sourceUrl: String? = null)

@Serializable
data class KiryuuTerm(
    val name: String? = null,
    val taxonomy: String? = null,
)

@Serializable
data class KiryuuChapterListResponse(val data: List<KiryuuChapter> = emptyList())

@Serializable
data class KiryuuChapter(
    val name: String? = null,
    val url: String? = null,
    val date: String? = null,
)
