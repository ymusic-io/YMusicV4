package io.ymusic.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.ymusic.ui.SpeechEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * Voice search for the search screen. With [inApp] (TV) it listens in YMusic's own UI through
 * [SpeechRecognizer], asking for the mic once; without it, or when the mic is refused or no
 * recognition service exists, it opens the system's speech dialog via [dialog]. Either way it
 * listens in the app language, and on Android 14+ also detects the system language.
 */
internal class AndroidSpeechInput(
    private val context: Context,
    private val inApp: Boolean,
    private val requestPermission: suspend (String) -> Boolean,
    private val dialog: suspend (Intent) -> String?,
) {
    /** [appLanguage]: the app's UI language (BCP-47); blank leaves the recognizer's default. */
    fun listen(appLanguage: String): Flow<SpeechEvent> = flow {
        val intent = speechIntent(appLanguage)
        if (inApp && SpeechRecognizer.isRecognitionAvailable(context) && micGranted()) {
            emitAll(recognize(intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)))
        } else {
            val spoken = dialog(intent)
            if (!spoken.isNullOrBlank()) emit(SpeechEvent.Final(spoken))
        }
    }

    private suspend fun micGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
            requestPermission(Manifest.permission.RECORD_AUDIO)

    private fun speechIntent(appLanguage: String): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (appLanguage.isBlank()) return intent
        val language = recognizerTag(Locale.forLanguageTag(appLanguage))
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        if (Build.VERSION.SDK_INT >= 34) {
            val candidates = arrayListOf(language, recognizerTag(systemLocale())).distinct().let(::ArrayList)
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, candidates)
                .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, candidates)
        }
        return intent
    }

    /** The device's own language, which the app's language override replaces in [Locale.getDefault]. */
    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    /** Recognizers expect language plus region (`vi-VN`); a bare language takes the system's region
     *  when they share it, else its most likely one. */
    private fun recognizerTag(locale: Locale): String {
        val region = locale.country.ifEmpty {
            val system = systemLocale()
            if (system.language == locale.language && system.country.isNotEmpty()) {
                system.country
            } else {
                ULocale.addLikelySubtags(ULocale.forLocale(locale)).country
            }
        }
        return Locale.Builder().setLanguage(locale.language).setRegion(region).build().toLanguageTag()
    }

    private fun recognize(intent: Intent): Flow<SpeechEvent> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onRmsChanged(rmsdB: Float) {
                    trySend(SpeechEvent.Level(((rmsdB - RMS_FLOOR_DB) / RMS_SPAN_DB).coerceIn(0f, 1f)))
                }

                override fun onPartialResults(partialResults: Bundle) {
                    partialResults.firstResult()?.let { trySend(SpeechEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle) {
                    val spoken = results.firstResult()
                    trySend(if (spoken.isNullOrBlank()) SpeechEvent.NoMatch else SpeechEvent.Final(spoken))
                    close()
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            trySend(SpeechEvent.NoMatch)
                            close()
                        }
                        else -> close(SpeechRecognitionException(error))
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)

    private fun Bundle.firstResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        /** onRmsChanged reports roughly -2 dB (silence) to 10 dB (loud speech). */
        const val RMS_FLOOR_DB = -2f
        const val RMS_SPAN_DB = 12f
    }
}

internal class SpeechRecognitionException(val error: Int) : Exception("SpeechRecognizer error $error")
