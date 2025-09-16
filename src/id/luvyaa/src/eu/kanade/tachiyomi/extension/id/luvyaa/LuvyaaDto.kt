package eu.kanade.tachiyomi.extension.id.luvyaa

import kotlinx.serialization.Serializable

@Serializable
data class LuvyaaDto(
    val `data`: DataWrapper,
) {
    val pages: List<String>
        get() = data.data.sources
            .firstOrNull { it.images.isNotEmpty() }
            ?.images ?: emptyList()
}

@Serializable
class Source(
    val images: List<String>,
)

@Serializable
class DataWrapper(
    val `data`: Data,
)

@Serializable
class Data(
    val sources: List<Source>,
)