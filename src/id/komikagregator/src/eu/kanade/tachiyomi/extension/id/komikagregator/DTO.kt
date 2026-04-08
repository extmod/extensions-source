package eu.kanade.tachiyomi.extension.id.komikagregator

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── LIST (popular / latest / search) ────────────────────────────────────────

@Serializable
data class NormalizedMangaItem(
    // format: "source:slug:originalUrl"  — digenerate di backend
    val url: String,
    val title: String,
    val cover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        this.url           = this@NormalizedMangaItem.url
        this.title         = this@NormalizedMangaItem.title
        this.thumbnail_url = cover
    }
}

@Serializable
data class NormalizedListResponse(
    val data: List<NormalizedMangaItem>,
    val hasNextPage: Boolean = false,
)

// ─── DETAIL ───────────────────────────────────────────────────────────────────

@Serializable
data class NormalizedDetail(
    val title: String,
    val cover: String? = null,
    // 0 = unknown, 1 = ongoing, 2 = completed
    val status: Int = SManga.UNKNOWN,
    val author: String? = null,
    val artist: String? = null,
    val genres: String? = null,
    val description: String? = null,
    // Tambahan field untuk judul alternatif
    val altTitles: List<String> = emptyList(),
)

// ─── CHAPTER LIST ─────────────────────────────────────────────────────────────

@Serializable
data class NormalizedChapter(
    val name: String,
    // format: "source:slug:chapterId"  — opaque, dikirim balik ke /pages
    val url: String,
    val date: String? = null,
)

@Serializable
data class NormalizedChapterListResponse(
    val chapters: List<NormalizedChapter>,
)

// ─── PAGE LIST ────────────────────────────────────────────────────────────────

@Serializable
data class NormalizedPageListResponse(
    val pages: List<String>,
)
