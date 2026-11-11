package org.fossify.musicplayer.helpers

import android.content.Context
import android.provider.MediaStore
import org.fossify.musicplayer.models.Track
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/**
 * Finds the lyrics of a track.
 * Order: 1) lyrics already stored with the song (.lrc next to the file, or the lyrics tag),
 *        2) lyrics cached from a previous online search,
 *        3) online search in LRCLIB (free, needs internet).
 * Call [load] from a background thread.
 */
object LyricsHelper {
    private const val API = "https://lrclib.net/api"
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[[A-Za-z]+:.*]$""")

    fun load(context: Context, track: Track): Lyrics? {
        getLocal(track)?.let { return it }
        getCached(context, track)?.let { return it }

        val raw = fetchOnline(track) ?: return null
        saveCache(context, track, raw)
        return parse(raw)
    }

    fun parse(raw: String): Lyrics? {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val allLines = text.split('\n')

        val synced = ArrayList<LyricLine>()
        for (line in allLines) {
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) {
                continue
            }

            val content = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLong()
                val seconds = tag.groupValues[2].toLong()
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                synced.add(LyricLine((minutes * 60 + seconds) * 1000 + millis, content))
            }
        }

        if (synced.size >= 2) {
            synced.sortBy { it.timeMs }
            return Lyrics(synced, true)
        }

        val plain = allLines
            .map { it.trim() }
            .filterNot { META_TAG.matches(it) }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        if (plain.isEmpty()) {
            return null
        }

        return Lyrics(plain.map { LyricLine(-1L, it) }, false)
    }

    private fun getLocal(track: Track): Lyrics? {
        val path = track.path
        if (path.isEmpty()) {
            return null
        }

        try {
            val lrc = File(path.substringBeforeLast('.', path) + ".lrc")
            if (lrc.exists()) {
                parse(lrc.readText())?.let { return it }
            }
        } catch (ignored: Exception) {
        }

        try {
            val file = File(path)
            if (file.exists()) {
                val lyrics = AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)
                if (!lyrics.isNullOrBlank()) {
                    return parse(lyrics)
                }
            }
        } catch (ignored: Exception) {
        }

        return null
    }

    private fun cacheFile(context: Context, track: Track) =
        File(File(context.filesDir, "lyrics"), "${track.mediaStoreId}.txt")

    private fun getCached(context: Context, track: Track): Lyrics? {
        return try {
            val file = cacheFile(context, track)
            if (file.exists()) parse(file.readText()) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCache(context: Context, track: Track, raw: String) {
        try {
            val file = cacheFile(context, track)
            file.parentFile?.mkdirs()
            file.writeText(raw)
        } catch (ignored: Exception) {
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun cleanOrNull(value: String): String? {
        val trimmed = value.trim()
        return if (trimmed.isEmpty() || trimmed == MediaStore.UNKNOWN_STRING) null else trimmed
    }

    private fun JSONObject.text(key: String): String {
        return if (isNull(key)) "" else optString(key, "")
    }

    private fun pickLyrics(obj: JSONObject): String? {
        val synced = obj.text("syncedLyrics")
        if (synced.isNotBlank()) {
            return synced
        }

        val plain = obj.text("plainLyrics")
        return if (plain.isNotBlank()) plain else null
    }

    private fun fetchOnline(track: Track): String? {
        val title = cleanOrNull(track.title) ?: return null
        val artist = cleanOrNull(track.artist)
        val album = cleanOrNull(track.album)

        // 1) exact match
        try {
            val url = StringBuilder("$API/get?track_name=${enc(title)}")
            artist?.let { url.append("&artist_name=${enc(it)}") }
            album?.let { url.append("&album_name=${enc(it)}") }
            if (track.duration > 0) {
                url.append("&duration=${track.duration}")
            }

            NetHelper.getString(url.toString())?.let { body ->
                pickLyrics(JSONObject(body))?.let { return it }
            }
        } catch (ignored: Exception) {
        }

        // 2) looser search
        try {
            val url = StringBuilder("$API/search?track_name=${enc(title)}")
            artist?.let { url.append("&artist_name=${enc(it)}") }
            val body = NetHelper.getString(url.toString()) ?: return null
            val array = JSONArray(body)

            var best: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val lyrics = pickLyrics(obj) ?: continue
                val hasSynced = obj.text("syncedLyrics").isNotBlank()
                val diff = abs(obj.optInt("duration", 0) - track.duration) + if (hasSynced) 0 else 1000
                if (diff < bestDiff) {
                    best = lyrics
                    bestDiff = diff
                }
            }
            return best
        } catch (ignored: Exception) {
        }

        return null
    }
}
