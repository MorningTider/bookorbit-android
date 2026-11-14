package com.bookorbit.feature.reader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.bookorbit.R
import com.bookorbit.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground media controls for speech while this book remains open, including screen off. */
// Android Auto discovery and voice search are handled by PlaybackService.
// This private media session controls speech for the currently open book.
@SuppressLint("MissingOnPlayFromSearch")
@AndroidEntryPoint
internal class ReaderSpeechService : Service() {
    @Inject lateinit var speech: ReaderSpeechPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var media: MediaSession

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                speech.pause()
            }
        }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.speech_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        ContextCompat.registerReceiver(
            this,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        media =
            MediaSession(this, "BookOrbitSpeech").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() = speech.resume()

                        override fun onPause() = speech.pause()

                        override fun onStop() = speech.stop()
                    }
                )
                setSessionActivity(openApp())
                isActive = true
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 15 audio focus requires a visible activity or an established foreground service.
        startForeground(NOTIFICATION, notification(speech.state.value))
        when (intent?.action) {
            "pause" -> speech.pause()
            "stop" -> speech.stop()
            else -> speech.resume()
        }
        if (!observing) {
            observing = true
            scope.launch {
                speech.state.collect { state ->
                    if (!state.active) {
                        stopSelf()
                        return@collect
                    }
                    media.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapter)
                            .build()
                    )
                    media.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(
                                PlaybackState.ACTION_PLAY or
                                    PlaybackState.ACTION_PAUSE or
                                    PlaybackState.ACTION_STOP or
                                    PlaybackState.ACTION_PLAY_PAUSE
                            )
                            .setState(
                                if (state.playing) PlaybackState.STATE_PLAYING
                                else PlaybackState.STATE_PAUSED,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                                state.rate,
                            )
                            .build()
                    )
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION, notification(state))
                }
            }
        }
        return START_NOT_STICKY
    }

    private var observing = false

    private fun openApp() =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(name: String) =
        PendingIntent.getService(
            this,
            name.hashCode(),
            Intent(this, ReaderSpeechService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notification(state: SpeechState): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle(state.title.ifBlank { getString(R.string.speech_title) })
            .setContentText(state.chapter)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
            .setDeleteIntent(action("stop"))
            .setOngoing(state.playing)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(media.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(
                Notification.Action.Builder(
                        Icon.createWithResource(
                            this,
                            if (state.playing) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play,
                        ),
                        getString(
                            if (state.playing) R.string.speech_pause else R.string.speech_resume
                        ),
                        action(if (state.playing) "pause" else "play"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        Icon.createWithResource(
                            this,
                            android.R.drawable.ic_menu_close_clear_cancel,
                        ),
                        getString(R.string.speech_stop),
                        action("stop"),
                    )
                    .build()
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        speech.stop()
        unregisterReceiver(noisy)
        media.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        speech.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "reader-speech"
        private const val NOTIFICATION = 834
    }
}
