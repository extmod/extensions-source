package eu.kanade.tachiyomi.extension.all.nhentai

import kotlinx.serialization.Serializable

@Serializable
class Hentai(
    val id: Int,
    val media_id: String,
    val title: Title,
    val tags: List<Tag>,
    val num_pages: Int,
    val num_favorites: Long,
    val upload_date: Long,
    val pages: List<HentaiPage>,
)

@Serializable
class Title(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class HentaiPage(
    val number: Int,
    val path: String,        // e.g. "galleries/2179013/1.jpg"
    val thumbnail: String,   // e.g. "galleries/2179013/1t.jpg"
    val width: Int = 0,
    val height: Int = 0,
) {
    // Ambil ekstensi dari path langsung, misal "1.jpg" -> "jpg"
    val extension: String get() = path.substringAfterLast('.', "jpg")
    val thumbnailExtension: String get() = thumbnail.substringAfterLast('.', "jpg")
}

@Serializable
class Tag(
    val name: String,
    val type: String,
)

// Wrapper untuk response dari script[data-sveltekit-fetched]
@Serializable
class SvelteKitFetched(
    val status: Int,
    val body: String,
)
