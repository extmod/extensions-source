# MangaID — Tachiyomi/Mihon Extension

Agregator 4 source manga Indonesia:
- Shinigami (API JSON)
- Komikcast (API JSON)
- Kiryuu (WordPress REST API + HTML)
- CosmicScans (HTML scraping via Jsoup)

## Setup

### Prerequisites
- Android Studio / IntelliJ IDEA
- Clone repo [extensions-source](https://github.com/mihonapp/extensions-source) dari Mihon

### Cara Install ke Repo Extensions

1. Copy folder `mangaid/` ke:
   ```
   extensions-source/src/id/
   ```

2. Pastikan `settings.gradle` di root project include folder `id/mangaid`

3. Build APK:
   ```bash
   ./gradlew :extensions:id:mangaid:assembleDebug
   ```

4. APK output ada di:
   ```
   extensions/id/mangaid/build/outputs/apk/debug/
   ```

5. Install APK ke HP, lalu di Mihon:
   **More → Extensions → pilih MangaID**

---

## Struktur URL

Setiap manga/chapter menggunakan prefix source di URL-nya:

| Source      | Manga URL                        | Chapter URL                        |
|-------------|----------------------------------|------------------------------------|
| Shinigami   | `shinigami/series/{slug}`        | `shinigami/chapter/{chapter_id}`   |
| Komikcast   | `komikcast/series/{slug}`        | `komikcast/chapter/{slug}`         |
| Kiryuu      | `kiryuu/manga/{slug}`            | `kiryuu/chapter/{chapter-slug}`    |
| CosmicScans | `cosmicscans/manga/{slug}`       | `cosmicscans/chapter/{slug}`       |

---

## Catatan

### Cookie CosmicScans
Cookie di `CosmicScans.kt` bisa expired. Kalau CosmicScans tidak muncul datanya:
1. Login ke lc5.cosmicscans.asia di browser
2. Copy cookie `wordpress_logged_in_*` dan `wpdiscuz_nonce_*`
3. Update di `CosmicScans.kt` baris `private val cookie = ...`
4. Rebuild APK

### Deduplication
Manga yang muncul di lebih dari 1 source akan di-dedup berdasarkan judul (normalize).
Yang disimpan adalah entry dengan `updatedAt` paling baru.
Source asalnya tetap tercatat di `manga.url` sehingga detail/chapter/pages tetap fetch ke source yang benar.
