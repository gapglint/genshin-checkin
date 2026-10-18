package com.lesumein.hoyocheckin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

/**
 * 호요랩 출첵 페이지를 WebView로 연다.
 * 로그인 모드: 먼저 지금 로그인 상태를 확인한다. 로그인돼 있으면 그렇다고 알려 주고,
 *   아니면 페이지의 로그인 창을 띄운다. 아이디·비밀번호 입력과 캡차는 사람이 하고,
 *   유효한 로그인 쿠키가 생기면 저장한 뒤 닫는다.
 * 페이지 모드: 캡차 때문에 자동 출첵이 막혔을 때 직접 출첵하도록 페이지만 띄운다.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val loginMode by lazy { intent.getBooleanExtra(EXTRA_LOGIN, true) }
    /** 마지막으로 서버에 유효성을 물어본 쿠키. 같은 쿠키는 다시 묻지 않아 요청이 몰리지 않는다. */
    private var checkedCookie: String? = null
    private var loginPopupOpened = false

    /** 로그인 페이지를 띄운 뒤, 사람이 로그인해서 쿠키가 바뀌는지 1초마다 본다. */
    private val pollCookie = object : Runnable {
        override fun run() {
            val cookie = loginCookie()
            if (cookie == null || cookie == checkedCookie) {
                handler.postDelayed(this, 1000)
                return
            }
            checkedCookie = cookie
            validate(cookie) { valid ->
                when (valid) {
                    true -> onLoggedIn(cookie, alreadyLoggedIn = false)
                    // 만료된 옛 쿠키. 사람이 새로 로그인해 쿠키가 바뀔 때까지 기다린다.
                    false -> handler.postDelayed(this, 1000)
                    // 네트워크 오류. 잠시 뒤 같은 쿠키로 다시 묻는다.
                    null -> { checkedCookie = null; handler.postDelayed(this, 5000) }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = if (loginMode) "호요랩 로그인" else "원신 출석체크"

        status = TextView(this).apply {
            text = if (loginMode) "로그인 상태 확인 중..." else "오늘 출석 칸을 눌러 직접 출첵해 주세요. 캡차가 나오면 풀면 됩니다."
            setPadding(32, 24, 32, 24)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
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
            visibility = if (loginMode) View.GONE else View.VISIBLE
        }
        // 로그인 창은 다른 도메인(account.hoyolab.com) iframe이라 서드파티 쿠키가 필요하다.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (loginMode) checkCurrentLogin() else webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
    }

    override fun onPause() {
        super.onPause()
        // 페이지 모드에서 다시 로그인했을 수도 있으니 나갈 때 쿠키를 갱신해 둔다.
        if (!loginMode) loginCookie()?.let { Store(this).cookie = it }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    /** 페이지를 띄우기 전에, WebView에 남은 쿠키로 이미 로그인돼 있는지 확인한다. */
    private fun checkCurrentLogin() {
        val cookie = loginCookie() ?: return showLoginPage()
        checkedCookie = cookie
        validate(cookie) { valid ->
            when (valid) {
                true -> onLoggedIn(cookie, alreadyLoggedIn = true)
                false -> showLoginPage()
                null -> {
                    checkedCookie = null
                    status.text = "로그인 상태를 확인하지 못했어요. 네트워크를 확인해 주세요."
                    handler.postDelayed({ checkCurrentLogin() }, 5000)
                }
            }
        }
    }

    private fun showLoginPage() {
        status.text = "아이디·비밀번호로 로그인하고 캡차를 풀어 주세요. 로그인되면 자동으로 닫혀요.\n로그인 창이 안 뜨면 왼쪽 위 「로그인」을 누르세요."
        status.gravity = Gravity.START
        status.textSize = 13f
        webView.visibility = View.VISIBLE
        webView.loadUrl(CheckInClient.CHECK_IN_PAGE)
        handler.postDelayed(pollCookie, 1000)
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

    private fun onLoggedIn(cookie: String, alreadyLoggedIn: Boolean) {
        CookieManager.getInstance().flush()
        Store(this).cookie = cookie
        CheckIn.schedule(this, restart = !alreadyLoggedIn)
        setResult(RESULT_OK)
        if (!alreadyLoggedIn) {
            Toast.makeText(this, "로그인 완료! 이제 자동으로 출첵해요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        status.text = "로그인돼 있어요."
        AlertDialog.Builder(this)
            .setTitle("이미 로그인돼 있어요")
            .setMessage("호요랩 UID ${uidOf(cookie) ?: "확인 불가"} 계정으로 자동 출첵 중이에요.")
            .setPositiveButton("확인") { _, _ -> finish() }
            .setNegativeButton("다른 계정으로 로그인") { _, _ -> switchAccount() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** 지금 로그인을 지우고 처음부터 로그인한다. */
    private fun switchAccount() {
        CheckIn.cancel(this)
        Store(this).clear()
        status.text = "로그아웃하는 중..."
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            checkedCookie = null
            showLoginPage()
        }
    }

    companion object {
        private const val EXTRA_LOGIN = "login"

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

        fun loginIntent(context: Context) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_LOGIN, true)

        fun checkInPageIntent(context: Context) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_LOGIN, false)

        private fun cookieValues(cookie: String): Map<String, String> =
            cookie.split(";").associate { it.substringBefore("=").trim() to it.substringAfter("=", "").trim() }

        internal fun hasLoginTokens(cookie: String): Boolean {
            val names = cookieValues(cookie).keys
            return "ltoken_v2" in names && "ltuid_v2" in names
        }

        internal fun uidOf(cookie: String): String? = cookieValues(cookie)["ltuid_v2"]?.takeIf { it.isNotEmpty() }
    }
}
