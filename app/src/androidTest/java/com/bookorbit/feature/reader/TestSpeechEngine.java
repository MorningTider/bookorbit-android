package com.bookorbit.feature.reader;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Standalone test APK service uses only platform classes and synthesizes silence. */
public class TestSpeechEngine extends TextToSpeechService {
    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
    }
    @Override protected int onLoadLanguage(String language, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
    }
    @Override protected String[] onGetLanguage() { return new String[] {"zho", "CHN", ""}; }
    @Override protected void onStop() { }
    @Override public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice("test-zh", Locale.SIMPLIFIED_CHINESE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }
    @Override public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        return "test-zh";
    }
    @Override public int onIsValidVoiceName(String name) {
        return "test-zh".equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        byte[] silence = new byte[3200];
        for (int i = 0; i < 5; i++) callback.audioAvailable(silence, 0, silence.length);
        callback.done();
    }
}
