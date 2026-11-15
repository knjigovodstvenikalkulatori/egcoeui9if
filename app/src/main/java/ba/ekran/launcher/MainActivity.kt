package ba.ekran.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {

    lateinit var web: WebView
    private lateinit var loader: WebViewAssetLoader

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleConfigIntent(intent)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)
        hideSystemUi()

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.loadWithOverviewMode = true
        s.useWideViewPort = true

        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host == HOST) {
                    val path = u.path ?: ""
                    if (path == "/net") return Net.proxy(u.getQueryParameter("u") ?: "")
                    if (path.startsWith("/icon/")) return Icons.response(this@MainActivity, u.lastPathSegment ?: "")
                    return loader.shouldInterceptRequest(u)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Glavni okvir ostaje na launcheru; iframe (YouTube player) smije normalno raditi.
                return request.isForMainFrame && request.url.host != HOST
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(this), "LauncherBridge")
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.loadUrl("https://$HOST/assets/index.html")
        web.requestFocus()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    /** Konfiguracija poslana s Maca: adb shell am start ... --es config <base64 JSON> */
    private fun handleConfigIntent(i: Intent?): Boolean {
        val b64 = i?.getStringExtra("config") ?: return false
        return try {
            val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            getSharedPreferences("ekran", MODE_PRIVATE).edit().putString("config", json).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (handleConfigIntent(intent) && ::web.isInitialized) {
            web.evaluateJavascript("window.launcherConfig && launcherConfig()", null)
        } else if (::web.isInitialized) {
            // Pritisnut Home dok smo već na launcheru: vrati se na početak.
            web.evaluateJavascript("window.launcherBack && launcherBack()", null)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (::web.isInitialized) {
            web.onResume()
            web.evaluateJavascript("window.launcherResume && launcherResume()", null)
            web.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        // Zaustavi YouTube player kad se otvori druga aplikacija.
        if (::web.isInitialized) web.evaluateJavascript("window.launcherPause && launcherPause()", null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                web.evaluateJavascript("window.launcherMenu && launcherMenu()", null)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                web.evaluateJavascript("window.launcherBack && launcherBack()", null)
            }
            return true // launcher se nikad ne zatvara
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
