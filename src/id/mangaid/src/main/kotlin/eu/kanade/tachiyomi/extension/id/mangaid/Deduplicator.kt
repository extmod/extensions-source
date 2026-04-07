package eu.kanade.tachiyomi.extension.id.mangaid

import eu.kanade.tachiyomi.source.model.SManga

data class MangaEntry(
    val manga: SManga,
    val updatedAt: Long = 0L,
)

object Deduplicator {

    fun deduplicate(entries: List<MangaEntry>): List<MangaEntry> {
        val seen   = mutableMapOf<String, Int>()
        val result = mutableListOf<MangaEntry>()

        for (entry in entries) {
            val key = normalizeTitle(entry.manga.title)
            if (key.isEmpty()) {
                result.add(entry)
                continue
            }

            val idx = seen[key] ?: -1
            if (idx == -1) {
                seen[key] = result.size
                result.add(entry)
            } else {
                val existTime   = result[idx].updatedAt
                val currentTime = entry.updatedAt

                // bug fix dari JS: simpan yang lebih BARU (tc > te, bukan tc < te)
                if (currentTime > existTime) {
                    result[idx] = entry
                    seen[key]   = idx
                }
                // kalau lebih lama, skip
            }
        }

        return result
    }
}
