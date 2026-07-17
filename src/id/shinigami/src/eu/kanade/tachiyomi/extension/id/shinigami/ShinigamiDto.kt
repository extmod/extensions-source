package eu.kanade.tachiyomi.extension.id.shinigami

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ShinigamiBrowseDto(
    val data: List<ShinigamiBrowseDataDto>,
    val meta: MetaDto,
)

@Serializable
class ShinigamiBrowseDataDto(
    @SerialName("cover_portrait_url") val thumbnailPortrait: String? = null,
    @SerialName("cover_image_url") val thumbnailLandscape: String? = null,
    @SerialName("manga_id") val mangaId: String? = null,
    val title: String? = null,
    val taxonomy: Map<String, List<ShinigamiTaxonomyItemDto>>? = null,
)

@Serializable
class MetaDto(
    val page: Int,
    @SerialName("total_page") val totalPage: Int,
)

@Serializable
class ShinigamiMangaDetailDto(
    val data: ShinigamiMangaDetailDataDto,
)

@Serializable
class ShinigamiMangaDetailDataDto(
    val status: Int = 0,
    val description: String? = null,
    val taxonomy: Map<String, List<ShinigamiTaxonomyItemDto>> = emptyMap(),
    @SerialName("cover_portrait_url") val thumbnailPortrait: String? = null,
    @SerialName("cover_image_url") val thumbnailLandscape: String? = null,
)

@Serializable
class ShinigamiTaxonomyItemDto(
    val name: String,
)

@Serializable
class ShinigamiChapterListDto(
    @SerialName("data") val chapterList: List<ShinigamiChapterListDataDto>? = null,
)

@Serializable
class ShinigamiChapterListDataDto(
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("chapter_number") val name: Double? = null,
    @SerialName("chapter_title") val title: String? = null,
    @SerialName("release_date") val date: String? = null,
)

@Serializable
class ShinigamiPageListDto(
    @SerialName("data") val pageList: ShinigamiPageDataDto,
)

@Serializable
class ShinigamiPageDataDto(
    @SerialName("chapter") val chapterPage: ShinigamiChapterPageDto,
)

@Serializable
class ShinigamiChapterPageDto(
    @SerialName("data") val pages: List<String>,
    val path: String,
)
