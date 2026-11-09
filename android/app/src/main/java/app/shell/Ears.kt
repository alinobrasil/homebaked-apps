package app.shell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Listening, for mini-apps that ask the user to say something back.
 *
 * On-device recognition only. Android's default SpeechRecognizer usually streams the
 * microphone to a cloud service, which is exactly what this app promises not to do, so
 * phones without an on-device recognizer (Android 11 and older, or no language pack)
 * report "unavailable" instead of quietly falling back to the cloud.
 *
 * The recognizer must be created and driven from the main thread. Every exit path reports
 * exactly once, so a mini-app can leave its microphone button spinning until it hears
 * back and never get stuck.
 */
class Ears(
    private val activity: Activity,
    private val emit: (String) -> Unit
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var live = false

    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try { SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) } catch (e: Exception) { false }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun status(): String = JSONObject()
        .put("available", available())
        .put("onDevice", true)
        .put("permission", hasPermission())
        .toString()

    /** Main thread only. */
    fun listen(lang: String) {
        if (live) return
        if (!available() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { fail("unavailable"); return }
        if (!hasPermission()) { fail("permission"); return }

        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                recognizer?.setRecognitionListener(this)
            }
            val tag = lang.ifEmpty { Locale.getDefault().toLanguageTag() }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // Slow or hesitant speakers leave long gaps; do not cut them off mid word.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            live = true
            recognizer?.startListening(i)
        } catch (e: Exception) {
            live = false
            fail("start")
        }
    }

    fun stop() {
        live = false
        try { recognizer?.cancel() } catch (e: Exception) { }
    }

    fun shutdown() {
        live = false
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
    }

    private fun fail(reason: String) {
        emit(JSONObject().put("ok", false).put("error", reason).toString())
    }

    // ------------------------------------------------------ RecognitionListener

    override fun onResults(results: Bundle?) {
        live = false
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val arr = JSONArray()
        if (list != null) for (s in list) arr.put(s)
        emit(JSONObject().put("ok", true).put("matches", arr).toString())
    }

    override fun onError(error: Int) {
        live = false
        val reason = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "nomatch"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "silence"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            // 12 and 13: the on-device model for this language is not installed.
            12, 13 -> "language"
            else -> "error$error"
        }
        // Nothing heard is a normal outcome, not a failure, so it comes back as an empty
        // success and the page can just say try again.
        if (reason == "nomatch" || reason == "silence") {
            emit(JSONObject().put("ok", true).put("matches", JSONArray()).toString())
        } else {
            fail(reason)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) { }
    override fun onBeginningOfSpeech() { }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() { }
    override fun onPartialResults(partialResults: Bundle?) { }
    override fun onEvent(eventType: Int, params: Bundle?) { }
}
