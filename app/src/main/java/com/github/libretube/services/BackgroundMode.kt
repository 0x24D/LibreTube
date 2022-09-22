package com.github.libretube.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.libretube.R
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.constants.BACKGROUND_CHANNEL_ID
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PLAYER_NOTIFICATION_ID
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.query
import com.github.libretube.extensions.toID
import com.github.libretube.util.*
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Loads the selected videos audio in background mode with a notification area.
 */
class BackgroundMode : Service() {
    /**
     * VideoId of the video
     */
    private lateinit var videoId: String

    /**
     *PlaylistId for autoplay
     */
    private var playlistId: String? = null

    /**
     * The response that gets when called the Api.
     */
    private var streams: com.github.libretube.api.obj.Streams? = null

    /**
     * The [ExoPlayer] player. Followed tutorial [here](https://developer.android.com/codelabs/exoplayer-intro)
     */
    private var player: ExoPlayer? = null
    private var playWhenReadyPlayer = true

    /**
     * The [AudioAttributes] handle the audio focus of the [player]
     */
    private lateinit var audioAttributes: AudioAttributes

    /**
     * SponsorBlock Segment data
     */
    private var segmentData: com.github.libretube.api.obj.Segments? = null

    /**
     * [Notification] for the player
     */
    private lateinit var nowPlayingNotification: NowPlayingNotification

    /**
     * The [videoId] of the next stream for autoplay
     */
    private var nextStreamId: String? = null

    /**
     * Helper for finding the next video in the playlist
     */
    private lateinit var autoPlayHelper: AutoPlayHelper

    /**
     * Autoplay Preference
     */
    private val autoplay = PreferenceHelper.getBoolean(PreferenceKeys.AUTO_PLAY, true)

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Setting the required [Notification] for running as a foreground service
     */
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channelId = BACKGROUND_CHANNEL_ID
            val channel = NotificationChannel(
                channelId,
                "Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            val notification: Notification = Notification.Builder(this, channelId)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.playingOnBackground)).build()
            startForeground(PLAYER_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Initializes the [player] with the [MediaItem].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // clear the playing queue
            PlayingQueue.clear()

            // get the intent arguments
            videoId = intent?.getStringExtra(IntentData.videoId)!!
            playlistId = intent.getStringExtra(IntentData.playlistId)
            val position = intent.getLongExtra(IntentData.position, 0L)

            // initialize the playlist autoPlay Helper
            autoPlayHelper = AutoPlayHelper(playlistId)

            // play the audio in the background
            loadAudio(videoId, position)
        } catch (e: Exception) {
            onDestroy()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Gets the video data and prepares the [player].
     */
    private fun loadAudio(
        videoId: String,
        seekToPosition: Long = 0
    ) {
        // append the video to the playing queue
        PlayingQueue.add(videoId)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                streams = RetrofitInstance.api.getStreams(videoId)
            } catch (e: Exception) {
                return@launch
            }

            handler.post {
                playAudio(seekToPosition)
            }
        }
    }

    private fun playAudio(
        seekToPosition: Long
    ) {
        PlayingQueue.updateCurrent(videoId)

        initializePlayer()
        setMediaItem()

        // create the notification
        if (!this@BackgroundMode::nowPlayingNotification.isInitialized) {
            nowPlayingNotification = NowPlayingNotification(this@BackgroundMode, player!!)
        }
        nowPlayingNotification.updatePlayerNotification(streams!!)

        player?.apply {
            playWhenReady = playWhenReadyPlayer
            prepare()
        }

        // seek to the previous position if available
        if (seekToPosition != 0L) player?.seekTo(seekToPosition)

        // set the playback speed
        val playbackSpeed = PreferenceHelper.getString(
            PreferenceKeys.BACKGROUND_PLAYBACK_SPEED,
            "1"
        ).toFloat()
        player?.setPlaybackSpeed(playbackSpeed)

        fetchSponsorBlockSegments()

        if (autoplay) setNextStream()
    }

    /**
     * create the player
     */
    private fun initializePlayer() {
        if (player != null) return

        audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, true)
            .build()

        /**
         * Listens for changed playbackStates (e.g. pause, end)
         * Plays the next video when the current one ended
         */
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(@Player.State state: Int) {
                when (state) {
                    Player.STATE_ENDED -> {
                        if (autoplay) playNextVideo()
                    }
                    Player.STATE_IDLE -> {
                        onDestroy()
                    }
                    PlaybackState.STATE_PAUSED -> {
                        query {
                            DatabaseHelper.saveWatchPosition(
                                videoId,
                                player?.currentPosition ?: 0L
                            )
                        }
                    }
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_READY -> {}
                }
            }
        })
    }

    /**
     * set the videoId of the next stream for autoplay
     */
    private fun setNextStream() {
        if (streams!!.relatedStreams!!.isNotEmpty()) {
            nextStreamId = streams?.relatedStreams!![0].url!!.toID()
        }

        if (playlistId == null) return
        if (!this::autoPlayHelper.isInitialized) autoPlayHelper = AutoPlayHelper(playlistId!!)
        // search for the next videoId in the playlist
        CoroutineScope(Dispatchers.IO).launch {
            nextStreamId = autoPlayHelper.getNextVideoId(videoId, streams!!.relatedStreams!!)
        }
    }

    /**
     * Plays the first related video to the current (used when the playback of the current video ended)
     */
    private fun playNextVideo() {
        if (nextStreamId == null || nextStreamId == videoId) return
        val nextQueueVideo = PlayingQueue.getNext()
        if (nextQueueVideo != null) nextStreamId = nextQueueVideo

        // play new video on background
        this.videoId = nextStreamId!!
        this.segmentData = null
        loadAudio(videoId)
    }

    /**
     * Sets the [MediaItem] with the [streams] into the [player]
     */
    private fun setMediaItem() {
        streams?.let {
            val uri = if (streams!!.hls != null) {
                streams!!.hls
            } else if (streams!!.audioStreams!!.isNotEmpty()) {
                PlayerHelper.getAudioSource(
                    this,
                    streams!!.audioStreams!!
                )
            } else {
                return
            }
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .build()
            player?.setMediaItem(mediaItem)
        }
    }

    /**
     * fetch the segments for SponsorBlock
     */
    private fun fetchSponsorBlockSegments() {
        CoroutineScope(Dispatchers.IO).launch {
            kotlin.runCatching {
                val categories = PlayerHelper.getSponsorBlockCategories()
                if (categories.size > 0) {
                    segmentData =
                        RetrofitInstance.api.getSegments(
                            videoId,
                            ObjectMapper().writeValueAsString(categories)
                        )
                    checkForSegments()
                }
            }
        }
    }

    /**
     * check for SponsorBlock segments
     */
    private fun checkForSegments() {
        Handler(Looper.getMainLooper()).postDelayed(this::checkForSegments, 100)

        if (segmentData == null || segmentData!!.segments.isEmpty()) return

        segmentData!!.segments.forEach { segment: com.github.libretube.api.obj.Segment ->
            val segmentStart = (segment.segment!![0] * 1000f).toLong()
            val segmentEnd = (segment.segment[1] * 1000f).toLong()
            val currentPosition = player?.currentPosition
            if (currentPosition in segmentStart until segmentEnd) {
                if (PreferenceHelper.getBoolean(
                        "sb_notifications_key",
                        true
                    )
                ) {
                    try {
                        Toast.makeText(this, R.string.segment_skipped, Toast.LENGTH_SHORT)
                            .show()
                    } catch (e: Exception) {
                        // Do nothing.
                    }
                }
                player?.seekTo(segmentEnd)
            }
        }
    }

    /**
     * destroy the [BackgroundMode] foreground service
     */
    override fun onDestroy() {
        // clear the playing queue
        PlayingQueue.clear()

        // called when the user pressed stop in the notification
        // stop the service from being in the foreground and remove the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // destroy the service
        stopSelf()
        if (this::nowPlayingNotification.isInitialized) nowPlayingNotification.destroy()
        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
