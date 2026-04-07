package eu.kanade.tachiyomi.extension.id.mangaid

const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// Source prefix used in manga.url to identify which source to delegate to
const val PREFIX_SHINIGAMI   = "shinigami"
const val PREFIX_KIRYUU      = "kiryuu"
const val PREFIX_KOMIKCAST   = "komikcast"
const val PREFIX_COSMICSCANS = "cosmicscans"

fun stripParens(t: String): String =
    t.replace(Regex("""\(.*?\)"""), "").replace(Regex("""\s+"""), " ").trim()

fun decodeHtml(str: String): String =
    str.replace(Regex("&#(\\d+);")) { mr ->
        mr.groupValues[1].toInt().toChar().toString()
    }
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")

fun normalizeTitle(t: String): String =
    decodeHtml(t).lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
