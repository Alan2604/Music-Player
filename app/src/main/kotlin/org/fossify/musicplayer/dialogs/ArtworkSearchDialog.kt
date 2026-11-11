package org.fossify.musicplayer.dialogs

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.ArtworkSearch

/**
 * Lets the user search high resolution covers online (iTunes catalog) and pick one.
 * [onPicked] receives the downloaded bitmap (up to 1200x1200) on the UI thread.
 */
class ArtworkSearchDialog(
    private val activity: Activity,
    private val songQuery: String,
    private val albumQuery: String,
    private val textColor: Int,
    private val bgColor: Int,
    private val accentColor: Int,
    private val onPicked: (Bitmap) -> Unit,
) {
    private val results = ArrayList<ArtworkSearch.Result>()
    private var dialog: AlertDialog? = null
    private var searchToken = 0

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var adapter: BaseAdapter

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun show() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(8))
            setBackgroundColor(bgColor)
        }

        container.addView(TextView(activity).apply {
            text = activity.getString(R.string.metro_artwork_title)
            textSize = 22f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(activity).apply {
            setText(songQuery)
            setSingleLine()
            hint = activity.getString(R.string.metro_artwork_hint)
            setTextColor(textColor)
            setHintTextColor((textColor and 0x00FFFFFF) or 0x80000000.toInt())
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSearch(text.toString().trim(), text.toString().trim())
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(activity).apply {
            text = activity.getString(R.string.metro_artwork_search)
            setTextColor(accentColor)
            background = null
            setOnClickListener {
                val query = input.text.toString().trim()
                runSearch(query, query)
            }
        })
        container.addView(row)

        status = TextView(activity).apply {
            setTextColor(textColor)
            alpha = 0.7f
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        container.addView(status)

        adapter = object : BaseAdapter() {
            override fun getCount() = results.size
            override fun getItem(position: Int) = results[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = results[position]
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(4), dp(4), dp(8))
                }

                val size = (activity.resources.displayMetrics.widthPixels - dp(40 + 24)) / 2
                val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                cell.addView(image, LinearLayout.LayoutParams(size, size))
                Glide.with(activity).load(item.thumbUrl).centerCrop().into(image)

                cell.addView(TextView(activity).apply {
                    text = item.album
                    setTextColor(textColor)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                cell.addView(TextView(activity).apply {
                    text = item.artist
                    setTextColor(textColor)
                    alpha = 0.6f
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                return cell
            }
        }

        val grid = GridView(activity).apply {
            numColumns = 2
            horizontalSpacing = dp(8)
            verticalSpacing = dp(4)
            adapter = this@ArtworkSearchDialog.adapter
            setOnItemClickListener { _, _, position, _ -> pick(results[position]) }
        }
        container.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))

        dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(bgColor))
        dialog?.setOnDismissListener { searchToken++ }
        dialog?.show()
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentColor)

        runSearch(songQuery, albumQuery)
    }

    private fun runSearch(song: String, album: String) {
        if (song.isBlank()) {
            return
        }

        val token = ++searchToken
        status.text = activity.getString(R.string.metro_artwork_searching)
        Thread {
            val found = ArtworkSearch.search(song, album)
            activity.runOnUiThread {
                if (token != searchToken) {
                    return@runOnUiThread
                }

                results.clear()
                results.addAll(found)
                adapter.notifyDataSetChanged()
                status.text = if (found.isEmpty()) {
                    activity.getString(R.string.metro_artwork_none)
                } else {
                    activity.getString(R.string.metro_artwork_tap)
                }
            }
        }.start()
    }

    private fun pick(result: ArtworkSearch.Result) {
        status.text = activity.getString(R.string.metro_artwork_downloading)
        Thread {
            val bitmap = ArtworkSearch.download(result)
            activity.runOnUiThread {
                if (bitmap != null) {
                    onPicked(bitmap)
                    dialog?.dismiss()
                } else {
                    status.text = activity.getString(R.string.metro_artwork_failed)
                    Toast.makeText(activity, R.string.metro_artwork_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
