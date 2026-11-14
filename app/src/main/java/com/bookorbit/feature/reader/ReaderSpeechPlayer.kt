package com.bookorbit.feature.reader

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.bookorbit.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class SpeechVoice(val name: String, val label: String, val network: Boolean)

internal data class SpeechState(
    val ready: Boolean = false,
    val preparing: Boolean = false,
    val active: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val chapter: String = "",
    val rate: Float = 1f,
    val voice: String = "",
    val voices: List<SpeechVoice> = emptyList(),
    val sleepMinutes: Int = 0,
    val error: Int? = null,
)

/** Owns one reader's speech, invalidating callbacks from stopped or replaced utterances. */
@Singleton
internal class ReaderSpeechPlayer
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("reader_speech", Context.MODE_PRIVATE)
    private val _state =
        MutableStateFlow(
            SpeechState(
                rate = prefs.getFloat("rate", 1f).coerceIn(.5f, 2f),
                voice = prefs.getString("voice", "").orEmpty(),
            )
        )
    val state = _state.asStateFlow()
    private var engine: TextToSpeech? = null
    private var engineGeneration = 0
    private var owner: Any? = null
    private var command: ((String, Long) -> Unit)? = null
    private var session = 0L
    private var serial = 0L
    private var utterance: String? = null
    private var pending: ReaderEvent.Speech? = null
    private var pendingOffset = 0
    private var utteranceOffset = 0
    private var resumeOnFocus = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wake =
        context
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BookOrbit:ReaderSpeech")
            .apply { setReferenceCounted(false) }
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) resume()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            val wasPlaying = state.value.playing
                            pause(releaseFocus = false)
                            resumeOnFocus = wasPlaying
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                    }
                },
                main,
            )
            .build()
    private val expire = Runnable {
        pause()
        setSleep(0)
    }
    private val waiting = Runnable { fail(R.string.speech_reader_unavailable) }

    fun attach(token: Any, title: String, send: (String, Long) -> Unit) {
        if (owner !== token) stop()
        owner = token
        command = send
        _state.update { it.copy(title = title) }
    }

    fun detach(token: Any) {
        if (owner !== token) return
        stop()
        owner = null
        command = null
        releaseEngine()
    }

    fun reload() {
        stop()
        releaseEngine()
        prepare()
    }

    private fun releaseEngine() {
        engineGeneration++
        engine?.shutdown()
        engine = null
        _state.update { it.copy(ready = false, preparing = false, voices = emptyList()) }
    }

    fun prepare() {
        if (engine != null) return
        _state.update { it.copy(preparing = true, error = null) }
        val generation = ++engineGeneration
        engine =
            TextToSpeech(context) { status ->
                main.post {
                    if (engine == null || generation != engineGeneration) return@post
                    if (status != TextToSpeech.SUCCESS) {
                        engine?.shutdown()
                        engine = null
                        fail(R.string.speech_engine_missing)
                        _state.update { it.copy(preparing = false) }
                        return@post
                    }
                    val tts = engine ?: return@post
                    tts.setAudioAttributes(attributes)
                    tts.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(id: String?) = Unit

                            override fun onRangeStart(
                                id: String?,
                                start: Int,
                                end: Int,
                                frame: Int,
                            ) {
                                main.post {
                                    if (id == utterance) pendingOffset = utteranceOffset + start
                                }
                            }

                            override fun onDone(id: String?) {
                                main.post {
                                    if (id != utterance || !state.value.playing) return@post
                                    utterance = null
                                    pending = null
                                    request("next")
                                }
                            }

                            @Deprecated("Platform callback")
                            override fun onError(id: String?) = onError(id, TextToSpeech.ERROR)

                            override fun onError(id: String?, code: Int) {
                                main.post {
                                    if (id == utterance) fail(R.string.speech_playback_failed)
                                }
                            }
                        }
                    )
                    val voices =
                        tts.voices
                            .orEmpty()
                            .filterNot {
                                it.features?.contains(
                                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
                                ) == true
                            }
                            .sortedWith(
                                compareBy(
                                    { it.locale.language != Locale.getDefault().language },
                                    { it.name },
                                )
                            )
                            .map {
                                SpeechVoice(
                                    it.name,
                                    "${it.locale.getDisplayName(Locale.getDefault())} · ${it.name}",
                                    it.isNetworkConnectionRequired,
                                )
                            }
                    _state.update { it.copy(ready = true, preparing = false, voices = voices) }
                }
            }
    }

    /**
     * Started from a visible user action. The service establishes foreground status before focus.
     */
    fun start() {
        if (!state.value.ready || command == null) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReaderSpeechService::class.java),
            )
        } catch (_: IllegalStateException) {
            fail(R.string.speech_playback_failed)
        } catch (_: SecurityException) {
            fail(R.string.speech_playback_failed)
        }
    }

    fun updateTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    fun resume() {
        if (state.value.playing) return
        resumeOnFocus = false
        if (!state.value.ready || command == null) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(R.string.speech_audio_busy)
            return
        }
        wake.acquire(600_000)
        val fresh = !state.value.active
        _state.update { it.copy(active = true, playing = true, error = null) }
        if (fresh) {
            session++
            request("start")
        } else pending?.let(::speak) ?: request("next")
    }

    fun accept(event: ReaderEvent.Speech): Boolean {
        if (event.session != session || !state.value.active) return false
        main.removeCallbacks(waiting)
        when {
            event.error -> fail(R.string.speech_reader_unavailable)
            event.done -> stop()
            event.text.isNotBlank() -> {
                pending = event
                pendingOffset = 0
                _state.update { it.copy(chapter = event.chapter) }
                if (state.value.playing) speak(event)
            }
            else -> fail(R.string.speech_reader_unavailable)
        }
        return !event.error && (event.text.isNotBlank() || (event.done && event.cfi != null))
    }

    private fun request(action: String) {
        main.removeCallbacks(waiting)
        main.postDelayed(waiting, 30_000)
        command?.invoke(action, session)
    }

    private fun speak(event: ReaderEvent.Speech) {
        val tts = engine ?: return
        val selected = tts.voices.orEmpty().firstOrNull { it.name == state.value.voice }
        val voiceResult =
            if (selected != null) tts.setVoice(selected)
            else {
                val tag = event.language.substringBefore(',').trim().replace('_', '-')
                val locale =
                    if (tag.isBlank() || tag == "und") Locale.getDefault()
                    else Locale.forLanguageTag(tag)
                tts.setLanguage(locale)
            }
        if (voiceResult < 0) {
            fail(R.string.speech_language_missing)
            return
        }
        wake.acquire(600_000)
        tts.setSpeechRate(state.value.rate)
        val id = "$session:${++serial}"
        utterance = id
        utteranceOffset = pendingOffset.coerceIn(0, event.text.lastIndex)
        if (
            tts.speak(event.text.substring(utteranceOffset), TextToSpeech.QUEUE_FLUSH, null, id) !=
                TextToSpeech.SUCCESS
        )
            fail(R.string.speech_playback_failed)
    }

    fun pause(releaseFocus: Boolean = true) {
        resumeOnFocus = false
        utterance = null
        engine?.stop()
        _state.update { it.copy(playing = false) }
        if (releaseFocus) audio.abandonAudioFocusRequest(focus)
        if (wake.isHeld) wake.release()
    }

    fun stop() {
        command?.invoke("stop", session)
        session++
        pause()
        main.removeCallbacks(waiting)
        pending = null
        setSleep(0)
        _state.update { it.copy(active = false, chapter = "") }
    }

    private fun fail(message: Int) {
        stop()
        _state.update { it.copy(error = message) }
    }

    fun setRate(value: Float) {
        val rate = value.coerceIn(.5f, 2f)
        prefs.edit { putFloat("rate", rate) }
        _state.update { it.copy(rate = rate) }
        restartUtterance()
    }

    fun setVoice(name: String) {
        prefs.edit { putString("voice", name) }
        _state.update { it.copy(voice = name) }
        restartUtterance()
    }

    private fun restartUtterance() {
        if (state.value.playing) {
            utterance = null
            engine?.stop()
            pending?.let(::speak)
        }
    }

    fun setSleep(minutes: Int) {
        main.removeCallbacks(expire)
        val duration = minutes.coerceIn(0, 120)
        _state.update { it.copy(sleepMinutes = duration) }
        if (duration > 0) main.postAtTime(expire, SystemClock.uptimeMillis() + duration * 60_000L)
    }
}
