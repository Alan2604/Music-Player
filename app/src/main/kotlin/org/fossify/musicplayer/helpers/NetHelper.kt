package org.fossify.musicplayer.helpers

import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny HTTP helper. No extra dependencies. Always call it from a background thread.
 */
object NetHelper {
    private const val USER_AGENT = "MetroPlayer/1.1 (personal use)"

    fun getString(url: String): String? = getBytes(url)?.toString(Charsets.UTF_8)

    fun getBytes(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
