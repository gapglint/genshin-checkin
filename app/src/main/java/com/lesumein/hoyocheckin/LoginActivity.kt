package com.lesumein.hoyocheckin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import java.io.IOException

/**
 * 호요랩 출첵 페이지를 WebView로 연다. WebView의 쿠키 저장소는 하나뿐이라 계정마다 비우고 시작한다.
 * 로그인 모드: 계정 추가·다시 로그인. 쿠키를 비우고 페이지의 로그인 창을 띄운다.
 *   아이디·비밀번호 입력과 캡차는 사람이 하고, 유효한 로그인 쿠키가 생기면 그 계정으로 저장한 뒤 닫는다.
 * 페이지 모드: 캡차 등으로 자동 출첵이 막혔을 때, 고른 계정의 쿠키를 넣고 출첵 페이지를 띄워 직접 누르게 한다.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val pageAccountId by lazy { intent.getStringExtra(EXTRA_ACCOUNT) }
    private val loginMode get() = pageAccountId == null
    /** 마지막으로 서버에 유효성을 물어본 로그인 토큰. 같은 토큰은 다시 묻지 않아 요청이 몰리지 않는다. */
    private var checkedTokens: String? = null
    private var loginPopupOpened = false

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val pageStore = pageAccountId?.let { AppSettings(this).account(it) }
        val screenTitle = if (loginMode) "호요랩 로그인" else "원신 출석체크 · ${pageStore?.displayName ?: ""}"
        title = screenTitle

        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = screenTitle
        header.findViewById<View>(R.id.headerBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        status = header.findViewById(R.id.headerStatus)
        status.text = if (loginMode) {
            "아이디·비밀번호로 로그인하고 캡차를 풀어 주세요. 로그인되면 자동으로 닫혀요.\n로그인 창이 안 뜨면 페이지 왼쪽 위 「로그인」을 누르세요."
        } else {
            "오늘 출석 칸을 눌러 직접 출석해 주세요. 캡차가 나오면 풀면 돼요."
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 일부 로그인 화면은 WebView 표시(wv)를 보고 막기 때문에 일반 모바일 크롬처럼 보이게 한다.
            settings.userAgentString = CheckInClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (loginMode && !loginPopupOpened && url.startsWith("https://act.hoyolab.com/")) {
                        loginPopupOpened = true
                        view.evaluateJavascript(OPEN_LOGIN_JS, null)
                    }
                }
            }
        }
        // 로그인 창은 다른 도메인(account.hoyolab.com) iframe이라 서드파티 쿠키가 필요하다.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // 다른 계정의 쿠키가 남아 있으면 그 계정으로 로그인된 것처럼 보이므로 항상 비우고 시작한다.
        cookies.removeAllCookies {
            if (loginMode) {
                checkedTokens = null
                webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
                handler.postDelayed(pollCookie, 1000)
            } else {
                pageStore?.cookie?.let { injectCookie(it) }
                webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 페이지 모드에서 같은 계정으로 다시 로그인했을 수도 있으니 나갈 때 쿠키를 갱신해 둔다.
        val id = pageAccountId ?: return
        loginCookie()?.takeIf { uidOf(it) == id }?.let { AppSettings(this).account(id)?.cookie = it }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    /** 저장해 둔 계정 쿠키를 WebView에 넣어 그 계정으로 페이지를 연다. */
    private fun injectCookie(cookie: String) {
        val manager = CookieManager.getInstance()
        for ((name, value) in cookieValues(cookie)) {
            if (name.isEmpty()) continue
            for (url in listOf("https://www.hoyolab.com", "https://act.hoyolab.com")) {
                manager.setCookie(url, "$name=$value; Domain=.hoyolab.com; Path=/; Secure")
            }
        }
        manager.flush()
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
