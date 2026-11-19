package com.swarai.studio

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
                tts?.language = Locale("hi", "IN")
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
                    engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "swarai_" + System.currentTimeMillis())
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
