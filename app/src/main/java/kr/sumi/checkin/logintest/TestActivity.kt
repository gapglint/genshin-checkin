package kr.sumi.checkin.logintest

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/**
 * 고른 방식으로 호요랩 로그인 화면을 띄운다. 로그인 쿠키가 생기면 성공으로 기록하고 바로 지운다(계정은 저장하지 않는다).
 * 성공하지 못하고 나가면 어땠는지 물어서 같이 기록한다.
 */
open class TestActivity : Activity() {
    private lateinit var strategy: Strategy
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val started = System.currentTimeMillis()
    private val trail = ArrayDeque<String>()
    private var pageStarts = 0
    private var finished = false
    private var loginOpened = false

    private fun seconds() = (System.currentTimeMillis() - started) / 1000

    /** 주소는 호스트와 경로만 남긴다(질의 문자열에 토큰이 붙는 경우가 있어 버린다). */
    private fun note(event: String, url: String? = null) {
        val path = url?.let { Uri.parse(it).let { u -> "${u.host ?: u.scheme}${u.path ?: ""}${u.fragment?.let { f -> "#" + f.substringBefore("?") } ?: ""}" } }
        trail.addLast("  +${seconds()}s $event${path?.let { " $it" } ?: ""}")
        while (trail.size > 40) trail.removeFirst()
    }

    private val pollCookie = object : Runnable {
        override fun run() {
            if (finished) return
            if (hasLogin()) {
                note("로그인 쿠키 생김")
                done("성공 (${seconds()}초)")
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        strategy = Strategy.byId(intent.getStringExtra(EXTRA_ID)!!)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        status = TextView(this).apply {
            text = "${strategy.title}\n로그인해 보세요. 되면 자동으로 닫혀요. 안 되면 뒤로 가기를 눌러 주세요."
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val quit = TextView(this).apply {
            text = "안 돼요 → 끝내기"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0xC6, 0x28, 0x28))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { askWhatHappened() }
        }
        frame = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(quit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // targetSdk 36은 화면을 상태 표시줄 뒤까지 그리므로 위아래 여백을 직접 준다.
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { askWhatHappened() }
        }

        note("시작 · 창 GPU ${if (window.decorView.isHardwareAccelerated) "켜짐" else "꺼짐"}")
        val web = newWebView()
        webView = web
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        clearSession {
            web.loadUrl(strategy.start.url)
            handler.postDelayed(pollCookie, 1000)
        }
    }

    @Deprecated("Android 13 미만용")
    override fun onBackPressed() = askWhatHappened()

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        val ua = WebSettings.getDefaultUserAgent(this@TestActivity)
        if (!strategy.keepWebViewUa) settings.userAgentString = ua.replace("; wv", "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        if (strategy.softwareLayer) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        if (strategy.noForcedDark) {
            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
            else if (Build.VERSION.SDK_INT >= 29) forceDarkOff(settings)
            isForceDarkAllowed = false
        }
        note("웹뷰 그리기 ${if (strategy.softwareLayer) "소프트웨어" else "기본"}")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageStarts++
                note("페이지 시작", url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                note("페이지 끝", url)
                if (strategy.autoOpenLogin && !loginOpened && url.startsWith("https://act.hoyolab.com/")) {
                    loginOpened = true
                    note("로그인 창 자동으로 열기")
                    view.evaluateJavascript(OPEN_LOGIN_JS, null)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame || request.url.host?.contains("account") == true) {
                    note("오류 ${error.errorCode} ${error.description}", request.url.toString())
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame || request.url.host?.contains("account") == true) {
                    note("HTTP ${response.statusCode}", request.url.toString())
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                note("웹뷰 엔진 종료 crash=${detail.didCrash()}")
                frame.removeView(view)
                view.destroy()
                webView = null
                status.text = "${strategy.title}\n로그인 화면이 멈췄어요. 뒤로 가기를 눌러 주세요."
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            // 페이지 스크립트 오류만 남긴다(내용은 앞부분만).
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    note("스크립트 오류 ${message.message().take(80)}")
                }
                return true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun forceDarkOff(settings: WebSettings) {
        if (Build.VERSION.SDK_INT >= 29) settings.forceDark = WebSettings.FORCE_DARK_OFF
    }

    /** 쿠키 이름만 보고 로그인됐는지 판단한다. 값은 읽어 쓰지 않는다. */
    private fun hasLogin(): Boolean = HOSTS.any { names(it).containsAll(listOf("ltoken_v2", "ltuid_v2")) }

    private fun names(host: String): Set<String> =
        CookieManager.getInstance().getCookie(host)?.split(";")?.map { it.substringBefore("=").trim() }?.toSet() ?: emptySet()

    /** 어느 주소에 어떤 쿠키 이름이 보였는지(값 없이). 쿠키가 따로 보관되는 문제인지 보려는 용도. */
    private fun cookieNames(): String = HOSTS.joinToString("\n") { host ->
        val n = names(host).filter { it in WATCHED }.sorted()
        "  ${Uri.parse(host).host}: ${if (n.isEmpty()) "-" else n.joinToString(",")}"
    }

    private fun askWhatHappened() {
        if (finished) return
        val choices = arrayOf(
            "화면이 까맣게 깜빡였어요",
            "로그인 창이 안 떴어요",
            "아이디·비번을 넣었는데 안 넘어가요",
            "캡차(퍼즐)에서 막혔어요",
            "기타 / 잘 모르겠어요",
        )
        AlertDialog.Builder(this)
            .setTitle("어땠나요?")
            .setItems(choices) { _, which -> done("안 됨: ${choices[which]}") }
            .setNegativeButton("계속 해 볼게요", null)
            .show()
    }

    private fun done(summary: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(pollCookie)
        val detail = buildString {
            appendLine("  페이지 시작 ${pageStarts}번")
            appendLine("  보인 쿠키 이름:")
            appendLine(cookieNames())
            trail.forEach { appendLine(it) }
        }
        Results(this).save(strategy.id, summary, detail)
        // 시험이 끝나면 로그인 흔적을 지운다.
        clearSession { finish() }
    }

    private fun clearSession(then: () -> Unit) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            then()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private val HOSTS = listOf(
            "https://www.hoyolab.com", "https://act.hoyolab.com", "https://m.hoyolab.com", "https://account.hoyolab.com",
            "https://account.hoyoverse.com",
        )
        private val WATCHED = setOf(
            "ltoken_v2", "ltuid_v2", "ltmid_v2", "cookie_token_v2", "account_id_v2", "account_mid_v2", "ltoken", "ltuid",
        )

        /** 본 앱과 같은 스크립트: 출석 페이지의 「로그인」 버튼을 찾아 누른다. */
        private const val OPEN_LOGIN_JS = """
            (function tryOpen(n) {
              var btn = Array.prototype.find.call(document.querySelectorAll('span,a,button,div'), function (e) {
                return e.children.length === 0 &&
                  (e.textContent.trim() === '「로그인」' || /login-btn/.test(String(e.className)));
              });
              if (btn) btn.click(); else if (n > 0) setTimeout(function () { tryOpen(n - 1); }, 500);
            })(20);
        """

        fun intent(context: Context, strategy: Strategy): Intent {
            val target = if (strategy.noGpuWindow) NoGpuTestActivity::class.java else TestActivity::class.java
            return Intent(context, target).putExtra(EXTRA_ID, strategy.id)
        }
    }
}

/** 매니페스트에서 하드웨어 가속을 끈 화면. 내용은 TestActivity와 같다. */
class NoGpuTestActivity : TestActivity()
