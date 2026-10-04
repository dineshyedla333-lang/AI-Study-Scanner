package com.aistudyscanner.agent.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Why a "Listen" request could not be served, in words a student understands. */
enum class SpeechFailure {
    /** The device has no voice data for that language installed. */
    LANGUAGE_UNAVAILABLE,

    /** Text-to-speech itself is missing or failed to start. */
    ENGINE_UNAVAILABLE,
}

/**
 * Reads a solution aloud in the language it was written in.
 *
 * One engine is kept for the whole process: TextToSpeech takes a noticeable
 * moment to initialise, and creating one per screen made the first tap of
 * "Listen" do nothing at all.
 */
object SpeechPlayer {
    private var tts: TextToSpeech? = null
    private var ready = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private const val UTTERANCE_ID = "solution"

    /** Locale per app language. Indian languages all use the IN region. */
    private fun localeFor(languageCode: String): Locale = when (languageCode) {
        "hi" -> Locale("hi", "IN")
        "te" -> Locale("te", "IN")
        "ta" -> Locale("ta", "IN")
        "kn" -> Locale("kn", "IN")
        "ml" -> Locale("ml", "IN")
        "mr" -> Locale("mr", "IN")
        "bn" -> Locale("bn", "IN")
        "gu" -> Locale("gu", "IN")
        else -> Locale.US
    }

    private fun ensureEngine(context: Context, onReady: (Boolean) -> Unit) {
        if (ready && tts != null) {
            onReady(true)
            return
        }
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        @Deprecated("Required by the base class")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }
                    },
                )
            }
            onReady(ready)
        }
    }

    /**
     * Speaks [markdown] in [languageCode]. The text is cleaned of Markdown and
     * LaTeX first — read verbatim, `\frac{1}{2}` is noise, not maths.
     *
     * [onFailure] fires when the device has no voice for that language, which
     * is common: most phones ship English and Hindi but not Telugu until the
     * user installs it.
     */
    fun speak(
        context: Context,
        markdown: String,
        languageCode: String,
        onFailure: (SpeechFailure) -> Unit = {},
    ) {
        val spoken = stripForSpeech(markdown)
        if (spoken.isBlank()) return

        ensureEngine(context) { ok ->
            val engine = tts
            if (!ok || engine == null) {
                onFailure(SpeechFailure.ENGINE_UNAVAILABLE)
                return@ensureEngine
            }
            val result = engine.setLanguage(localeFor(languageCode))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                onFailure(SpeechFailure.LANGUAGE_UNAVAILABLE)
                return@ensureEngine
            }
            engine.setSpeechRate(0.95f) // a touch slower: this is a lesson
            _isSpeaking.value = true
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /** Releases the engine. Called when the app's last activity goes away. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _isSpeaking.value = false
    }
}

private val DISPLAY_MATH = Regex("""\\\[[\s\S]*?\\]|\$\$[\s\S]*?\$\$""")
private val INLINE_MATH = Regex("""\\\([\s\S]*?\\\)|\$[^$\n]+\$""")
private val LATEX_COMMAND = Regex("""\\[a-zA-Z]+\s*""")
private val MARKDOWN_MARKS = Regex("""[*_`#>]+""")
private val BRACES = Regex("""[{}]""")
private val BLANK_LINES = Regex("""\n{2,}""")
private val SPACES = Regex("""[ \t]{2,}""")

/**
 * Turns an answer into something worth hearing.
 *
 * Maths is replaced by a short spoken placeholder rather than read out: a TTS
 * engine saying "backslash frac open brace one close brace" helps nobody, and
 * the student has the equation on screen in front of them while it reads the
 * surrounding explanation.
 */
internal fun stripForSpeech(markdown: String): String {
    var text = markdown
    text = DISPLAY_MATH.replace(text, " … ")
    text = INLINE_MATH.replace(text, " … ")
    text = LATEX_COMMAND.replace(text, " ")
    text = BRACES.replace(text, " ")
    text = MARKDOWN_MARKS.replace(text, "")
    text = BLANK_LINES.replace(text, "\n")
    text = SPACES.replace(text, " ")
    return text.lines().joinToString("\n") { it.trim() }.trim()
}
