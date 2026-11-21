
package com.swarai.studio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var selectedVoiceName: String? = null
    private var selectedOnnxPath: String? = null

    private fun getModelsDir(): File {
        val base = Environment.getExternalStorageDirectory()
        return File(base, "SwarAI/models")
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + packageName)
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {}
                }
            }
            return
        }
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 204)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureStoragePermission()

        try {
            val dir = getModelsDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
        } catch (_: Exception) {}

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
                    tts?.language = Locale("hi", "IN")
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {}
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {}
                    })
                    webView.evaluateJavascript(
                        "if(window.__swaraiRefreshVoices)window.__swaraiRefreshVoices();if(window.__swaraiRefreshOnnxModels)window.__swaraiRefreshOnnxModels();var b=document.getElementById(\"swarai-onnx-scan-btn\");if(b){b.style.pointerEvents=\"auto\";b.onclick=function(e){if(e)e.preventDefault();if(typeof refreshOnnxList===\"function\")refreshOnnxList(true);else if(window.__swaraiRefreshOnnxModels)window.__swaraiRefreshOnnxModels();};}",
                        null
                    )
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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        "if(window.__swaraiRefreshVoices)window.__swaraiRefreshVoices();if(window.__swaraiRefreshOnnxModels)window.__swaraiRefreshOnnxModels();",
                        null
                    )
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidTtsBridge(), "AndroidTTS")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    private fun pickMaleVoice(engine: TextToSpeech, langPrefix: String): Voice? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val voices = engine.voices?.filter { !it.isNetworkConnectionRequired } ?: return null
        val candidates = voices.filter { it.locale.language.equals(langPrefix, ignoreCase = true) }
            .ifEmpty { voices }

        return candidates.firstOrNull { v ->
            val n = v.name.lowercase(Locale.ROOT)
            n.contains("male") && !n.contains("female")
        } ?: candidates.firstOrNull { v ->
            val n = v.name.lowercase(Locale.ROOT)
            n.contains("-m-") || n.endsWith("-m") || n.contains("man") || n.contains("deep")
        }
    }

    
    private val onnxAudioLock = Any()
    private var activeOnnxTrack: AudioTrack? = null
    private var cachedOnnxTts: OfflineTts? = null
    private var cachedOnnxKey: String = ""

    internal fun stopOnnxAudioTrack() {
        synchronized(onnxAudioLock) {
            try {
                activeOnnxTrack?.pause()
                activeOnnxTrack?.flush()
                activeOnnxTrack?.stop()
                activeOnnxTrack?.release()
            } catch (_: Throwable) {}
            activeOnnxTrack = null
        }
    }

    internal fun releaseOnnxEngine() {
        stopOnnxAudioTrack()
        synchronized(onnxAudioLock) {
            try {
                cachedOnnxTts?.release()
            } catch (_: Throwable) {}
            cachedOnnxTts = null
            cachedOnnxKey = ""
        }
    }

    inner class AndroidTtsBridge {
        @JavascriptInterface
        fun getModelsFolderPath(): String {
            return try {
                getModelsDir().absolutePath
            } catch (_: Exception) {
                "/storage/emulated/0/SwarAI/models"
            }
        }

        @JavascriptInterface
        fun getLocalOnnxModels(): String {
            val arr = JSONArray()
            try {
                val dir = getModelsDir()
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val files = dir.listFiles()
                if (files != null) {
                    val onnxFiles = files.filter { it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".onnx") }
                        .sortedBy { it.name.lowercase(Locale.ROOT) }
                    for (f in onnxFiles) {
                        val sizeMb = String.format(Locale.US, "%.1f MB", f.length() / (1024.0 * 1024.0))
                        val hasJson = File(f.absolutePath + ".json").exists()
                        val obj = JSONObject()
                        obj.put("name", f.name)
                        obj.put("path", f.absolutePath)
                        obj.put("size", sizeMb)
                        obj.put("hasConfigJson", hasJson)
                        arr.put(obj)
                    }
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface
        fun selectOnnxModel(path: String?): String {
            selectedOnnxPath = if (path.isNullOrBlank()) null else path.trim()
            runOnUiThread {
                if (selectedOnnxPath != null) {
                    val f = File(selectedOnnxPath!!)
                    Toast.makeText(this@MainActivity, "मॉडल चुना गया: ${f.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "सिस्टम TTS मोड चुना गया", Toast.LENGTH_SHORT).show()
                }
            }
            return selectedOnnxPath ?: ""
        }

        @JavascriptInterface
        fun getSelectedOnnxModel(): String {
            return selectedOnnxPath ?: ""
        }

        @JavascriptInterface
        fun getAvailableVoices(): String {
            val arr = JSONArray()
            try {
                val engine = tts
                if (engine != null && ttsReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val voices: Set<Voice>? = engine.voices
                    if (voices != null) {
                        val sorted = voices.filter { !it.isNetworkConnectionRequired }
                            .sortedWith(compareBy<Voice> {
                                when {
                                    it.locale.language == "hi" -> 0
                                    it.locale.language == "en" -> 1
                                    else -> 2
                                }
                            }.thenBy { it.name })

                        for (v in sorted) {
                            val obj = JSONObject()
                            obj.put("name", v.name)
                            val langTag = v.locale.toLanguageTag()
                            val labelLang = when (v.locale.language) {
                                "hi" -> "हिंदी"
                                "en" -> "English"
                                else -> langTag
                            }
                            val nLow = v.name.lowercase(Locale.ROOT)
                            val genderHint = when {
                                nLow.contains("female") || nLow.contains("-f-") -> " [Female]"
                                nLow.contains("male") || nLow.contains("-m-") -> " [Male]"
                                else -> ""
                            }
                            obj.put("label", "$labelLang$genderHint • ${v.name} ($langTag)")
                            obj.put("lang", langTag)
                            arr.put(obj)
                        }
                    }
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface
        fun setVoice(voiceName: String?) {
            selectedVoiceName = voiceName
            runOnUiThread {
                try {
                    val engine = tts
                    if (engine != null && ttsReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !voiceName.isNullOrBlank()) {
                        val match = engine.voices?.firstOrNull { it.name == voiceName }
                        if (match != null) {
                            engine.voice = match
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun selectMaleVoice(lang: String = "hi-IN"): String {
            var chosen = ""
            runOnUiThread {
                try {
                    val engine = tts
                    if (engine != null && ttsReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val prefix = lang.split("-", "_").firstOrNull() ?: "hi"
                        val mv = pickMaleVoice(engine, prefix)
                        if (mv != null) {
                            selectedVoiceName = mv.name
                            engine.voice = mv
                            chosen = mv.name
                        }
                    }
                } catch (_: Exception) {}
            }
            return chosen
        }

            private fun trySpeakWithOnnx(text: String, rate: Float, pitch: Float, lang: String): Boolean {
        return try {
            val modelsDir = File(Environment.getExternalStorageDirectory(), "SwarAI/models")
            if (!modelsDir.exists() || !modelsDir.isDirectory) return false

            val onnxFile = modelsDir.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("onnx", ignoreCase = true)
            } ?: return false

            val tokensFile = File(modelsDir, "tokens.txt")
            if (!tokensFile.exists() || !tokensFile.isFile) return false

            val espeakDir = File(modelsDir, "espeak-ng-data")
            val key = "${onnxFile.absolutePath}|${tokensFile.absolutePath}|${espeakDir.absolutePath}"

            val ttsEngine = synchronized(onnxAudioLock) {
                if (cachedOnnxTts != null && cachedOnnxKey == key) {
                    cachedOnnxTts
                } else {
                    try { cachedOnnxTts?.release() } catch (_: Throwable) {}
                    cachedOnnxTts = null
                    val vitsCfg = OfflineTtsVitsModelConfig().apply {
                        model = onnxFile.absolutePath
                        tokens = tokensFile.absolutePath
                        dataDir = if (espeakDir.exists() && espeakDir.isDirectory) espeakDir.absolutePath else ""
                    }
                    val modelCfg = OfflineTtsModelConfig().apply {
                        vits = vitsCfg
                        numThreads = 2
                        debug = false
                        provider = "cpu"
                    }
                    val ttsCfg = OfflineTtsConfig().apply {
                        model = modelCfg
                    }
                    val created = OfflineTts(assets, ttsCfg)
                    cachedOnnxTts = created
                    cachedOnnxKey = key
                    created
                }
            } ?: return false

            val speed = rate.coerceIn(0.5f, 2.0f)
            val generated = try {
                ttsEngine.generate(text = text, sid = 0, speed = speed)
            } catch (_: Throwable) {
                return false
            }
            val samples = generated.samples
            val sampleRate = generated.sampleRate
            if (samples.isEmpty() || sampleRate !in 8000..48000) return false

            val pcm = ShortArray(samples.size) { idx ->
                val v = (samples[idx] * 32767.0f).toInt()
                v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            stopOnnxAudioTrack()
            if (pcm.isEmpty()) return false
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            val bufSize = maxOf(minBuf, pcm.size * 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            synchronized(onnxAudioLock) {
                activeOnnxTrack = track
            }
            track.play()
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            true
        } catch (_: Throwable) {
            false
        }
    }

            private fun speakWithSystemTts(cleanText: String, rate: Float, pitch: Float, lang: String) {
            runOnUiThread {
                val engine = tts
                if (engine != null && ttsReady) {
                    val r = if (rate >= 0.4f && rate <= 2.5f) rate else 1.0f
                    val p = if (pitch >= 0.4f && pitch <= 2.0f) pitch else 1.0f
            try { engine.setSpeechRate(r) } catch (_: Throwable) {}
            try { engine.setPitch(p) } catch (_: Throwable) {}
            try {
                var appliedVoice = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !selectedVoiceName.isNullOrBlank()) {
                    val match = engine.voices?.firstOrNull { it.name == selectedVoiceName }
                    if (match != null) {
                        try {
                            engine.voice = match
                            appliedVoice = true
                        } catch (_: Throwable) {
                            appliedVoice = false
                        }
                    }
                }
                if (!appliedVoice) {
                    val parts = lang.split("-", "_")
                    val loc = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
                    val res = engine.setLanguage(loc)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.getDefault()
                    }
                }
            } catch (_: Throwable) {}

                    val speakRes = try {
                        if (cleanText.isNotEmpty()) engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "swarai_" + System.currentTimeMillis()) else TextToSpeech.ERROR
                    } catch (_: Throwable) {
                        TextToSpeech.ERROR
                    }
                    if (speakRes == TextToSpeech.ERROR) {
                        Toast.makeText(this@MainActivity, "TTS Speak Error", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "TTS Not Ready Yet", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
    fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f, lang: String = "hi-IN") {
        val clean = text.replace(Regex("\[[^\]]*\]"), " ").trim()
        if (clean.isEmpty()) return
        android.util.Log.d("SWARAI_TTS", "speak start lang=" + lang)
        try { stopOnnxAudioTrack() } catch (t: Throwable) { android.util.Log.e("SWARAI_TTS", "stopOnnx err", t) }
        Thread {
            var handled = false
            try {
                handled = trySpeakWithOnnx(clean, rate, pitch, lang)
            } catch (t: Throwable) {
                android.util.Log.e("SWARAI_TTS", "onnx err", t)
                handled = false
            }
            if (!handled) {
                runOnUiThread {
                    try {
                        speakWithSystemTts(clean, rate, pitch, lang)
                    } catch (t: Throwable) {
                        android.util.Log.e("SWARAI_TTS", "sys tts err", t)
                    }
                }
            }
        }.start()
    }
    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        releaseOnnxEngine()
            super.onDestroy()
    }


}
