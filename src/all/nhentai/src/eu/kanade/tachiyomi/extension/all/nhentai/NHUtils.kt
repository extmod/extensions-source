package eu.kanade.tachiyomi.extension.all.nhentai

object NHUtils {
    fun getArtists(data: Hentai): String {
        return data.tags
            .filter { it.type == "artist" }
            .joinToString(", ") { it.name }
    }

    fun getGroups(data: Hentai): String? {
        return data.tags
            .filter { it.type == "group" }
            .joinToString(", ") { it.name }
            .takeIf { it.isNotBlank() }
    }

    fun getTagDescription(data: Hentai): String {
        val tags = data.tags.groupBy { it.type }
        return buildString {
            tags["category"]?.joinToString { it.name }?.let {
                append("Categories: ", it, "\n")
            }
            tags["parody"]?.joinToString { it.name }?.let {
                append("Parodies: ", it, "\n")
            }
            tags["character"]?.joinToString { it.name }?.let {
                append("Characters: ", it, "\n\n")
            }
        }
    }

    fun getTags(data: Hentai): String {
        return data.tags
            .filter { it.type == "tag" }
            .joinToString(", ") { it.name }
    }
}
