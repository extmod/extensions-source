package eu.kanade.tachiyomi.extension.id.softkomik

import kotlinx.serialization.Serializable

@Serializable
data class LibDataDto(
    val data: List<MangaDto>,
    val maxPage: Int,
    val page: Int,
)

@Serializable
data class MangaDto(
    val gambar: String,
    val title: String,
    val title_slug: String,
    val status: String? = null,
    val type: String? = null,
)

@Serializable
data class MangaDetailsDto(
    val gambar: String,
    val title: String,
    val author: String? = null,
    val Genre: List<String>? = emptyList(),
    val sinopsis: String? = null,
    val status: String? = null,
    val type: String? = null,
)

@Serializable
data class ChapterDto(
    val chapter: String,
)

@Serializable
data class ChapterListDto(
    val chapter: List<ChapterDto>,
)

@Serializable
data class VercelTokenDto(
    val token: String,
    val sign: String,
    val exp: Long,
    val images: List<String> = emptyList(),
)
