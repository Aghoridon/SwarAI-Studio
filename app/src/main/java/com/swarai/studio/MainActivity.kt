package com.swarai.studio

import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + packageName)
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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
                    webView.evaluateJavascript("if(window.__swaraiRefreshVoices)window.__swaraiRefreshVoices();if(window.__swaraiRefreshOnnxModels)window.__swaraiRefreshOnnxModels();var b=document.getElementById(\"swarai-onnx-scan-btn\");if(b){b.style.pointerEvents=\"auto\";b.onclick=function(e){if(e)e.preventDefault();if(typeof refreshOnnxList===\"function\")refreshOnnxList(true);else if(window.__swaraiRefreshOnnxModels)window.__swaraiRefreshOnnxModels();};}", null)
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
                    view?.evaluateJavascript("if(window.__swaraiRefreshVoices)window.__swaraiRefreshVoices();if(window.__swaraiRefreshOnnxModels)window.__swaraiRefreshOnnxModels();", null)
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

        @JavascriptInterface
        fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f, lang: String = "hi-IN") {
            val clean = text.replace(Regex("\\[[^\\]]*\\]"), " ").trim()
            if (clean.isEmpty()) return
        // SWARAI_ONNX_SPEAK_HOOK
                val activeOnnx = selectedOnnxPath
                if (!activeOnnx.isNullOrBlank()) {
                    val onnxFile = File(activeOnnx)
                    val modelDir = onnxFile.parentFile
                    val tokensFile = if (modelDir != null) File(modelDir, "tokens.txt") else null
                    val espeakDir = if (modelDir != null) File(modelDir, "espeak-ng-data") else null
                    if (onnxFile.exists() && tokensFile != null && tokensFile.exists()) {
                        try {
                            val vitsCfg = OfflineTtsVitsModelConfig().apply {
                                model = onnxFile.absolutePath
                                tokens = tokensFile.absolutePath
                                if (espeakDir != null && espeakDir.exists()) {
                                    try { dataDir = espeakDir.absolutePath } catch (_: Throwable) {}
                                }
                                noiseScale = 0.667f
                                noiseScaleW = 0.8f
                                lengthScale = 1.0f
                            }
                            val modelCfg = OfflineTtsModelConfig().apply {
                                vits = vitsCfg
                                numThreads = 2
                                debug = false
                            }
                            val ttsCfg = OfflineTtsConfig().apply {
                                model = modelCfg
                            }
                            val sherpaTts = OfflineTts(assets, ttsCfg)
                            val spd = if (rate >= 0.4f && rate <= 2.5f) rate else 1.0f
                            val generated = sherpaTts.generate(clean, 0, spd)
                            val samples = generated.samples
                            val sr = generated.sampleRate
                            sherpaTts.release()
                            if (samples != null && samples.isNotEmpty() && sr > 0) {
                                val pcm = ShortArray(samples.size) { i ->
                                    val clamped = samples[i].coerceIn(-1.0f, 1.0f)
                                    (clamped * Short.MAX_VALUE).toInt().toShort()
                                }
                                val bufBytes = pcm.size * 2
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
                                            .setSampleRate(sr)
                                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                            .build()
                                    )
                                    .setBufferSizeInBytes(bufBytes)
                                    .setTransferMode(AudioTrack.MODE_STATIC)
                                    .build()
                                track.write(pcm, 0, pcm.size)
                                track.play()
                                return
                            }
                        } catch (_: Exception) {
                            // Fall back to system TTS on model/runtime error
                        }
                    } else if (!onnxFile.exists()) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "ONNX फ़ाइल नहीं मिली, सिस्टम TTS चल रहा है", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "tokens.txt नहीं मिला (सिस्टम TTS फ़ॉलबैक)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                runOnUiThread {
                            Toast.makeText(this@MainActivity, "ONNX फ़ाइल नहीं मिली, सिस्टम TTS चल रहा है", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "tokens.txt नहीं मिला (सिस्टम TTS फ़ॉलबैक)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                runOnUiThread {
                    val engine = tts
                if (engine != null && ttsReady) {
                    val r = if (rate >= 0.4f && rate <= 2.5f) rate else 1.0f
                    val p = if (pitch >= 0.4f && pitch <= 2.0f) pitch else 1.0f
                    engine.setSpeechRate(r)
                    engine.setPitch(p)

                    var appliedVoice = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !selectedVoiceName.isNullOrBlank()) {
                        val match = engine.voices?.firstOrNull { it.name == selectedVoiceName }
                        if (match != null) {
                            engine.voice = match
                            appliedVoice = true
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

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}

