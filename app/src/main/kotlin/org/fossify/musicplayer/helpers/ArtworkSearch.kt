package org.fossify.musicplayer.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Searches high resolution cover art in the iTunes Search API
 * (the same source used by Apple Music Artwork Finder). Call from a background thread.
 */
object ArtworkSearch {
    data class Result(
        val album: String,
        val artist: String,
        val thumbUrl: String,
        val hdUrl: String,
    )

    private val SIZE_REGEX = Regex("""\d+x\d+bb""")

    fun search(songQuery: String, albumQuery: String = songQuery, country: String = "us"): List<Result> {
        val found = LinkedHashMap<String, Result>()
        collect(found, albumQuery, "album", country)
        collect(found, songQuery, "song", country)
        return found.values.toList()
    }

    private fun collect(
        into: MutableMap<String, Result>,
        query: String,
        entity: String,
        country: String,
    ) {
        if (query.isBlank()) {
            return
        }

        try {
            val term = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://itunes.apple.com/search?term=$term&media=music&entity=$entity&country=$country&limit=30"
            val body = NetHelper.getString(url) ?: return
            val results = JSONObject(body).optJSONArray("results") ?: return
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val art = item.optString("artworkUrl100", "")
                if (art.isEmpty()) {
                    continue
                }

                val hd = art.replace(SIZE_REGEX, "1200x1200bb")
                if (!into.containsKey(hd)) {
                    into[hd] = Result(
                        album = item.optString("collectionName", item.optString("trackName", "")),
                        artist = item.optString("artistName", ""),
                        thumbUrl = art.replace(SIZE_REGEX, "300x300bb"),
                        hdUrl = hd
                    )
                }
            }
        } catch (ignored: Exception) {
        }
    }

    fun download(result: Result): Bitmap? {
        val candidates = listOf(result.hdUrl, result.hdUrl.replace("1200x1200bb", "600x600bb"))
        for (url in candidates) {
            val bytes = NetHelper.getBytes(url) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                return bitmap
            }
        }
        return null
    }
}
