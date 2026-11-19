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
            runOnUiThread {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val attrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(attrs)
                    }
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {}
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {}
                    })
                } else {
                    Toast.makeText(this, "TTS Init Failed: $status", Toast.LENGTH_LONG).show()
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
        fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f, lang: String = "en-US") {
            val clean = text.replace(Regex("\\[[^\\]]*\\]"), " ").trim()
            if (clean.isEmpty()) return
            runOnUiThread {
                val engine = tts
                if (engine != null && ttsReady) {
                    val r = if (rate > 0.2f && rate <= 2.5f) rate else 1.0f
                    val p = if (pitch > 0.3f && pitch <= 2.0f) pitch else 1.0f
                    engine.setSpeechRate(r)
                    engine.setPitch(p)
                    Toast.makeText(this@MainActivity, "TTS Speak: $clean", Toast.LENGTH_SHORT).show()
                    val res = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "swarai_" + System.currentTimeMillis())
                    if (res == TextToSpeech.ERROR) {
                        Toast.makeText(this@MainActivity, "TTS Speak Error", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "TTS Not Ready Yet", Toast.LENGTH_SHORT).show()
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
