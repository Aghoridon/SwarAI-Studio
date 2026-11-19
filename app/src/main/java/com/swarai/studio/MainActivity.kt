package com.swarai.studio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(attrs)
                }
                val res = tts?.setLanguage(Locale("hi", "IN"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread {
                            webView.evaluateJavascript("if(window.__onNativeTtsStart)window.__onNativeTtsStart();", null)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            webView.evaluateJavascript("if(window.__onNativeTtsDone)window.__onNativeTtsDone();", null)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            webView.evaluateJavascript("if(window.__onNativeTtsDone)window.__onNativeTtsDone();", null)
                        }
                    }
                })
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Android TTS इंजन शुरू नहीं हो सका", Toast.LENGTH_SHORT).show()
                }
            }
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidTtsBridge(), "AndroidTTS")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    inner class AndroidTtsBridge {
        @JavascriptInterface
        fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f, lang: String = "hi-IN") {
            val clean = text.replace(Regex("\\[[^\\]]*\\]"), " ").trim()
            if (clean.isEmpty()) return
            runOnUiThread {
                val engine = tts
                if (engine != null && ttsReady) {
                    val parts = lang.split("-", "_")
                    val loc = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
                    val r = engine.setLanguage(loc)
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.getDefault()
                    }
                    engine.setSpeechRate(if (rate > 0.1f) rate else 1.0f)
                    engine.setPitch(if (pitch > 0.1f) pitch else 1.0f)
                    val uttId = "swarai_" + System.currentTimeMillis()
                    val speakRes = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, uttId)
                    if (speakRes == TextToSpeech.ERROR) {
                        Toast.makeText(this@MainActivity, "TTS बोलने में त्रुटि आई", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "TTS अभी तैयार हो रहा है, दोबारा दबाएं", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                try {
                    tts?.stop()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
