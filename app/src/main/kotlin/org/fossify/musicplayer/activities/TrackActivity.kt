package org.fossify.musicplayer.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import com.bumptech.glide.signature.ObjectKey
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.os.postDelayed
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.musicplayer.R
import org.fossify.musicplayer.databinding.ActivityTrackBinding
import org.fossify.musicplayer.databinding.DialogMetroEditBinding
import org.fossify.musicplayer.dialogs.ArtworkSearchDialog
import org.fossify.musicplayer.dialogs.LyricsPanel
import org.fossify.musicplayer.extensions.audioHelper
import org.fossify.musicplayer.extensions.config
import org.fossify.musicplayer.extensions.getPlaybackSetting
import org.fossify.musicplayer.extensions.getTrackCoverArt
import org.fossify.musicplayer.extensions.getTrackFromUri
import org.fossify.musicplayer.extensions.isReallyPlaying
import org.fossify.musicplayer.extensions.loadGlideResource
import org.fossify.musicplayer.extensions.maybeRestartOnPrevious
import org.fossify.musicplayer.extensions.nextMediaItem
import org.fossify.musicplayer.extensions.sendCommand
import org.fossify.musicplayer.extensions.setRepeatMode
import org.fossify.musicplayer.extensions.shuffledMediaItemsIndices
import org.fossify.musicplayer.extensions.toMediaItem
import org.fossify.musicplayer.extensions.toTrack
import org.fossify.musicplayer.extensions.updatePlayPauseIcon
import org.fossify.musicplayer.fragments.PlaybackSpeedFragment
import org.fossify.musicplayer.helpers.TagHelper
import org.fossify.musicplayer.helpers.PlaybackSetting
import org.fossify.musicplayer.helpers.SEEK_INTERVAL_S
import org.fossify.musicplayer.interfaces.PlaybackSpeedListener
import org.fossify.musicplayer.models.Track
import org.fossify.musicplayer.playback.CustomCommands
import org.fossify.musicplayer.playback.PlaybackService
import java.io.File
import java.text.DecimalFormat
import kotlin.time.Duration.Companion.milliseconds

class TrackActivity : SimpleControllerActivity(), PlaybackSpeedListener {
    companion object {
        private const val SWIPE_DOWN_THRESHOLD = 100
        private const val SEEK_COALESCE_INTERVAL_MS = 150L
        private const val UPDATE_INTERVAL_MS = 150L
    }

    private var isThirdPartyIntent = false
    private lateinit var nextTrackPlaceholder: Drawable

    private val handler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(Dispatchers.Default)
    private var seekJob: Job? = null
    private var seekCount = 0

    private val binding by viewBinding(ActivityTrackBinding::inflate)

    // Metro look: colors follow the app theme. With the "Auto light / dark" theme this is
    // AMOLED black + white text in dark mode and white + black text in light mode.
    private var metroText = Color.WHITE
    private var metroBg = Color.BLACK
    private var lyricsPanel: LyricsPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        refreshMetroColors()
        setupEdgeToEdge(padBottomSystem = listOf(binding.activityTrackHolder))
        nextTrackPlaceholder =
            resources.getColoredDrawableWithColor(R.drawable.ic_headset, metroText)
        setupButtons()
        setupFlingListener()
        setupMetroToolbar()
        playMetroEntrance()

        binding.apply {
            activityTrackToolbar.setNavigationOnClickListener { finish() }
            nextTrackHolder.background = metroBg.toDrawable()
            nextTrackHolder.setOnClickListener {
                startActivity(Intent(applicationContext, QueueActivity::class.java))
            }

            isThirdPartyIntent = intent.action == Intent.ACTION_VIEW
            if (isThirdPartyIntent && (savedInstanceState == null || PlaybackService.currentMediaItem == null)) {
                initThirdPartyIntent()
                return
            }

            setupTrackInfo(PlaybackService.currentMediaItem)
            setupNextTrackInfo(PlaybackService.nextMediaItem)
            activityTrackPlayPause.updatePlayPauseIcon(
                isPlaying = PlaybackService.isPlaying,
                color = metroText
            )
            updatePlayerState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateTextColors(binding.activityTrackHolder)
        refreshMetroColors()
        binding.activityTrackToolbar.overflowIcon?.applyColorFilter(metroText)
        binding.activityTrackTitle.setTextColor(metroText)
        binding.activityTrackArtist.setTextColor(metroText)
        listOf(
            binding.activityTrackProgressCurrent,
            binding.activityTrackProgressMax,
            binding.activityTrackSpeed,
            binding.nextTrackLabel
        ).forEach { it.setTextColor(metroText) }
        updatePlayerState()
        updateTrackInfo()
    }

    override fun onPause() {
        super.onPause()
        cancelProgressUpdate()
    }

    override fun onStop() {
        super.onStop()
        cancelProgressUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        lyricsPanel?.dismiss()
        lyricsPanel = null
        cancelProgressUpdate()
        if (isThirdPartyIntent && !isChangingConfigurations) {
            withPlayer {
                if (!isReallyPlaying) {
                    sendCommand(CustomCommands.CLOSE_PLAYER)
                }
            }
        }
    }

    private fun setupTrackInfo(item: MediaItem?) {
        val track = item?.toTrack() ?: return
        editedInfo[track.mediaStoreId]?.let {
            track.title = it.first
            track.artist = it.second
        }

        setupTopArt(track)
        binding.apply {
            activityTrackTitle.text = track.title
            activityTrackArtist.text = track.artist
            activityTrackTitle.setOnLongClickListener {
                copyToClipboard(activityTrackTitle.value)
                true
            }

            activityTrackArtist.setOnLongClickListener {
                copyToClipboard(activityTrackArtist.value)
                true
            }

            activityTrackProgressbar.max = track.duration
            activityTrackProgressMax.text = track.duration.getFormattedDuration()
        }
    }

    private fun initThirdPartyIntent() {
        getTrackFromUri(intent.data) { track ->
            runOnUiThread {
                if (track != null) {
                    prepareAndPlay(listOf(track), startActivity = false)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    finish()
                }
            }
        }
    }

    private fun setupButtons() = binding.apply {
        activityTrackToggleShuffle.setOnClickListener { withPlayer { toggleShuffle() } }
        activityTrackPrevious.setOnClickListener { seekToPrevious() }
        activityTrackPlayPause.setOnClickListener { togglePlayback() }
        activityTrackNext.setOnClickListener { seekToNext() }
        activityTrackProgressCurrent.setOnClickListener { seekBack() }
        activityTrackProgressMax.setOnClickListener { seekForward() }
        activityTrackPlaybackSetting.setOnClickListener { togglePlaybackSetting() }
        activityTrackSpeedClickArea.setOnClickListener { showPlaybackSpeedPicker() }
        setupShuffleButton()
        setupPlaybackSettingButton()
        setupSeekbar()

        arrayOf(activityTrackPrevious, activityTrackPlayPause, activityTrackNext).forEach {
            it.applyColorFilter(metroText)
        }
    }

    private fun setupNextTrackInfo(item: MediaItem?) {
        val track = item?.toTrack()
        if (track == null) {
            binding.nextTrackHolder.beGone()
            return
        }

        binding.nextTrackHolder.beVisible()
        val artist =
            if (track.artist.trim().isNotEmpty() && track.artist != MediaStore.UNKNOWN_STRING) {
                " • ${track.artist}"
            } else {
                ""
            }

        @SuppressLint("SetTextI18n")
        binding.nextTrackLabel.text = "${getString(R.string.next_track)} ${track.title}$artist"

        getTrackCoverArt(track) { coverArt ->
            val cornerRadius =
                resources.getDimension(org.fossify.commons.R.dimen.rounded_corner_radius_small)
                    .toInt()
            val wantedSize = resources.getDimension(R.dimen.song_image_size).toInt()

            // change cover image manually only once loaded successfully to avoid blinking at fails and placeholders
            loadGlideResource(
                model = coverArt,
                options = RequestOptions().transform(CenterCrop(), RoundedCorners(cornerRadius)),
                size = Size(wantedSize, wantedSize),
                onLoadFailed = {
                    runOnUiThread {
                        binding.nextTrackImage.setImageDrawable(nextTrackPlaceholder)
                    }
                },
                onResourceReady = {
                    runOnUiThread {
                        binding.nextTrackImage.setImageDrawable(it)
                    }
                }
            )
        }
    }

    private fun setupTopArt(track: Track) {
        getTrackCoverArt(track) { coverArt ->
            // Metro look: flat square cover, about 80% of the screen width
            val side = (realScreenSize.x * 0.8f).toInt()

            // change cover image manually only once loaded successfully to avoid blinking at fails and placeholders
            loadGlideResource(
                model = coverArt,
                options = RequestOptions().fitCenter()
                    .signature(ObjectKey((coverArt as? File)?.lastModified() ?: 0L)),
                size = Size(side, side),
                onLoadFailed = {
                    val drawable = resources.getDrawable(R.drawable.ic_headset)
                    val placeholder = getResizedDrawable(drawable, side)
                    placeholder.applyColorFilter(metroText)

                    runOnUiThread {
                        binding.activityTrackImage.setImageDrawable(placeholder)
                    }
                },
                onResourceReady = {
                    runOnUiThread {
                        binding.activityTrackImage.setImageDrawable(it)
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Metro extras: entrance animation, toolbar buttons (search + three dots), editor, search
    // ---------------------------------------------------------------------------------

    private val editedInfo = mutableMapOf<Long, Pair<String, String>>()
    private var pendingCover: Bitmap? = null
    private var coverPreview: ImageView? = null
    private val tagHelper by lazy { TagHelper(this) }

    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onCoverPicked(uri)
        }
    }

    private fun setupMetroToolbar() {
        val toolbar = binding.activityTrackToolbar
        toolbar.inflateMenu(R.menu.menu_track)
        toolbar.overflowIcon?.applyColorFilter(metroText)
        tintToolbarIcons()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.metro_lyrics -> showLyrics()
                R.id.metro_search -> showSearchDialog()
                R.id.metro_edit_track -> showEditDialog()
            }
            true
        }
    }

    private fun refreshMetroColors() {
        metroText = getProperTextColor()
        metroBg = getProperBackgroundColor()

        binding.root.setBackgroundColor(metroBg)
        (binding.root as? ViewGroup)?.getChildAt(0)?.setBackgroundColor(metroBg)
        binding.nextTrackHolder.background = metroBg.toDrawable()
        binding.metroTitle.setTextColor(metroText)
        binding.metroHeader.setTextColor(metroText)
        binding.activityTrackToolbar.navigationIcon?.applyColorFilter(metroText)
        tintToolbarIcons()

        val lightBars = ColorUtils.calculateLuminance(metroBg) > 0.5
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    private fun tintToolbarIcons() {
        val menu = binding.activityTrackToolbar.menu
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.mutate()?.applyColorFilter(metroText)
        }
    }

    // The title/artist the user sees (including edits made in the editor)
    private fun currentTrackWithEdits(): Track? {
        val track = PlaybackService.currentMediaItem?.toTrack() ?: return null
        editedInfo[track.mediaStoreId]?.let {
            track.title = it.first
            track.artist = it.second
        }
        return track
    }

    private fun showLyrics() {
        val track = currentTrackWithEdits() ?: return
        val panel = lyricsPanel ?: LyricsPanel(
            activity = this,
            textColor = metroText,
            bgColor = metroBg,
            accentColor = getProperPrimaryColor(),
            onSeek = { positionMs -> withPlayer { seekTo(positionMs) } }
        ).also { lyricsPanel = it }

        withPlayer {
            panel.show(track, currentPosition)
        }
    }

    private fun showArtworkSearch(track: Track) {
        val title = editedInfo[track.mediaStoreId]?.first ?: track.title
        val artist = (editedInfo[track.mediaStoreId]?.second ?: track.artist)
            .takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }.orEmpty()
        val album = track.album.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }.orEmpty()

        ArtworkSearchDialog(
            activity = this,
            songQuery = "$artist $title".trim(),
            albumQuery = "$artist $album".trim(),
            textColor = metroText,
            bgColor = metroBg,
            accentColor = getProperPrimaryColor(),
            onPicked = { bitmap ->
                pendingCover = bitmap
                coverPreview?.setImageBitmap(bitmap)
            }
        ).show()
    }

    // Windows Phone style: the big title and the section header slide in from the right,
    // one after the other, decelerating into place.
    private fun playMetroEntrance() {
        val distance = resources.displayMetrics.density * 96f
        listOf(binding.metroTitle to 0L, binding.metroHeader to 90L).forEach { (view, delay) ->
            view.alpha = 0f
            view.translationX = distance
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(delay)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    private fun showSearchDialog() {
        ensureBackgroundThread {
            val allTracks = ArrayList(audioHelper.getAllTracks())
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                val textColor = getProperTextColor()
                val density = resources.displayMetrics.density
                val input = AppCompatEditText(this@TrackActivity).apply {
                    hint = getString(R.string.metro_search_hint)
                    setTextColor(textColor)
                    setHintTextColor(ColorUtils.setAlphaComponent(textColor, 128))
                    setSingleLine()
                }

                var results: List<Track> = emptyList()
                val adapter = object : ArrayAdapter<String>(
                    this@TrackActivity,
                    android.R.layout.simple_list_item_1,
                    ArrayList<String>()
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as? TextView)?.setTextColor(textColor)
                        return view
                    }
                }
                val list = ListView(this@TrackActivity)
                list.adapter = adapter

                input.doAfterTextChanged { text ->
                    val query = text?.toString().orEmpty().trim().lowercase()
                    results = if (query.isEmpty()) {
                        emptyList()
                    } else {
                        allTracks.filter {
                            it.title.lowercase().contains(query) ||
                                it.artist.lowercase().contains(query) ||
                                it.album.lowercase().contains(query)
                        }.take(100)
                    }
                    adapter.clear()
                    adapter.addAll(results.map { "${it.title} • ${it.artist}" })
                }

                val container = LinearLayout(this@TrackActivity)
                container.orientation = LinearLayout.VERTICAL
                val pad = (density * 20).toInt()
                container.setPadding(pad, pad / 2, pad, 0)
                container.addView(
                    input,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                container.addView(
                    list,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (density * 320).toInt()
                    )
                )

                var dialog: AlertDialog? = null
                list.setOnItemClickListener { _, _, position, _ ->
                    val picked = results.getOrNull(position) ?: return@setOnItemClickListener
                    val index = allTracks.indexOfFirst { it.mediaStoreId == picked.mediaStoreId }
                    dialog?.dismiss()
                    if (index >= 0) {
                        prepareAndPlay(allTracks, startIndex = index, startActivity = false)
                    }
                }

                getAlertDialogBuilder()
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .apply {
                        setupDialogStuff(container, this, R.string.metro_search) { alertDialog ->
                            dialog = alertDialog
                            alertDialog.showKeyboard(input)
                        }
                    }
            }
        }
    }

    private fun showEditDialog() {
        val track = PlaybackService.currentMediaItem?.toTrack() ?: return
        pendingCover = null

        val dialogBinding = DialogMetroEditBinding.inflate(layoutInflater)
        coverPreview = dialogBinding.metroEditCover
        dialogBinding.metroEditCover.setImageDrawable(binding.activityTrackImage.drawable)
        dialogBinding.metroEditCoverButton.setTextColor(getProperPrimaryColor())
        dialogBinding.metroEditCoverButton.setOnClickListener { pickCover.launch("image/*") }
        dialogBinding.metroEditCoverSearchButton.setTextColor(getProperPrimaryColor())
        dialogBinding.metroEditCoverSearchButton.setOnClickListener { showArtworkSearch(track) }
        dialogBinding.metroEditTitleInput.setText(editedInfo[track.mediaStoreId]?.first ?: track.title)
        dialogBinding.metroEditDescriptionInput.setText(editedInfo[track.mediaStoreId]?.second ?: track.artist)

        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(dialogBinding.root, this, R.string.metro_edit) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        saveEdits(
                            track = track,
                            newTitle = dialogBinding.metroEditTitleInput.value.trim(),
                            newArtist = dialogBinding.metroEditDescriptionInput.value.trim(),
                            dialog = alertDialog
                        )
                    }
                }
            }
    }

    private fun saveEdits(track: Track, newTitle: String, newArtist: String, dialog: AlertDialog) {
        if (newTitle.isEmpty()) {
            toast(R.string.metro_edit_empty)
            return
        }

        val shownTitle = editedInfo[track.mediaStoreId]?.first ?: track.title
        val shownArtist = editedInfo[track.mediaStoreId]?.second ?: track.artist
        val textChanged = newTitle != shownTitle || newArtist != shownArtist
        val newCover = pendingCover
        if (!textChanged && newCover == null) {
            dialog.dismiss()
            return
        }

        if (textChanged && !tagHelper.isEditTagSupported(track)) {
            toast(R.string.metro_edit_unsupported)
            return
        }

        val onDone = {
            runOnUiThread {
                if (textChanged) {
                    editedInfo[track.mediaStoreId] = newTitle to newArtist
                    binding.activityTrackTitle.text = newTitle
                    binding.activityTrackArtist.text = newArtist
                }
                if (newCover != null) {
                    setupTopArt(track)
                }
                pendingCover = null
                toast(R.string.metro_edit_saved)
                dialog.dismiss()
            }
        }

        ensureBackgroundThread {
            try {
                if (newCover != null) {
                    saveCustomCover(track, newCover)
                }

                if (textChanged) {
                    val edited = track.copy(title = newTitle, artist = newArtist)
                    handleRecoverableSecurityException { granted ->
                        if (granted) {
                            try {
                                tagHelper.writeTag(edited, newArtist, newTitle, edited.album)
                                audioHelper.updateTrackInfo(edited.path, newArtist, newTitle, edited.path)
                                onDone()
                            } catch (e: Exception) {
                                runOnUiThread { toast(org.fossify.commons.R.string.unknown_error_occurred) }
                            }
                        }
                    }
                } else {
                    onDone()
                }
            } catch (e: Exception) {
                runOnUiThread { toast(org.fossify.commons.R.string.unknown_error_occurred) }
            }
        }
    }

    // Custom covers live in the app's private storage and take priority over the file's own art
    // (see getTrackCoverArt). The audio file itself is not modified.
    private fun saveCustomCover(track: Track, bitmap: Bitmap) {
        val dir = File(filesDir, "custom_covers")
        dir.mkdirs()
        File(dir, "${track.mediaStoreId}.jpg").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
    }

    private fun onCoverPicked(uri: Uri) {
        ensureBackgroundThread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) {
                    sample *= 2
                }

                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: throw IllegalStateException("Could not decode image")

                val longest = maxOf(decoded.width, decoded.height)
                val bitmap = if (longest > 1024) {
                    val ratio = 1024f / longest
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * ratio).toInt(),
                        (decoded.height * ratio).toInt(),
                        true
                    )
                } else {
                    decoded
                }

                runOnUiThread {
                    pendingCover = bitmap
                    coverPreview?.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                runOnUiThread { toast(org.fossify.commons.R.string.unknown_error_occurred) }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFlingListener() {
        val flingListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    if (velocityY > 0 && velocityY > velocityX && e2.y - e1.y > SWIPE_DOWN_THRESHOLD) {
                        finish()
                        binding.activityTrackTopShadow.animate().alpha(0f).start()
                        overridePendingTransition(0, org.fossify.commons.R.anim.slide_down)
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        }

        val gestureDetector = GestureDetectorCompat(this, flingListener)
        binding.activityTrackHolder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun toggleShuffle() {
        val isShuffleEnabled = !config.isShuffleEnabled
        config.isShuffleEnabled = isShuffleEnabled
        toast(if (isShuffleEnabled) R.string.shuffle_enabled else R.string.shuffle_disabled)
        setupShuffleButton()
        withPlayer {
            shuffleModeEnabled = config.isShuffleEnabled
            setupNextTrackInfo(nextMediaItem)
        }
    }

    private fun setupShuffleButton(isShuffleEnabled: Boolean = config.isShuffleEnabled) {
        binding.activityTrackToggleShuffle.apply {
            applyColorFilter(if (isShuffleEnabled) getProperPrimaryColor() else metroText)
            alpha = if (isShuffleEnabled) 1f else MEDIUM_ALPHA
            contentDescription =
                getString(if (isShuffleEnabled) R.string.disable_shuffle else R.string.enable_shuffle)
        }
    }

    private fun seekBack() {
        binding.activityTrackProgressbar.progress += -SEEK_INTERVAL_S
        withPlayer { seekBack() }
    }

    private fun seekForward() {
        binding.activityTrackProgressbar.progress += SEEK_INTERVAL_S
        withPlayer { seekForward() }
    }

    private fun togglePlaybackSetting() {
        val newPlaybackSetting = config.playbackSetting.nextPlaybackOption
        config.playbackSetting = newPlaybackSetting
        toast(newPlaybackSetting.descriptionStringRes)
        setupPlaybackSettingButton()
        withPlayer {
            setRepeatMode(newPlaybackSetting)
        }
    }

    private fun maybeUpdatePlaybackSettingButton(playbackSetting: PlaybackSetting) {
        if (config.playbackSetting != PlaybackSetting.STOP_AFTER_CURRENT_TRACK) {
            setupPlaybackSettingButton(playbackSetting)
        }
    }

    private fun setupPlaybackSettingButton(playbackSetting: PlaybackSetting = config.playbackSetting) {
        binding.activityTrackPlaybackSetting.apply {
            contentDescription = getString(playbackSetting.contentDescriptionStringRes)
            setImageResource(playbackSetting.iconRes)

            val isRepeatOff = playbackSetting == PlaybackSetting.REPEAT_OFF

            alpha = if (isRepeatOff) MEDIUM_ALPHA else 1f
            applyColorFilter(if (isRepeatOff) metroText else getProperPrimaryColor())
        }
    }

    private fun setupSeekbar() {
        binding.activityTrackSpeedIcon.applyColorFilter(metroText)
        updatePlaybackSpeed(config.playbackSpeed)

        binding.activityTrackProgressbar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val formattedProgress = progress.getFormattedDuration()
                    binding.activityTrackProgressCurrent.text = formattedProgress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) = withPlayer {
                    seekTo(seekBar.progress * 1000L)
                }
            })
    }

    private fun showPlaybackSpeedPicker() {
        val fragment = PlaybackSpeedFragment()
        fragment.show(supportFragmentManager, PlaybackSpeedFragment::class.java.simpleName)
        fragment.setListener(this)
    }

    override fun updatePlaybackSpeed(speed: Float) {
        val isSlow = speed < 1f
        if (isSlow != binding.activityTrackSpeed.tag as? Boolean) {
            binding.activityTrackSpeed.tag = isSlow

            val drawableId =
                if (isSlow) R.drawable.ic_playback_speed_slow_vector else R.drawable.ic_playback_speed_vector
            binding.activityTrackSpeedIcon.setImageDrawable(resources.getDrawable(drawableId))
        }

        @SuppressLint("SetTextI18n")
        binding.activityTrackSpeed.text = "${DecimalFormat("#.##").format(speed)}x"
        withPlayer {
            setPlaybackSpeed(speed)
        }
    }

    private fun getResizedDrawable(drawable: Drawable, wantedHeight: Int): Drawable {
        val bitmap = (drawable as BitmapDrawable).bitmap
        val bitmapResized = bitmap.scale(wantedHeight, wantedHeight, false)
        return bitmapResized.toDrawable(resources)
    }

    override fun onPlaybackStateChanged(playbackState: Int) = updatePlayerState()

    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayerState()

    override fun onRepeatModeChanged(repeatMode: Int) {
        maybeUpdatePlaybackSettingButton(getPlaybackSetting(repeatMode))
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        setupShuffleButton(shuffleModeEnabled)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        if (mediaItem == null) {
            finish()
        } else {
            binding.activityTrackProgressbar.progress = 0
            updateTrackInfo()
            currentTrackWithEdits()?.let { lyricsPanel?.onTrackChanged(it) }
        }
    }

    private fun updateTrackInfo() {
        withPlayer {
            setupTrackInfo(currentMediaItem)
            setupNextTrackInfo(nextMediaItem)
        }
    }

    private fun updatePlayerState() {
        withPlayer {
            val isPlaying = isReallyPlaying
            if (isPlaying) {
                scheduleProgressUpdate()
            } else {
                cancelProgressUpdate()
            }

            updateProgress(currentPosition)
            updatePlayPause(isPlaying)
            setupShuffleButton(shuffleModeEnabled)
            maybeUpdatePlaybackSettingButton(getPlaybackSetting(repeatMode))
        }
    }

    private fun scheduleProgressUpdate() {
        cancelProgressUpdate()
        withPlayer {
            val delayInMillis = (UPDATE_INTERVAL_MS / config.playbackSpeed).toLong()
            handler.postDelayed(delayInMillis = delayInMillis) {
                updateProgress(currentPosition)
                scheduleProgressUpdate()
            }
        }
    }

    private fun cancelProgressUpdate() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateProgress(currentPosition: Long) {
        lyricsPanel?.onPosition(currentPosition)
        binding.activityTrackProgressbar.progress =
            currentPosition.milliseconds.inWholeSeconds.toInt()
    }

    private fun updatePlayPause(isPlaying: Boolean) {
        binding.activityTrackPlayPause.updatePlayPauseIcon(isPlaying, metroText)
    }

    private fun seekToNext() {
        seekCount += 1
        seekWithDelay()
    }

    private fun seekToPrevious() {
        withPlayer {
            if (maybeRestartOnPrevious()) return@withPlayer
            seekCount -= 1
            seekWithDelay()
        }
    }

    /**
     * This is here so the player can quickly seek next/previous without doing too much work.
     * It probably won't be needed once https://github.com/androidx/media/issues/81 is resolved.
     */
    private fun seekWithDelay() {
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(timeMillis = SEEK_COALESCE_INTERVAL_MS)
            if (seekCount != 0) {
                seekByCount(seekCount)
            }
        }
    }

    private fun seekByCount(count: Int) {
        withPlayer {
            if (currentMediaItem == null) {
                return@withPlayer
            }

            val currentIndex = currentMediaItemIndex
            val mediaItemCount = mediaItemCount
            val seekIndex = if (shuffleModeEnabled) {
                val shuffledIndex = shuffledMediaItemsIndices.indexOf(currentIndex)
                val seekIndex = rotateIndex(mediaItemCount, shuffledIndex + count)
                shuffledMediaItemsIndices.getOrNull(seekIndex) ?: return@withPlayer
            } else {
                rotateIndex(mediaItemCount, currentIndex + count)
            }

            play()
            seekTo(seekIndex, 0)
            seekCount = 0
        }
    }

    private fun rotateIndex(total: Int, index: Int): Int {
        return (index % total + total) % total
    }
}
