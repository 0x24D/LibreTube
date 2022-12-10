package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.github.libretube.R
import com.github.libretube.databinding.DoubleTapOverlayBinding
import com.github.libretube.databinding.ExoStyledPlayerControlViewBinding
import com.github.libretube.databinding.PlayerGestureControlsViewBinding
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.toPixel
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.interfaces.OnlinePlayerOptions
import com.github.libretube.ui.interfaces.PlayerGestureOptions
import com.github.libretube.ui.interfaces.PlayerOptions
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.PlaybackSpeedSheet
import com.github.libretube.util.AudioHelper
import com.github.libretube.util.BrightnessHelper
import com.github.libretube.util.PlayerGestureController
import com.github.libretube.util.PlayerHelper
import com.github.libretube.util.PlayingQueue
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.trackselection.TrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.ui.SubtitleView
import com.google.android.exoplayer2.util.RepeatModeUtil

@SuppressLint("ClickableViewAccessibility")
internal class CustomExoPlayerView(
    context: Context,
    attributeSet: AttributeSet? = null
) : StyledPlayerView(context, attributeSet), PlayerOptions, PlayerGestureOptions {
    val binding: ExoStyledPlayerControlViewBinding = ExoStyledPlayerControlViewBinding.bind(this)

    /**
     * Objects for player tap and swipe gesture
     */
    private lateinit var gestureViewBinding: PlayerGestureControlsViewBinding
    private lateinit var playerGestureController: PlayerGestureController
    private lateinit var brightnessHelper: BrightnessHelper
    private lateinit var audioHelper: AudioHelper
    private var doubleTapOverlayBinding: DoubleTapOverlayBinding? = null

    /**
     * Objects from the parent fragment
     */
    private var playerOptionsInterface: OnlinePlayerOptions? = null
    private var trackSelector: TrackSelector? = null

    private val runnableHandler = Handler(Looper.getMainLooper())

    var isPlayerLocked: Boolean = false

    /**
     * Preferences
     */
    var autoplayEnabled = PlayerHelper.autoPlayEnabled

    private var resizeModePref = PlayerHelper.resizeModePref

    private val supportFragmentManager
        get() = (context as BaseActivity).supportFragmentManager

    private fun toggleController() {
        if (isControllerFullyVisible) hideController() else showController()
    }

    // saved to only load the playback speed once (for the first video)
    private var playbackPrefSet = false

    fun initialize(
        playerViewInterface: OnlinePlayerOptions?,
        doubleTapOverlayBinding: DoubleTapOverlayBinding,
        playerGestureControlsViewBinding: PlayerGestureControlsViewBinding,
        trackSelector: TrackSelector?
    ) {
        this.playerOptionsInterface = playerViewInterface
        this.doubleTapOverlayBinding = doubleTapOverlayBinding
        this.trackSelector = trackSelector
        this.gestureViewBinding = playerGestureControlsViewBinding
        this.playerGestureController = PlayerGestureController(context as BaseActivity, this)
        this.brightnessHelper = BrightnessHelper(context as Activity)
        this.audioHelper = AudioHelper(context)

        // Set touch listener for tap and swipe gestures.
        setOnTouchListener(playerGestureController)
        initializeGestureProgress()

        initRewindAndForward()

        initializeAdvancedOptions(context)

        if (!playbackPrefSet) {
            player?.playbackParameters = PlaybackParameters(
                PlayerHelper.playbackSpeed.toFloat(),
                1.0f
            )
            playbackPrefSet = true
        }

        // locking the player
        binding.lockPlayer.setOnClickListener {
            // change the locked/unlocked icon
            binding.lockPlayer.setImageResource(
                if (!isPlayerLocked) {
                    R.drawable.ic_locked
                } else {
                    R.drawable.ic_unlocked
                }
            )

            // show/hide all the controls
            lockPlayer(isPlayerLocked)

            // change locked status
            isPlayerLocked = !isPlayerLocked
        }

        resizeMode = when (resizeModePref) {
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        binding.playPauseBTN.setOnClickListener {
            if (player?.isPlaying == false) {
                // start or go on playing
                if (player?.playbackState == Player.STATE_ENDED) {
                    // restart video if finished
                    player?.seekTo(0)
                }
                player?.play()
            } else {
                // pause the video
                player?.pause()
            }
        }

        player?.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                super.onEvents(player, events)
                if (events.containsAny(
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED
                    )
                ) {
                    updatePlayPauseButton()
                }
            }
        })
    }

    private fun updatePlayPauseButton() {
        if (player?.isPlaying == true) {
            // video is playing
            binding.playPauseBTN.setImageResource(R.drawable.ic_pause)
        } else if (player?.playbackState == Player.STATE_ENDED) {
            // video has finished
            binding.playPauseBTN.setImageResource(R.drawable.ic_restart)
        } else {
            // player in any other state
            binding.playPauseBTN.setImageResource(R.drawable.ic_play)
        }
    }

    override fun hideController() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // hide all the navigation bars that potentially could have been reopened manually ba the user
            (context as? MainActivity)?.setFullscreen()
        }
        super.hideController()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }

    private fun initRewindAndForward() {
        val seekIncrementText = (PlayerHelper.seekIncrement / 1000).toString()
        listOf(
            doubleTapOverlayBinding?.rewindTV,
            doubleTapOverlayBinding?.forwardTV,
            binding.forwardTV,
            binding.rewindTV
        ).forEach {
            it?.text = seekIncrementText
        }
        binding.forwardBTN.setOnClickListener {
            player?.seekTo(player!!.currentPosition + PlayerHelper.seekIncrement)
        }
        binding.rewindBTN.setOnClickListener {
            player?.seekTo(player!!.currentPosition - PlayerHelper.seekIncrement)
        }
        if (PlayerHelper.doubleTapToSeek) return

        listOf(binding.forwardBTN, binding.rewindBTN).forEach {
            it.visibility = View.VISIBLE
        }
    }

    private fun initializeAdvancedOptions(context: Context) {
        binding.toggleOptions.setOnClickListener {
            val items = mutableListOf(
                BottomSheetItem(
                    context.getString(R.string.player_autoplay),
                    R.drawable.ic_play,
                    {
                        if (autoplayEnabled) {
                            context.getString(R.string.enabled)
                        } else {
                            context.getString(R.string.disabled)
                        }
                    }
                ) {
                    onAutoplayClicked()
                },
                BottomSheetItem(
                    context.getString(R.string.repeat_mode),
                    R.drawable.ic_repeat,
                    {
                        if (player?.repeatMode == RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE) {
                            context.getString(R.string.repeat_mode_none)
                        } else {
                            context.getString(R.string.repeat_mode_current)
                        }
                    }
                ) {
                    onRepeatModeClicked()
                },
                BottomSheetItem(
                    context.getString(R.string.player_resize_mode),
                    R.drawable.ic_aspect_ratio,
                    {
                        when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> context.getString(R.string.resize_mode_fit)
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> context.getString(R.string.resize_mode_fill)
                            else -> context.getString(R.string.resize_mode_zoom)
                        }
                    }
                ) {
                    onResizeModeClicked()
                },
                BottomSheetItem(
                    context.getString(R.string.playback_speed),
                    R.drawable.ic_speed,
                    {
                        "${
                        player?.playbackParameters?.speed
                            .toString()
                            .replace(".0", "")
                        }x"
                    }
                ) {
                    onPlaybackSpeedClicked()
                }
            )

            if (playerOptionsInterface != null) {
                items.add(
                    BottomSheetItem(
                        context.getString(R.string.quality),
                        R.drawable.ic_hd,
                        { "${player?.videoSize?.height}p" }
                    ) {
                        playerOptionsInterface?.onQualityClicked()
                    }
                )
                items.add(
                    BottomSheetItem(
                        context.getString(R.string.audio_track),
                        R.drawable.ic_audio,
                        {
                            trackSelector?.parameters?.preferredAudioLanguages?.firstOrNull()
                        }
                    ) {
                        playerOptionsInterface?.onAudioStreamClicked()
                    }
                )
                items.add(
                    BottomSheetItem(
                        context.getString(R.string.captions),
                        R.drawable.ic_caption,
                        {
                            if (trackSelector != null && trackSelector!!.parameters.preferredTextLanguages.isNotEmpty()) {
                                trackSelector!!.parameters.preferredTextLanguages[0]
                            } else {
                                context.getString(R.string.none)
                            }
                        }
                    ) {
                        playerOptionsInterface?.onCaptionsClicked()
                    }
                )
            }

            val bottomSheetFragment = BaseBottomSheet().setItems(items, null)
            bottomSheetFragment.show(supportFragmentManager, null)
        }
    }

    // lock the player
    private fun lockPlayer(isLocked: Boolean) {
        // isLocked is the current (old) state of the player lock
        val visibility = if (isLocked) View.VISIBLE else View.GONE

        binding.exoTopBarRight.visibility = visibility
        binding.exoCenterControls.visibility = visibility
        binding.exoBottomBar.visibility = visibility
        binding.closeImageButton.visibility = visibility
        binding.exoTitle.visibility = visibility
        binding.playPauseBTN.visibility = visibility

        // disable tap and swipe gesture if the player is locked
        playerGestureController.isEnabled = isLocked
    }

    private fun rewind() {
        player?.seekTo((player?.currentPosition ?: 0L) - PlayerHelper.seekIncrement)

        // show the rewind button
        doubleTapOverlayBinding?.rewindBTN.apply {
            this!!.visibility = View.VISIBLE
            // clear previous animation
            this.animate().rotation(0F).setDuration(0).start()
            // start new animation
            this.animate()
                .rotation(-30F)
                .setDuration(100)
                .withEndAction {
                    // reset the animation when finished
                    animate().rotation(0F).setDuration(100).start()
                }
                .start()

            runnableHandler.removeCallbacks(hideRewindButtonRunnable)
            // start callback to hide the button
            runnableHandler.postDelayed(hideRewindButtonRunnable, 700)
        }
    }

    private fun forward() {
        player?.seekTo(player!!.currentPosition + PlayerHelper.seekIncrement)

        // show the forward button
        doubleTapOverlayBinding?.forwardBTN.apply {
            this!!.visibility = View.VISIBLE
            // clear previous animation
            this.animate().rotation(0F).setDuration(0).start()
            // start new animation
            this.animate()
                .rotation(30F)
                .setDuration(100)
                .withEndAction {
                    // reset the animation when finished
                    animate().rotation(0F).setDuration(100).start()
                }
                .start()

            // start callback to hide the button
            runnableHandler.removeCallbacks(hideForwardButtonRunnable)
            runnableHandler.postDelayed(hideForwardButtonRunnable, 700)
        }
    }

    private val hideForwardButtonRunnable = Runnable {
        doubleTapOverlayBinding?.forwardBTN.apply {
            this!!.visibility = View.GONE
        }
    }
    private val hideRewindButtonRunnable = Runnable {
        doubleTapOverlayBinding?.rewindBTN.apply {
            this!!.visibility = View.GONE
        }
    }

    private fun initializeGestureProgress() {
        gestureViewBinding.brightnessProgressBar.let { bar ->
            bar.progress =
                brightnessHelper.getBrightnessWithScale(bar.max.toFloat(), saved = true).toInt()
        }
        gestureViewBinding.volumeProgressBar.let { bar ->
            bar.progress = audioHelper.getVolumeWithScale(bar.max)
        }
    }

    private fun updateBrightness(distance: Float) {
        gestureViewBinding.brightnessControlView.visibility = View.VISIBLE
        val bar = gestureViewBinding.brightnessProgressBar

        if (bar.progress == 0) {
            // If brightness progress goes to below 0, set to system brightness
            if (distance <= 0) {
                brightnessHelper.resetToSystemBrightness()
                gestureViewBinding.brightnessImageView.setImageResource(R.drawable.ic_brightness_auto)
                gestureViewBinding.brightnessTextView.text = resources.getString(R.string.auto)
                return
            }
            gestureViewBinding.brightnessImageView.setImageResource(R.drawable.ic_brightness)
        }

        bar.incrementProgressBy(distance.toInt())
        gestureViewBinding.brightnessTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
        brightnessHelper.setBrightnessWithScale(bar.progress.toFloat(), bar.max.toFloat())
    }

    private fun updateVolume(distance: Float) {
        val bar = gestureViewBinding.volumeProgressBar
        gestureViewBinding.volumeControlView.apply {
            if (visibility == View.GONE) {
                visibility = View.VISIBLE
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = audioHelper.getVolumeWithScale(bar.max)
            }
        }

        if (bar.progress == 0) {
            gestureViewBinding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt())
        audioHelper.setVolumeWithScale(bar.progress, bar.max)

        gestureViewBinding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    override fun onAutoplayClicked() {
        // autoplay options dialog
        BaseBottomSheet()
            .setSimpleItems(
                listOf(
                    context.getString(R.string.enabled),
                    context.getString(R.string.disabled)
                )
            ) { index ->
                when (index) {
                    0 -> autoplayEnabled = true
                    1 -> autoplayEnabled = false
                }
            }
            .show(supportFragmentManager)
    }

    override fun onPlaybackSpeedClicked() {
        player?.let { PlaybackSpeedSheet(it).show(supportFragmentManager) }
    }

    override fun onResizeModeClicked() {
        // switching between original aspect ratio (black bars) and zoomed to fill device screen
        val aspectRatioModeNames = context.resources?.getStringArray(R.array.resizeMode)
            ?.toList().orEmpty()

        val aspectRatioModes = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )

        BaseBottomSheet()
            .setSimpleItems(aspectRatioModeNames) { index ->
                resizeMode = aspectRatioModes[index]
            }
            .show(supportFragmentManager)
    }

    override fun onRepeatModeClicked() {
        val repeatModeNames = listOf(
            context.getString(R.string.repeat_mode_none),
            context.getString(R.string.repeat_mode_current),
            context.getString(R.string.all)
        )
        // repeat mode options dialog
        BaseBottomSheet()
            .setSimpleItems(repeatModeNames) { index ->
                PlayingQueue.repeatQueue = when (index) {
                    0 -> {
                        player?.repeatMode = Player.REPEAT_MODE_OFF
                        false
                    }
                    1 -> {
                        player?.repeatMode = Player.REPEAT_MODE_ONE
                        false
                    }
                    else -> true
                }
            }
            .show(supportFragmentManager)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)

        val offset = when (newConfig?.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> 20.toPixel()
            else -> 10.toPixel()
        }

        binding.progressBar.let {
            val params = it.layoutParams as MarginLayoutParams
            params.bottomMargin = offset.toInt()
            it.layoutParams = params
        }
    }

    override fun onSingleTap() {
        toggleController()
    }

    override fun onDoubleTapCenterScreen() {
        player?.let { player ->
            if (player.isPlaying) {
                player.pause()
                if (!isControllerFullyVisible) showController()
            } else {
                player.play()
                if (isControllerFullyVisible) hideController()
            }
        }
    }

    override fun onDoubleTapLeftScreen() {
        if (!PlayerHelper.doubleTapToSeek) return
        rewind()
    }

    override fun onDoubleTapRightScreen() {
        if (!PlayerHelper.doubleTapToSeek) return
        forward()
    }

    override fun onSwipeLeftScreen(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        if (isControllerFullyVisible) hideController()
        updateBrightness(distanceY)
    }

    override fun onSwipeRightScreen(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        if (isControllerFullyVisible) hideController()
        updateVolume(distanceY)
    }

    override fun onSwipeEnd() {
        gestureViewBinding.brightnessControlView.visibility = View.GONE
        gestureViewBinding.volumeControlView.visibility = View.GONE
    }

    override fun onZoom() {
        if (!PlayerHelper.pinchGestureEnabled) return
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
        }
    }

    override fun onMinimize() {
        if (!PlayerHelper.pinchGestureEnabled) return
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        subtitleView?.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
    }

    override fun onFullscreenChange(isFullscreen: Boolean) {
        if (PlayerHelper.swipeGestureEnabled && this::brightnessHelper.isInitialized) {
            if (isFullscreen) {
                brightnessHelper.restoreSavedBrightness()
                if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                    subtitleView?.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
                }
            } else {
                brightnessHelper.resetToSystemBrightness(false)
                subtitleView?.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            }
        }
    }

    companion object {
        private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.158f
    }
}
