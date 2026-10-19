package kr.sumi.checkin

import android.content.Context
import android.content.Intent

/** 깔려 있는 원신 하나. */
data class GameApp(val packageName: String, val label: String)

/**
 * 원신 게임 앱. 스토어마다 패키지 이름이 달라서(플레이스토어·공식 사이트 APK, 갤럭시 스토어) 깔려 있는 쪽을 연다.
 * 둘 다 깔려 있으면 설정에서 고른 쪽, 고르지 않았으면 먼저 찾은 쪽.
 * 아무것도 없으면 '원신 켜기'를 아예 보이지 않는다(특정 스토어로 보내지 않는다).
 * 새 이름을 넣으면 매니페스트의 queries에도 넣어야 안드로이드 11부터 보인다.
 */
object Game {
    private val PACKAGES = listOf(
        "com.miHoYo.GenshinImpact" to "플레이스토어·공식 사이트에서 받은 원신",
        "com.miHoYo.GI.samsung" to "갤럭시 스토어에서 받은 원신",
    )

    fun installed(context: Context): List<GameApp> = PACKAGES
        .filter { (name, _) -> context.packageManager.getLaunchIntentForPackage(name) != null }
        .map { (name, label) -> GameApp(name, label) }

    fun chosen(context: Context): GameApp? {
        val apps = installed(context)
        return apps.firstOrNull { it.packageName == AppSettings(context).gamePackage } ?: apps.firstOrNull()
    }

    fun launchIntent(context: Context): Intent? =
        chosen(context)?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
}
