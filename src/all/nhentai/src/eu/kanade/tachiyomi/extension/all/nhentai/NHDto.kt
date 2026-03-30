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
    val cover: HentaiImage? = null,
    val thumbnail: HentaiImage? = null,
)

@Serializable
class Title(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class HentaiImage(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
class HentaiPage(
    val number: Int,
    val path: String,
    val thumbnail: String,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
class Tag(
    val name: String,
    val type: String,
)

// Wrapper untuk script[data-sveltekit-fetched]
// Field statusText dan headers sengaja tidak dimasukkan,
// parsing pakai ignoreUnknownKeys = true
@Serializable
class SvelteKitFetched(
    val status: Int,
    val body: String,
)
