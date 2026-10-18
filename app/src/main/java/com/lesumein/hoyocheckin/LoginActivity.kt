package com.lesumein.hoyocheckin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * 호요랩 출첵 페이지를 WebView로 연다.
 * 로그인 모드: 아이디·비밀번호 입력과 캡차는 사람이 하고, 로그인 쿠키가 생기면 저장한 뒤 닫는다.
 * 페이지 모드: 캡차 때문에 자동 출첵이 막혔을 때 직접 출첵하도록 페이지만 띄운다.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val loginMode by lazy { intent.getBooleanExtra(EXTRA_LOGIN, true) }
    private var saved = false

    private val pollCookie = object : Runnable {
        override fun run() {
            if (saveCookieIfLoggedIn()) {
                Toast.makeText(this@LoginActivity, "로그인 완료! 이제 자동으로 출첵해요.", Toast.LENGTH_LONG).show()
                CheckIn.schedule(this@LoginActivity)
                CheckIn.runSoon(this@LoginActivity)
                setResult(RESULT_OK)
                finish()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (loginMode) "호요랩 로그인" else "원신 출석체크"

        val cookies = CookieManager.getInstance()
        if (loginMode) {
            // 만료된 쿠키가 남아 있으면 로그인 전에 바로 '성공'으로 판단하므로 먼저 지운다.
            cookies.removeAllCookies(null)
            cookies.flush()
        }

        val hint = TextView(this).apply {
            text = if (loginMode) {
                "아이디·비밀번호로 로그인하고 캡차를 풀어 주세요. 로그인되면 자동으로 닫혀요.\n로그인 창이 안 보이면 페이지의 출석 칸을 아무거나 누르세요."
            } else {
                "오늘 출석 칸을 눌러 직접 출첵해 주세요. 캡차가 나오면 풀면 됩니다."
            }
            setPadding(32, 24, 32, 24)
            textSize = 13f
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 일부 로그인 화면은 WebView 표시(wv)를 보고 막기 때문에 일반 모바일 크롬처럼 보이게 한다.
            settings.userAgentString = CheckInClient.USER_AGENT
            webViewClient = WebViewClient()
        }
        // 로그인 창은 다른 도메인(account.hoyolab.com) iframe이라 서드파티 쿠키가 필요하다.
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
        if (loginMode) handler.postDelayed(pollCookie, 1000)
    }

    override fun onPause() {
        super.onPause()
        // 페이지 모드에서 다시 로그인했을 수도 있으니 나갈 때 쿠키를 갱신해 둔다.
        if (!loginMode) saveCookieIfLoggedIn()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollCookie)
        webView.destroy()
        super.onDestroy()
    }

    /** WebView에 호요랩 로그인 쿠키가 있으면 저장하고 true. HttpOnly 쿠키도 여기서는 읽힌다. */
    private fun saveCookieIfLoggedIn(): Boolean {
        if (saved && loginMode) return true
        val cookie = CookieManager.getInstance().getCookie("https://www.hoyolab.com") ?: return false
        if (!hasLoginTokens(cookie)) return false
        CookieManager.getInstance().flush()
        Store(this).cookie = cookie
        saved = true
        return true
    }

    companion object {
        private const val EXTRA_LOGIN = "login"

        fun loginIntent(context: Context) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_LOGIN, true)

        fun checkInPageIntent(context: Context) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_LOGIN, false)

        internal fun hasLoginTokens(cookie: String): Boolean {
            val names = cookie.split(";").map { it.substringBefore("=").trim() }.toSet()
            return "ltoken_v2" in names && "ltuid_v2" in names
        }
    }
}
