package com.stadium.manager

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$HOST/assets/index.html"

        // Asks the page to handle the back button (close a window / go to the previous tab).
        // Returns true when the page handled it.
        private const val JS_BACK =
            "(function(){try{return !!(window.__nativeBack&&window.__nativeBack());}catch(e){return false;}})()"
    }

    lateinit var webView: WebView
        private set
    lateinit var bridge: NativeBridge
        private set

    private var lastBackPress = 0L
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>
    private lateinit var notifPermission: ActivityResultLauncher<String>
    private var pendingNotifCb: String? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannels(this)

        fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback ?: return@registerForActivityResult
            fileCallback = null
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }
        notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            Notifier.markAsked(this)
            val id = pendingNotifCb ?: return@registerForActivityResult
            pendingNotifCb = null
            bridge.callJs(id, "{\"state\":\"" + Notifier.permissionState(this) + "\"}")
        }

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#F7FCF9"))
        setContentView(webView)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }

        bridge = NativeBridge(this)
        webView.addJavascriptInterface(bridge, "AndroidNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                if (url.scheme == "blob" || url.scheme == "data" || url.scheme == "about") return false
                openExternal(url)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = params.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        // Backups can be .json / .xlsx: some phones hide them with strict MIME types.
                        if (type.isNullOrEmpty() || type == "*/*" || params.acceptTypes.any { it.startsWith(".") }) type = "*/*"
                    }
                    fileChooser.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            // window.open(...) / target=_blank -> open in the phone's browser / app.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        openExternal(r.url); v.destroy(); return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        // Plain http(s) downloads (blob downloads are handled by the page script).
        webView.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http")) openExternal(Uri.parse(url))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        if (KeepAliveService.isWanted(this)) KeepAliveService.start(this)

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        if (webView.url == null) webView.loadUrl(START_URL)
    }

    private fun handleBack() {
        webView.evaluateJavascript(JS_BACK) { result ->
            if (result == "true") return@evaluateJavascript
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPress < 2000) {
                lastBackPress = 0
                // Leave the app like the Home button does: it keeps running so notifications still arrive.
                moveTaskToBack(true)
            } else {
                lastBackPress = now
                Toast.makeText(this, "اضغط رجوع مرة أخرى للخروج", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openExternal(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "لا يوجد تطبيق لفتح هذا الرابط", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestNotificationPermission(cbId: String) {
        if (Build.VERSION.SDK_INT >= 33 && Notifier.permissionState(this) != "granted") {
            pendingNotifCb = cbId
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            bridge.callJs(cbId, "{\"state\":\"" + Notifier.permissionState(this) + "\"}")
        }
    }

    fun setBars(dark: Boolean) {
        val bar = if (dark) Color.BLACK else Color.parseColor("#047857")
        window.statusBarColor = bar
        window.navigationBarColor = if (dark) Color.BLACK else Color.WHITE
        if (Build.VERSION.SDK_INT >= 26) {
            var f = window.decorView.systemUiVisibility
            f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = f
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.evaluateJavascript("try{window.__nativeResume&&window.__nativeResume()}catch(e){}", null)
    }

    override fun onPause() {
        // Only marks the page as hidden (JavaScript keeps running, so live sync and notifications continue).
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) KeepAliveService.stop(this)
        webView.destroy()
        super.onDestroy()
    }
}
