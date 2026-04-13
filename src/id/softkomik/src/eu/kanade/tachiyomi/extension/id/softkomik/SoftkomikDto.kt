package eu.kanade.tachiyomi.extension.id.softkomik

import kotlinx.serialization.Serializable

@Serializable
data class SessionDto(
    val token: String,
    val sign: String,
    val ex: Long,
)

@Serializable
data class LibDataDto(
    val data: List<MangaDto>,
    val page: Int,
    val maxPage: Int,
)

@Serializable
data class MangaDto(
    val title: String,
    val title_slug: String,
    val gambar: String,
    val status: String? = null,
    val type: String? = null,
    val latest_chapter: String? = null,
)

@Serializable
data class MangaDetailsDto(
    val title: String,
    val author: String? = null,
    val sinopsis: String? = null,
    val Genre: List<String>? = emptyList(),
    val status: String? = null,
    val type: String? = null,
    val gambar: String,
)

@Serializable
data class ChapterListDto(
    val chapter: List<ChapterDto>,
)

@Serializable
data class ChapterDto(
    val chapter: String,
)

@Serializable
data class ChapterPageDataDto(
    val _id: String,
    val imageSrc: List<String>,
    val storageInter2: Boolean? = false,
)

@Serializable
data class ChapterPageImagesDto(
    val imageSrc: List<String>,
)
