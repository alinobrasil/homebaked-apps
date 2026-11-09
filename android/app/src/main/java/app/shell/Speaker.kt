package app.shell

import android.content.Context
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.util.Locale

/**
 * Speech for mini-apps.
 *
 * Android System WebView does not implement the synthesis half of the Web
 * Speech API. A mini-app calling speechSynthesis.speak() gets an object that
 * accepts the call and stays silent, even on a phone whose own text to speech
 * works fine in Settings. There is no way around that from inside the page,
 * so the shell hands the platform engine across the bridge instead.
 *
 * One instance per WebAppActivity. The engine starts asynchronously, so the
 * first utterance usually arrives before it is ready; that one is held and
 * replayed on init rather than dropped, which is what makes an intro card
 * speak on the very first open.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    @Volatile private var ready = false
    @Volatile private var initError = false
    @Volatile private var lastLang = ""
    @Volatile private var pending: Triple<String, String, Float>? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                val held = pending
                pending = null
                if (held != null) speak(held.first, held.second, held.third)
            } else {
                initError = true
            }
        }
    }

    fun speak(text: String, lang: String, rate: Float) {
        if (text.isEmpty() || initError) return
        val engine = tts ?: return

        if (!ready) { pending = Triple(text, lang, rate); return }

        try {
            if (lang != lastLang) {
                engine.language = localeOf(lang)
                lastLang = lang
            }
            engine.setSpeechRate(if (rate <= 0f) 1f else rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            initError = true
        }
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) { }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) { }
        tts = null
        ready = false
    }

    /**
     * JSON a mini-app can read to decide whether this path is usable, and to
     * show the user something specific when it is not.
     *
     * langStatus mirrors TextToSpeech.LANG_*: -2 not supported, -1 missing
     * data, 0 or better means the language is there.
     */
    fun status(lang: String): String {
        val engine = tts
        val o = JSONObject()
        o.put("ready", ready)
        o.put("error", initError)
        o.put("lang", lang)

        var langStatus = TextToSpeech.LANG_NOT_SUPPORTED
        if (engine != null && ready) {
            langStatus = try {
                engine.isLanguageAvailable(localeOf(lang))
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            o.put("engine", engine.defaultEngine ?: "")
        }
        o.put("langStatus", langStatus)
        o.put("langSupported", langStatus >= TextToSpeech.LANG_AVAILABLE)
        return o.toString()
    }

    private fun localeOf(tag: String): Locale =
        try {
            if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        } catch (e: Exception) {
            Locale.getDefault()
        }

    private companion object {
        const val UTTERANCE_ID = "miniapp"
    }
}
