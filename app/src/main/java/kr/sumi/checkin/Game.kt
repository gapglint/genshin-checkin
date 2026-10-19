package kr.sumi.checkin

import android.content.Context
import android.content.Intent

/**
 * 원신 게임 앱. 스토어마다 패키지 이름이 달라서(플레이스토어·공식 사이트 APK, 갤럭시 스토어) 깔려 있는 쪽을 연다.
 * 아무것도 없으면 null이고, 그때는 '원신 켜기'를 아예 보이지 않는다(특정 스토어로 보내지 않는다).
 * 새 이름을 넣으면 매니페스트의 queries에도 넣어야 안드로이드 11부터 보인다.
 */
object Game {
    private val PACKAGES = listOf(
        "com.miHoYo.GenshinImpact", // 플레이스토어, 공식 사이트
        "com.miHoYo.GI.samsung", // 갤럭시 스토어
    )

    fun launchIntent(context: Context): Intent? =
        PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
}
