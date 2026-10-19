package kr.sumi.checkin

import android.content.Context
import android.content.Intent

/**
 * 원신 게임 앱. 패키지 이름으로 찾으므로 플레이스토어·갤럭시 스토어·공식 사이트 어디서 깔았든 같다.
 * 깔려 있지 않으면 null이고, 그때는 '원신 켜기'를 아예 보이지 않는다(특정 스토어로 보내지 않는다).
 */
object Game {
    private const val PACKAGE = "com.miHoYo.GenshinImpact"

    fun launchIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)
}
