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
import java.io.IOException
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * 호요랩 출첵 페이지를 WebView로 연다.
 * 로그인 모드: 아이디·비밀번호 입력과 캡차는 사람이 하고, 유효한 로그인 쿠키가 생기면 저장한 뒤 닫는다.
 *   이미 로그인돼 있으면 쿠키가 유효한지만 확인하고 바로 닫는다.
 * 페이지 모드: 캡차 때문에 자동 출첵이 막혔을 때 직접 출첵하도록 페이지만 띄운다.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val loginMode by lazy { intent.getBooleanExtra(EXTRA_LOGIN, true) }
    /** 마지막으로 서버에 유효성을 물어본 쿠키. 같은 쿠키는 다시 묻지 않아 요청이 몰리지 않는다. */
    private var checkedCookie: String? = null

    private val pollCookie = object : Runnable {
        override fun run() {
            val cookie = loginCookie()
            if (cookie == null || cookie == checkedCookie) {
                handler.postDelayed(this, 1000)
                return
            }
            checkedCookie = cookie
            Thread {
                val valid = try {
                    CheckInClient(cookie).isLoggedIn()
                } catch (e: IOException) {
                    null
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (valid) {
                        true -> onLoggedIn(cookie)
                        // 만료된 옛 쿠키. 사람이 새로 로그인해 쿠키가 바뀔 때까지 기다린다.
                        false -> handler.postDelayed(this, 1000)
                        // 네트워크 오류. 잠시 뒤 같은 쿠키로 다시 묻는다.
                        null -> { checkedCookie = null; handler.postDelayed(this, 5000) }
                    }
                }
            }.start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (loginMode) "호요랩 로그인" else "원신 출석체크"

        val cookies = CookieManager.getInstance()

        val hint = TextView(this).apply {
            text = if (loginMode) {
                "아이디·비밀번호로 로그인하고 캡차를 풀어 주세요. 로그인되면 자동으로 닫혀요.\n이미 로그인돼 있으면 확인 후 바로 닫혀요. 로그인 창이 안 보이면 페이지의 출석 칸을 아무거나 누르세요."
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
        if (!loginMode) loginCookie()?.let { Store(this).cookie = it }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollCookie)
        webView.destroy()
        super.onDestroy()
    }

    /** WebView에 호요랩 로그인 쿠키가 있으면 그 쿠키 문자열. HttpOnly 쿠키도 여기서는 읽힌다. */
    private fun loginCookie(): String? =
        CookieManager.getInstance().getCookie("https://www.hoyolab.com")?.takeIf { hasLoginTokens(it) }

    private fun onLoggedIn(cookie: String) {
        CookieManager.getInstance().flush()
        val store = Store(this)
        val message = if (store.cookie == cookie) "이미 로그인돼 있어요." else "로그인 완료! 이제 자동으로 출첵해요."
        store.cookie = cookie
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        CheckIn.schedule(this, restart = true)
        setResult(RESULT_OK)
        finish()
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
