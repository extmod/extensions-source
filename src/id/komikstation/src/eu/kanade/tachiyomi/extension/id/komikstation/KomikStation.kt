package eu.kanade.tachiyomi.extension.id.komikstation

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale

class KomikStation : MangaThemesia("Komik Station", "https://Komikstation.org", "id", dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))) {
    // Formerly "Komik Station (WP Manga Stream)"
    override val id = 6148605743576635261

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(4)
        .build()

    override fun searchMangaFromElement(element: Element): SManga {
    return SManga.create().apply {
        thumbnail_url = element.select("img").attr("src").trim().let {
            if (it.isEmpty()) "" else "https://images.weserv.nl/?w=800&q=75&url=$it"
        }
    }
}

    override val projectPageString = "/project-list"

    override val hasProjectPage = true
}
