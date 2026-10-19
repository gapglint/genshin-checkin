package kr.sumi.logintest

import android.content.Context
import android.os.Build
import android.webkit.WebView

/** 방식별 결과를 휴대폰 안에만 저장한다. 쿠키 값·아이디·비밀번호는 저장하지 않는다. */
class Results(context: Context) {
    private val prefs = context.getSharedPreferences("results", Context.MODE_PRIVATE)

    fun summary(id: String): String? = prefs.getString("summary_$id", null)
    fun detail(id: String): String? = prefs.getString("detail_$id", null)

    fun save(id: String, summary: String, detail: String) {
        prefs.edit().putString("summary_$id", summary).putString("detail_$id", detail).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** 개발자에게 보낼 전체 결과. */
    fun report(): String = buildString {
        val webView = WebView.getCurrentWebViewPackage()
        appendLine("원신 출첵 로그인 테스트 ${BuildConfigInfo.VERSION}")
        appendLine("기기 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("칩 ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}")
        appendLine("웹뷰 ${webView?.packageName} ${webView?.versionName}")
        for (s in Strategy.ALL) {
            appendLine()
            appendLine("[${s.id}] ${summary(s.id) ?: "안 해 봄"}")
            detail(s.id)?.let { append(it) }
        }
    }
}

object BuildConfigInfo {
    const val VERSION = "1"
}
