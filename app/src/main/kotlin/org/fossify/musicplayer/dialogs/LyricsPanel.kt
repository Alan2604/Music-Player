package org.fossify.musicplayer.dialogs

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.LyricLine
import org.fossify.musicplayer.helpers.Lyrics
import org.fossify.musicplayer.helpers.LyricsHelper
import org.fossify.musicplayer.models.Track

/**
 * Full screen lyrics panel (Spotify style): synced lyrics scroll with the song, the current line
 * is highlighted with the accent color and a tap on a line jumps to it. Plain lyrics are shown as
 * a normal scrollable text.
 */
class LyricsPanel(
    private val activity: Activity,
    private val textColor: Int,
    private val bgColor: Int,
    private val accentColor: Int,
    private val onSeek: (Long) -> Unit,
) {
    private var dialog: Dialog? = null
    private var scroll: ScrollView? = null
    private var holder: LinearLayout? = null
    private var status: TextView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null

    private var lines: List<LyricLine> = emptyList()
    private var synced = false
    private val lineViews = ArrayList<TextView>()
    private var currentIndex = -1
    private var lastPosition = 0L
    private var loadToken = 0

    val isShowing: Boolean get() = dialog?.isShowing == true

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun show(track: Track, positionMs: Long) {
        lastPosition = positionMs
        if (!isShowing) {
            buildDialog()
        }
        load(track)
    }

    fun onTrackChanged(track: Track) {
        if (isShowing) {
            lastPosition = 0L
            load(track)
        }
    }

    fun dismiss() {
        loadToken++
        dialog?.dismiss()
        dialog = null
    }

    fun onPosition(positionMs: Long) {
        lastPosition = positionMs
        if (!isShowing || !synced || lines.isEmpty()) {
            return
        }

        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs + 250) {
                index = i
            } else {
                break
            }
        }

        if (index != currentIndex) {
            highlight(index)
        }
    }

    private fun buildDialog() {
        val root = FrameLayout(activity)
        root.setBackgroundColor(bgColor)

        val scrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(120), 0, dp(220))
        }
        val linesHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        scrollView.addView(
            linesHolder,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            scrollView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(24), dp(36), dp(12), dp(12))
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply {
            setTextColor(textColor)
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
        }
        val artist = TextView(activity).apply {
            setTextColor(textColor)
            alpha = 0.6f
            textSize = 13f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 1
        }
        titles.addView(title)
        titles.addView(artist)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(activity).apply {
            text = "✕"
            textSize = 22f
            setTextColor(textColor)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { dismiss() }
        }
        header.addView(close)
        root.addView(
            header,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        val statusView = TextView(activity).apply {
            setTextColor(textColor)
            alpha = 0.7f
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(32), 0, dp(32), 0)
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        val newDialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        newDialog.setContentView(root)
        newDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(bgColor))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        newDialog.setOnDismissListener {
            loadToken++
            dialog = null
        }

        dialog = newDialog
        scroll = scrollView
        holder = linesHolder
        status = statusView
        titleView = title
        artistView = artist
        newDialog.show()
    }

    private fun load(track: Track) {
        val token = ++loadToken
        titleView?.text = track.title
        artistView?.text = track.artist
        holder?.removeAllViews()
        lineViews.clear()
        lines = emptyList()
        currentIndex = -1
        synced = false
        status?.apply {
            text = activity.getString(R.string.metro_lyrics_loading)
            visibility = View.VISIBLE
        }

        Thread {
            val lyrics = try {
                LyricsHelper.load(activity, track)
            } catch (e: Exception) {
                null
            }

            activity.runOnUiThread {
                if (token == loadToken && isShowing) {
                    render(lyrics)
                }
            }
        }.start()
    }

    private fun render(lyrics: Lyrics?) {
        if (lyrics == null) {
            status?.apply {
                text = activity.getString(R.string.metro_lyrics_none)
                visibility = View.VISIBLE
            }
            return
        }

        status?.visibility = View.GONE
        lines = lyrics.lines
        synced = lyrics.synced

        lyrics.lines.forEachIndexed { index, line ->
            val view = TextView(activity).apply {
                text = line.text.ifEmpty { "♪" }
                textSize = 26f
                setTextColor(textColor)
                alpha = if (synced) 0.4f else 0.9f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setPadding(0, dp(if (line.text.isEmpty() && !synced) 6 else 10), 0, dp(if (line.text.isEmpty() && !synced) 6 else 10))
                if (synced && line.timeMs >= 0) {
                    setOnClickListener {
                        onSeek(line.timeMs)
                        highlight(index)
                    }
                }
            }
            holder?.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            lineViews.add(view)
        }

        onPosition(lastPosition)
    }

    private fun highlight(index: Int) {
        lineViews.getOrNull(currentIndex)?.apply {
            setTextColor(textColor)
            alpha = 0.4f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

        currentIndex = index
        val view = lineViews.getOrNull(index) ?: return
        view.setTextColor(accentColor)
        view.alpha = 1f
        view.typeface = Typeface.create("sans-serif", Typeface.BOLD)

        scroll?.let { scrollView ->
            scrollView.post {
                val target = (view.top - scrollView.height / 3).coerceAtLeast(0)
                scrollView.smoothScrollTo(0, target)
            }
        }
    }
}
