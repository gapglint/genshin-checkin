package kr.sumi.checkin.logintest

import android.content.Context
import android.os.Build
import android.webkit.WebView

/**
 * 로그인이 안 되는 폰에서 여러 방식을 시험해 보는 임시 화면(로그인 화면의 '다른 방법 시험하기'로 연다).
 * 원인을 찾으면 이 폴더와 매니페스트의 세 화면을 지운다.
 *
 * 방식별 결과를 휴대폰 안에만 저장한다. 쿠키 값·아이디·비밀번호는 저장하지 않는다. */
class Results(private val context: Context) {
    private val prefs = context.getSharedPreferences("login_test", Context.MODE_PRIVATE)

    fun summary(id: String): String? = prefs.getString("summary_$id", null)
    fun detail(id: String): String? = prefs.getString("detail_$id", null)

    fun save(id: String, summary: String, detail: String) {
        prefs.edit().putString("summary_$id", summary).putString("detail_$id", detail).apply()
    }

    /** 본 로그인 화면에서 남긴 기록. */
    fun saveLoginTrail(detail: String) = prefs.edit().putString("login_trail", detail).apply()

    fun clear() = prefs.edit().clear().apply()

    /** 개발자에게 보낼 전체 결과. */
    fun report(): String = buildString {
        val webView = WebView.getCurrentWebViewPackage()
        appendLine("원신 출첵 ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} 로그인 테스트")
        appendLine("기기 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("칩 ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}")
        appendLine("웹뷰 ${webView?.packageName} ${webView?.versionName}")
        prefs.getString("login_trail", null)?.let {
            appendLine()
            appendLine("[본 로그인 화면]")
            append(it)
        }
        for (s in Strategy.ALL) {
            appendLine()
            appendLine("[${s.id}] ${summary(s.id) ?: "안 해 봄"}")
            detail(s.id)?.let { append(it) }
        }
    }
}
