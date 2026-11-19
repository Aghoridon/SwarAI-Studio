package com.swarai.studio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var lastBackPressedTime: Long = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        pendingWebPermissionRequest?.let { req ->
            if (micGranted) {
                req.grant(req.resources)
            } else {
                req.deny()
                Toast.makeText(this, "माइक रिकॉर्डिंग के लिए Microphone अनुमति आवश्यक है", Toast.LENGTH_SHORT).show()
            }
            pendingWebPermissionRequest = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val results: Array<Uri>? = when {
                data.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(results)
        } else {
            callback.onReceiveValue(null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        requestInitialPermissions()
        configureWebView()
        setupBackNavigation()

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestInitialPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Bridge for direct MP3 / WAV / JSON downloads into phone Downloads folder
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hook <a download="file.mp3" href="blob:..."> clicks so Android saves exact filename directly
                val hookScript = """
                    (function() {
                        if (window.__swaraiAndroidHooked) return;
                        window.__swaraiAndroidHooked = true;
                        const origClick = HTMLAnchorElement.prototype.click;
                        HTMLAnchorElement.prototype.click = function() {
                            if (this.href && (this.href.startsWith('blob:') || this.href.startsWith('data:'))) {
                                const fileName = this.download || ('SwarAI_' + Date.now() + '.mp3');
                                fetch(this.href)
                                    .then(r => r.blob())
                                    .then(blob => {
                                        const reader = new FileReader();
                                        reader.onloadend = function() {
                                            if (window.AndroidBridge && reader.result) {
                                                window.AndroidBridge.saveBase64File(
                                                    reader.result,
                                                    fileName,
                                                    blob.type || 'audio/mpeg'
                                                );
                                            }
                                        };
                                        reader.readAsDataURL(blob);
                                    })
                                    .catch(err => console.error('Blob bridge error:', err));
                                return;
                            }
                            return origClick.apply(this, arguments);
                        };
                    })();
                """.trimIndent()
                webView.evaluateJavascript(hookScript, null)
            }
        }

        // Fallback DownloadListener for any non-anchor downloads
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val safeName = "SwarAI_${System.currentTimeMillis()}.mp3"
                val js = """
                    fetch('$url').then(r => r.blob()).then(b => {
                        const fr = new FileReader();
                        fr.onloadend = () => window.AndroidBridge.saveBase64File(fr.result, '$safeName', '${mimeType ?: "audio/mpeg"}');
                        fr.readAsDataURL(b);
                    });
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                runOnUiThread {
                    val hasMic = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasMic) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermissionRequest = request
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                val wantsAudio = fileChooserParams?.isCaptureEnabled == true ||
                    acceptTypes.any { it.contains("audio", ignoreCase = true) }

                val intentToLaunch: Intent = if (wantsAudio) {
                    val pickAudioIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                    }
                    val recordAudioIntent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    Intent.createChooser(pickAudioIntent, "माइक से रिकॉर्ड करें या ऑडियो चुनें").apply {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(recordAudioIntent))
                    }
                } else {
                    val pickAnyIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    Intent.createChooser(pickAnyIntent, "फ़ाइल चुनें")
                }

                return try {
                    fileChooserLauncher.launch(intentToLaunch)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "फ़ाइल पिकर नहीं खुल सका", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPressedTime < 2000L) {
                        finish()
                    } else {
                        lastBackPressedTime = now
                        Toast.makeText(
                            this@MainActivity,
                            "ऐप बंद करने के लिए फिर से Back दबाएं",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            try {
                val pureBase64 = if (base64Data.contains(",")) {
                    base64Data.substringAfter(",")
                } else {
                    base64Data
                }
                val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
                val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._\\-\\u0900-\\u097F]"), "_")
                val resolvedMime = when {
                    cleanName.endsWith(".mp3", true) -> "audio/mpeg"
                    cleanName.endsWith(".wav", true) -> "audio/wav"
                    cleanName.endsWith(".json", true) -> "application/json"
                    mimeType.isNotBlank() -> mimeType
                    else -> "application/octet-stream"
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                        put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SwarAI_Studio")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert failed")

                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(decodedBytes)
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "SwarAI_Studio"
                    )
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val outFile = File(downloadsDir, cleanName)
                    FileOutputStream(outFile).use { it.write(decodedBytes) }
                    MediaScannerConnection.scanFile(
                        this@MainActivity,
                        arrayOf(outFile.absolutePath),
                        arrayOf(resolvedMime),
                        null
                    )
                }

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Downloads/SwarAI_Studio में सेव हो गया:\n$cleanName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ फ़ाइल सेव करने में त्रुटि: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
