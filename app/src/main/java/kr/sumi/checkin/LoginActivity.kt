package kr.sumi.checkin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedDispatcher
import java.io.IOException

/**
 * 호요랩 출첵 페이지를 WebView로 연다. WebView의 쿠키 저장소는 하나뿐이라 계정마다 비우고 시작한다.
 * 로그인 모드: 계정 추가·다시 로그인. 쿠키를 비우고 페이지의 로그인 창을 띄운다.
 *   아이디·비밀번호 입력과 캡차는 사람이 하고, 유효한 로그인 쿠키가 생기면 그 계정으로 저장한 뒤 닫는다.
 * 페이지 모드: 캡차 등으로 자동 출첵이 막혔을 때, 고른 계정의 쿠키를 넣고 출첵 페이지를 띄워 직접 누르게 한다.
 */
class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val pageAccountId by lazy { intent.getStringExtra(EXTRA_ACCOUNT) }
    private val loginMode get() = pageAccountId == null
    /** 마지막으로 서버에 유효성을 물어본 로그인 토큰. 같은 토큰은 다시 묻지 않아 요청이 몰리지 않는다. */
    private var checkedTokens: String? = null
    private var loginPopupOpened = false

    /**
     * 로그인이 안 될 때 보내 받을 기록: 열린 페이지(주소는 경로까지만, 질의 문자열은 버린다), 새 창, 오류, 웹뷰 엔진 종료.
     * 쿠키·아이디·비밀번호는 남기지 않는다. 화면의 '기록 복사'로 기기 정보와 함께 복사한다.
     */
    private val trail = ArrayDeque<String>()
    /** 웹뷰 엔진이 죽어 웹뷰를 치웠으면 true. 그 뒤로는 웹뷰를 건드리지 않는다. */
    private var engineGone = false
    private val started = System.currentTimeMillis()

    private fun note(event: String, url: String? = null) {
        val path = url?.let { Uri.parse(it).let { u -> "${u.host ?: u.scheme}${u.path ?: ""}${if (u.fragment != null) "#" + u.fragment!!.substringBefore("?") else ""}" } }
        trail.addLast("+${(System.currentTimeMillis() - started) / 1000}s $event${path?.let { " $it" } ?: ""}")
        while (trail.size > 60) trail.removeFirst()
    }

    private fun copyTrail() {
        val webView = WebView.getCurrentWebViewPackage()
        val text = buildString {
            appendLine("원신 출첵 ${packageManager.getPackageInfo(packageName, 0).versionName} 로그인 기록")
            appendLine("기기 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("웹뷰 ${webView?.packageName} ${webView?.versionName}")
            trail.forEach { appendLine(it) }
        }
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("로그인 기록", text))
        Toast.makeText(this, "기록을 복사했어요. 메신저나 메일에 붙여 넣어 보내 주세요.", Toast.LENGTH_LONG).show()
    }

    /** 로그인 페이지를 띄운 뒤, 사람이 로그인해서 토큰이 생기는지 1초마다 본다. */
    private val pollCookie = object : Runnable {
        override fun run() {
            val cookie = loginCookie()
            if (cookie == null || loginTokens(cookie) == checkedTokens) {
                handler.postDelayed(this, 1000)
                return
            }
            checkedTokens = loginTokens(cookie)
            validate(cookie) { valid ->
                note("로그인 토큰 확인: " + when (valid) { true -> "유효"; false -> "무효"; null -> "네트워크 오류" })
                when (valid) {
                    true -> onLoggedIn(cookie)
                    false -> handler.postDelayed(this, 1000)
                    // 네트워크 오류. 잠시 뒤 같은 토큰으로 다시 묻는다.
                    null -> { checkedTokens = null; handler.postDelayed(this, 5000) }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        val pageStore = pageAccountId?.let { AppSettings(this).account(it) }
        val screenTitle = if (loginMode) "호요랩 로그인" else "원신 출석체크 · ${pageStore?.displayName ?: ""}"
        title = screenTitle

        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = screenTitle
        header.findViewById<View>(R.id.headerBack).setOnClickListener { goBack() }
        status = header.findViewById(R.id.headerStatus)
        status.text = if (loginMode) {
            "호요랩 계정이나 구글·애플 등으로 로그인해 주세요. 로그인되면 자동으로 닫혀요.\n호요랩 앱 로그인은 그대로예요. 이 앱 로그인은 1년 동안 유지돼요."
        } else {
            "오늘 출석 칸을 눌러 직접 출석해 주세요. 캡차가 나오면 풀면 돼요."
        }
        webView = newWebView().apply {
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = note("페이지", url)

                /**
                 * 웹 주소가 아닌 링크(호요랩 페이지의 '앱으로 보기'가 여는 intent://, market:// 등)는 웹뷰가 못 연다.
                 * 그런 링크는 휴대폰에 넘겨 해당 앱(없으면 플레이 스토어)을 연다.
                 */
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("http://") || url.startsWith("https://")) return false
                    note("외부 링크", url)
                    openOutside(url)
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) note("불러오기 오류 ${error.errorCode} ${error.description}", request.url.toString())
                }

                /**
                 * 웹뷰 엔진이 죽으면 기본으로는 앱이 꺼진다. 다시 열면 같은 이유로 또 죽으며 깜빡일 수 있어
                 * 자동으로 다시 열지 않고, 기록을 남긴 뒤 안내만 띄운다(기록 복사 버튼은 그대로 있다).
                 */
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    note("웹뷰 엔진 종료 crash=${detail.didCrash()}")
                    engineGone = true
                    handler.removeCallbacks(pollCookie)
                    frame.removeView(view)
                    view.destroy()
                    frame.addView(TextView(this@LoginActivity).apply {
                        text = "로그인 화면이 멈췄어요.\n뒤로 갔다가 다시 시도하거나, 위의 '기록 복사'로 보내 주세요."
                        textSize = 15f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(getColor(R.color.ink))
                    })
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (loginMode && !loginPopupOpened && url.startsWith("https://act.hoyolab.com/")) {
                        loginPopupOpened = true
                        view.evaluateJavascript(OPEN_LOGIN_JS, null)
                    }
                }
            }
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        frame = FrameLayout(this).apply { addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (loginMode) {
                addView(otherWayButton(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(trailButton(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)

        // 뒤로 가기: 웹 페이지 안에서 먼저 뒤로 간다. Android 13+는 새 뒤로 가기 방식, 그 전은 onBackPressed.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }

        // 다른 계정의 쿠키가 남아 있으면 그 계정으로 로그인된 것처럼 보이므로 항상 비우고 시작한다.
        cookies.removeAllCookies {
            if (loginMode) {
                checkedTokens = null
                webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
                handler.postDelayed(pollCookie, 1000)
            } else {
                val cookie = pageStore?.cookie
                if (cookie == null) webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
                else injectCookie(cookie) { webView.loadUrl(CheckInClient.CHECK_IN_PAGE) }
            }
        }
    }

    /** Android 12 이하용. 13 이상은 위의 OnBackInvokedCallback이 처리한다(매니페스트에서 켜 둠). */
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Android 13 미만용")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (engineGone) return finish()
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    /**
     * 출석 페이지의 작은 로그인 창이 안 될 때: 호요랩 모바일 사이트를 열어 그 사이트의 로그인으로 들어간다.
     * 로그인 쿠키는 같은 hoyolab.com이라 똑같이 감지돼 저장된다.
     */
    private fun otherWayButton() = TextView(this).apply {
        val density = resources.displayMetrics.density
        text = "로그인 창이 안 되면 → 호요랩 사이트에서 로그인"
        textSize = 14f
        setTextColor(getColor(R.color.accent))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        minHeight = (44 * density).toInt()
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener {
            note("호요랩 사이트로 전환")
            status.text = "가운데 「로그인」을 눌러 로그인해 주세요. 로그인되면 자동으로 닫혀요."
            visibility = View.GONE
            webView.loadUrl(HOYOLAB_MOBILE)
        }
    }

    /** 로그인이 끝내 안 될 때 기록을 복사해 보내는 버튼(작게). */
    private fun trailButton() = TextView(this).apply {
        val density = resources.displayMetrics.density
        text = "그래도 안 되면 → 기록 복사해서 보내기"
        textSize = 13f
        setTextColor(getColor(R.color.ink_muted))
        minHeight = (40 * density).toInt()
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { copyTrail() }
    }

    /** intent:// 링크는 그 앱을, 안 깔렸으면 링크에 적힌 대체 주소나 플레이 스토어를 연다. 열 수 없으면 조용히 무시한다. */
    private fun openOutside(url: String) {
        try {
            if (url.startsWith("intent:")) {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                try {
                    startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    val fallback = intent.getStringExtra("browser_fallback_url")
                        ?: intent.`package`?.let { "https://play.google.com/store/apps/details?id=$it" }
                    fallback?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (e: Exception) {
            // 잘못된 링크이거나 열 앱이 없다. 로그인 화면은 그대로 둔다.
        }
    }

    /** 로그인 화면용 웹뷰. 로그인 창은 다른 도메인 iframe이라 서드파티 쿠키가 필요하다. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 구글 등 일부 로그인은 웹뷰 표시(wv)를 보고 막는다. 이 폰의 실제 브라우저 정보에서 그 표시만 빼서 쓴다
        // (버전을 고정해 두면 최신 폰의 실제 엔진과 어긋나 오히려 막힐 수 있다).
        settings.userAgentString = WebSettings.getDefaultUserAgent(this@LoginActivity).replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    override fun onPause() {
        super.onPause()
        // 페이지 모드에서 같은 계정으로 다시 로그인했을 수도 있으니 나갈 때 쿠키를 갱신해 둔다.
        val id = pageAccountId ?: return
        loginCookie()?.takeIf { uidOf(it) == id }?.let { AppSettings(this).account(id)?.cookie = it }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!engineGone) webView.destroy()
        super.onDestroy()
    }

    /**
     * 저장해 둔 계정 쿠키를 WebView에 넣고, 다 들어간 뒤에 then을 부른다.
     * setCookie는 비동기라 바로 페이지를 열면 쿠키가 들어가기 전에 요청이 나가 로그인 안 된 화면이 뜬다.
     */
    private fun injectCookie(cookie: String, then: () -> Unit) {
        val manager = CookieManager.getInstance()
        val values = cookieValues(cookie).filterKeys { it.isNotEmpty() }
        var pending = values.size
        if (pending == 0) return then()
        for ((name, value) in values) {
            manager.setCookie("https://www.hoyolab.com", "$name=$value; Domain=.hoyolab.com; Path=/; Secure") {
                if (--pending == 0) {
                    manager.flush()
                    then()
                }
            }
        }
    }

    /** WebView에 호요랩 로그인 쿠키가 있으면 그 쿠키 문자열. HttpOnly 쿠키도 여기서는 읽힌다. */
    private fun loginCookie(): String? =
        CookieManager.getInstance().getCookie("https://www.hoyolab.com")?.takeIf { hasLoginTokens(it) }

    /** 쿠키가 유효한지 백그라운드에서 묻고 UI 스레드로 결과를 준다. 네트워크 오류는 null. */
    private fun validate(cookie: String, onResult: (Boolean?) -> Unit) {
        Thread {
            val valid = try {
                CheckInClient(cookie).isLoggedIn()
            } catch (e: IOException) {
                null
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) onResult(valid)
            }
        }.start()
    }

    private fun onLoggedIn(cookie: String) {
        CookieManager.getInstance().flush()
        val id = uidOf(cookie) ?: return
        val settings = AppSettings(this)
        val existing = id in settings.accountIds
        val store = settings.saveLogin(id, cookie)
        CheckIn.schedule(this, restart = true)
        setResult(RESULT_OK)
        val message = if (existing) "${store.displayName} 계정을 다시 로그인했어요." else "계정을 추가했어요. 이제 자동으로 출석해요."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val EXTRA_ACCOUNT = "account"
        /** 로그인하지 않았으면 가운데에 「로그인」 버튼이 크게 보이는 호요랩 모바일 페이지. */
        private const val HOYOLAB_MOBILE = "https://m.hoyolab.com/#/timeline"

        /** 출첵 페이지의 「로그인」 버튼을 찾아 누른다. 페이지가 늦게 그려지므로 몇 번 다시 찾는다. */
        private const val OPEN_LOGIN_JS = """
            (function tryOpen(n) {
              var btn = Array.prototype.find.call(document.querySelectorAll('span,a,button,div'), function (e) {
                return e.children.length === 0 &&
                  (e.textContent.trim() === '「로그인」' || /login-btn/.test(String(e.className)));
              });
              if (btn) btn.click(); else if (n > 0) setTimeout(function () { tryOpen(n - 1); }, 500);
            })(20);
        """

        /** 계정 추가 또는 다시 로그인. 로그인한 계정의 UID로 어느 쪽인지 정해진다. */
        fun loginIntent(context: Context) = Intent(context, LoginActivity::class.java)

        fun checkInPageIntent(context: Context, accountId: String) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_ACCOUNT, accountId)

        private fun cookieValues(cookie: String): Map<String, String> =
            cookie.split(";").associate { it.substringBefore("=").trim() to it.substringAfter("=", "").trim() }

        internal fun hasLoginTokens(cookie: String): Boolean {
            val names = cookieValues(cookie).keys
            return "ltoken_v2" in names && "ltuid_v2" in names
        }

        /** 로그인 토큰 부분만. 호요랩 페이지는 분석용 쿠키를 수시로 바꾸므로 '새로 로그인했는지'는 이것으로 비교한다. */
        internal fun loginTokens(cookie: String): String {
            val values = cookieValues(cookie)
            return "${values["ltuid_v2"]}:${values["ltoken_v2"]}"
        }

        internal fun uidOf(cookie: String): String? = cookieValues(cookie)["ltuid_v2"]?.takeIf { it.isNotEmpty() }
    }
}
