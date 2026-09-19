package com.instaweb.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.URLDecoder
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashOverlay: FrameLayout
    private lateinit var loadingOverlay: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingDownloadAction: (() -> Unit)? = null
    private var splashActive = false
    private var pendingBackgroundSync = false
    private var lastNightMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (grants.values.all { it }) request.grant(request.resources) else request.deny()
        }
    }

    private val geoLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val callback = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        if (callback != null) callback.invoke(origin, grants.values.any { it }, false)
    }

    private val storageLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingDownloadAction
        pendingDownloadAction = null
        if (granted) action?.invoke() else toast(R.string.download_failed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)
        splashOverlay = findViewById(R.id.splash_overlay)
        loadingOverlay = findViewById(R.id.loading_overlay)
        applyThemeColors()
        setupInsets()
        setupWebView()
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideFullscreenView()
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
        if (savedInstanceState == null) {
            webView.loadUrl(INITIAL_URL)
            startSplashSequence()
        } else {
            splashOverlay.visibility = View.GONE
            loadingOverlay.visibility = View.GONE
            if (webView.restoreState(savedInstanceState) == null) {
                webView.loadUrl(INITIAL_URL)
            }
        }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night != lastNightMode) {
            lastNightMode = night
            applyThemeColors()
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        customView?.let { (window.decorView as ViewGroup).removeView(it) }
        customView = null
        customViewCallback = null
        webView.destroy()
        super.onDestroy()
    }

    private fun applyThemeColors() {
        val night = isNightMode()
        lastNightMode = night
        webView.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
        splashOverlay.setBackgroundColor(ContextCompat.getColor(this, R.color.splash_background))
        loadingOverlay.setBackgroundColor(ContextCompat.getColor(this, R.color.loading_background))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    private fun startSplashSequence() {
        splashActive = true
        val density = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val minDimPx = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val logoSize = (minDimPx * 0.27f).coerceIn(96f * density, 120f * density).toInt()
        val logo = findViewById<ImageView>(R.id.splash_logo)
        logo.layoutParams = logo.layoutParams.apply {
            width = logoSize
            height = logoSize
        }
        val lockup = findViewById<ImageView>(R.id.splash_meta_lockup)
        val lockupWidth = (minDimPx * 0.217f).coerceIn(64f * density, 112f * density).toInt()
        lockup.layoutParams = lockup.layoutParams.apply {
            width = lockupWidth
        }
        val block = findViewById<LinearLayout>(R.id.splash_lockup_block)
        ViewCompat.setOnApplyWindowInsetsListener(splashOverlay) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val params = block.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = max((metrics.heightPixels * 0.10f).toInt(), bars.bottom + (20f * density).toInt())
            block.layoutParams = params
            WindowInsetsCompat.CONSUMED
        }
        val easeOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        logo.scaleX = 0.9f
        logo.scaleY = 0.9f
        logo.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(easeOut).start()
        block.alpha = 0f
        block.translationY = 14f * density
        block.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(340).setInterpolator(easeOut).start()
        mainHandler.postDelayed({ transitionToLoading() }, SPLASH_HOLD_MS)
    }

    private fun transitionToLoading() {
        loadingOverlay.visibility = View.VISIBLE
        loadingOverlay.alpha = 0f
        loadingOverlay.animate().alpha(1f).setDuration(260).start()
        splashOverlay.animate().alpha(0f).setDuration(260).withEndAction {
            splashOverlay.visibility = View.GONE
        }.start()
        mainHandler.postDelayed({ dismissLoading() }, 260 + SPINNER_HOLD_MS)
    }

    private fun dismissLoading() {
        loadingOverlay.animate().alpha(0f).setDuration(320).withEndAction {
            loadingOverlay.visibility = View.GONE
            (splashOverlay.parent as? ViewGroup)?.removeView(splashOverlay)
            (loadingOverlay.parent as? ViewGroup)?.removeView(loadingOverlay)
            splashActive = false
            if (pendingBackgroundSync) {
                pendingBackgroundSync = false
                syncPageBackground()
            }
        }.start()
    }

    private fun setupInsets() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val padding = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setWindowInsetsAnimationCallback(webView, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                val padding = insets.getInsets(
                    WindowInsetsCompat.Type.ime()
                        or WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
                )
                webView.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                return insets
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            setGeolocationEnabled(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        webView.setBackgroundColor(if (isNightMode()) Color.BLACK else Color.WHITE)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                return when (scheme) {
                    "http", "https", "file", "data", "blob", "about", "javascript" -> false
                    else -> {
                        openExternally(url)
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (splashActive) {
                    pendingBackgroundSync = true
                } else {
                    syncPageBackground()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showErrorPage(error.description?.toString() ?: getString(R.string.error_message_default))
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String,
                result: JsPromptResult
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue)
                    setSingleLine()
                }
                val container = FrameLayout(this@MainActivity).apply {
                    val pad = (resources.displayMetrics.density * 24).toInt()
                    setPadding(pad, 0, pad, 0)
                    addView(input)
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    geoLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val needed = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) needed.add(Manifest.permission.CAMERA)
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) needed.add(Manifest.permission.RECORD_AUDIO)
                when {
                    needed.isEmpty() -> request.grant(request.resources)
                    needed.all { hasPermission(it) } -> request.grant(request.resources)
                    else -> {
                        pendingPermissionRequest?.deny()
                        pendingPermissionRequest = request
                        permissionLauncher.launch(needed.toTypedArray())
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermissionRequest == request) pendingPermissionRequest = null
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val types = fileChooserParams.acceptTypes
                    .filter { !it.isNullOrBlank() }
                    .map { it.trim() }
                    .toTypedArray()
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (types.isEmpty()) "*/*" else types.first()
                    if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types)
                    if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, getString(R.string.choose_file)))
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.INVISIBLE
                (window.decorView as ViewGroup).addView(
                    view,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                )
                WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                hideFullscreenView()
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url == null -> Unit
                url.startsWith("data:") -> ensureStoragePermission { saveDataUri(url, mimeType) }
                url.startsWith("http://") || url.startsWith("https://") -> ensureStoragePermission {
                    downloadViaManager(url, userAgent, contentDisposition, mimeType)
                }
                else -> toast(R.string.download_failed)
            }
        }
    }

    private fun hideFullscreenView() {
        val view = customView ?: return
        (window.decorView as ViewGroup).removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    private fun syncPageBackground() {
        webView.evaluateJavascript(BG_PROBE_SCRIPT) { value ->
            val color = parseCssColor(value)
            webView.setBackgroundColor(color)
            val light = luminanceOf(color) > 0.5f
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }

    private fun showErrorPage(message: String) {
        val safe = message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html = ERROR_PAGE.replace("__MESSAGE__", Uri.encode(safe))
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun ensureStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            action()
        } else {
            pendingDownloadAction = action
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun downloadViaManager(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent ?: "")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(R.string.download_started)
        } catch (e: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun saveDataUri(url: String, fallbackMime: String?) {
        try {
            val comma = url.indexOf(',')
            if (comma < 6) {
                toast(R.string.download_failed)
                return
            }
            val meta = url.substring(5, comma)
            val isBase64 = meta.contains("base64", ignoreCase = true)
            val mime = meta.substringBefore(';').ifBlank { fallbackMime ?: "application/octet-stream" }
            val payload = url.substring(comma + 1)
            val bytes = if (isBase64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val name = "download-${System.currentTimeMillis()}.$extension"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    toast(R.string.download_failed)
                    return
                }
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                @Suppress("DEPRECATION")
                if (!dir.mkdirs() && !dir.isDirectory) {
                    toast(R.string.download_failed)
                    return
                }
                File(dir, name).outputStream().use { it.write(bytes) }
            }
            toast(R.string.download_saved)
        } catch (e: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun hostOf(url: String): String = Uri.parse(url).host?.takeIf { it.isNotBlank() } ?: url

    private fun luminanceOf(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    private fun parseCssColor(value: String?): Int {
        if (value.isNullOrBlank() || value.trim() == "null") return if (isNightMode()) Color.BLACK else Color.WHITE
        val v = value.trim().trim('"')
        val match = Regex("rgba?\\(([0-9]+)[,\\s]+([0-9]+)[,\\s]+([0-9]+)").find(v)
        if (match != null) {
            val (r, g, b) = match.destructured
            return Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }
        return try {
            Color.parseColor(v)
        } catch (e: IllegalArgumentException) {
            if (isNightMode()) Color.BLACK else Color.WHITE
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val INITIAL_URL = "file:///android_asset/index.html"
        private const val SPLASH_HOLD_MS = 1000L
        private const val SPINNER_HOLD_MS = 1400L

        private const val BG_PROBE_SCRIPT =
            "(function(){try{function ok(x){return x&&x!=='rgba(0, 0, 0, 0)'&&x!=='transparent'}" +
                "var b=document.body,e=document.documentElement;" +
                "if(b&&ok(getComputedStyle(b).backgroundColor))return getComputedStyle(b).backgroundColor;" +
                "if(e&&ok(getComputedStyle(e).backgroundColor))return getComputedStyle(e).backgroundColor;" +
                "var d=window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches;" +
                "return d?'rgb(0, 0, 0)':'rgb(255, 255, 255)'}catch(t){return 'rgb(255, 255, 255)'}})()"

        private const val ERROR_PAGE =
            "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>Instagram</title>" +
                "<style>*{margin:0;padding:0;box-sizing:border-box}" +
                "body{min-height:100vh;display:flex;align-items:center;justify-content:center;" +
                "background:#111;color:#f5f5f7;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;padding:24px}" +
                ".card{max-width:420px;width:100%;background:#1c1c1e;border-radius:22px;padding:28px;text-align:center}" +
                "h1{font-size:1.25rem;margin-bottom:10px}" +
                "p{color:#98989d;font-size:.9rem;line-height:1.5;margin-bottom:20px;word-break:break-word}" +
                "button{border:0;border-radius:13px;padding:13px 22px;font-size:.9rem;font-weight:700;color:#fff;" +
                "background:linear-gradient(135deg,#fa7e1e,#d62976 55%,#962fbf);cursor:pointer}" +
                "</style></head><body><div class=\"card\"><h1>Page unavailable</h1><p id=\"m\"></p>" +
                "<button onclick=\"location.replace('file:///android_asset/index.html')\">Try again</button></div>" +
                "<script>document.getElementById('m').textContent=decodeURIComponent(\"__MESSAGE__\")</script>" +
                "</body></html>"
    }
}
