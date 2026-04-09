package eu.kanade.tachiyomi.extension.id.komikagregator

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
data class NormalizedMangaItem(
    val url: String,
    val title: String,
    val cover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        this.url = this@NormalizedMangaItem.url
        this.title = this@NormalizedMangaItem.title
        this.thumbnail_url = cover
    }
}

@Serializable
data class NormalizedListResponse(
    val data: List<NormalizedMangaItem> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class NormalizedDetail(
    val title: String,
    val cover: String? = null,
    val status: Int = SManga.UNKNOWN,
    val author: String? = null,
    val artist: String? = null,
    val genres: String? = null,
    val description: String? = null,
    val altTitles: List<String> = emptyList(),
)

@Serializable
data class NormalizedChapter(
    val name: String,
    val url: String,
    val date: Long? = null,
)

@Serializable
data class NormalizedChapterListResponse(
    val chapters: List<NormalizedChapter> = emptyList(),
)

@Serializable
data class NormalizedPageListResponse(
    val pages: List<String> = emptyList(),
)
