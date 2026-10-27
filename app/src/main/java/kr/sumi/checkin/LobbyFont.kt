package kr.sumi.checkin

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.FontRequest
import android.provider.FontsContract
import android.util.Base64

/**
 * 로비의 이름·대사·메뉴 제목에 쓰는 나눔명조(굵게). 앱에 넣지 않고 구글 글꼴 제공자(구글 플레이 서비스)에서
 * 필요할 때 받는다(Downloadable Fonts, 앱 용량 0KB, 다른 앱과 같이 쓴다). 받기 전이나 받을 수 없으면 null이고 기본 글씨체로 보인다.
 */
object LobbyFont {
    @Volatile var title: Typeface? = null
        private set
    private var requested = false
    /** 받으면 부를 것. 화면이 다시 만들어지면(회전 등) 마지막 화면 것으로 바뀐다. */
    private var ready: (() -> Unit)? = null
    /** 못 받은 때. 화면을 그릴 때마다 묻지 않게 10분은 기다린다. */
    private var failedAt = 0L

    /** 아직 없으면 한 번 받아 둔다. 받으면 onReady(화면을 다시 그린다). */
    fun load(context: Context, onReady: () -> Unit) {
        if (title != null) return
        ready = onReady
        if (requested || System.currentTimeMillis() - failedAt < 10 * 60_000L) return
        requested = true
        val res = context.resources
        // 구글 글꼴 제공자의 서명(개발용·배포용). Google 공식 예제의 font_certs.xml 그대로.
        val certs = listOf(R.array.com_google_android_gms_fonts_certs_dev, R.array.com_google_android_gms_fonts_certs_prod).map { id ->
            res.getStringArray(id).map { Base64.decode(it.trim(), Base64.DEFAULT) }
        }
        val request = FontRequest("com.google.android.gms.fonts", "com.google.android.gms", "name=Nanum Myeongjo&weight=800", certs)
        try {
            FontsContract.requestFonts(context.applicationContext, request, Handler(Looper.getMainLooper()), null,
                object : FontsContract.FontRequestCallback() {
                    override fun onTypefaceRetrieved(typeface: Typeface) {
                        title = typeface
                        ready?.invoke()
                        ready = null
                    }

                    override fun onTypefaceRequestFailed(reason: Int) {
                        // 구글 플레이 서비스가 없거나 받을 수 없으면 기본 글씨체로 둔다(다음에 화면을 그릴 때 다시 시도).
                        requested = false
                        failedAt = System.currentTimeMillis()
                    }
                })
        } catch (e: Exception) {
        }
    }
}
