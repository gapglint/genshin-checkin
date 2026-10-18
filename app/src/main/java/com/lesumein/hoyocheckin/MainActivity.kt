package com.lesumein.hoyocheckin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var screen: MainScreen
    private var busy: String? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        store = Store(this)
        screen = MainScreen(findViewById(R.id.scroll))
        applySystemBarInsets(findViewById(R.id.content))

        screen.primaryButton.setOnClickListener {
            if (store.cookie == null) startActivity(LoginActivity.loginIntent(this)) else checkInNow()
        }
        screen.makeUpButton.setOnClickListener { makeUpNow() }
        screen.rowMakeUpAlerts.setOnClickListener { screen.makeUpSwitch.toggle() }
        screen.makeUpSwitch.setOnCheckedChangeListener { _, checked -> store.makeUpAlerts = checked }
        screen.rowOpenPage.setOnClickListener { startActivity(LoginActivity.checkInPageIntent(this)) }
        screen.rowAccount.setOnClickListener { startActivity(LoginActivity.loginIntent(this)) }
        screen.rowLogout.setOnClickListener { confirmLogout() }

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 앱 업데이트 등으로 예약이 사라졌어도 다시 건다. 이미 있으면 그대로 둔다.
        if (store.cookie != null) CheckIn.schedule(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 보충 출석 알림을 눌러 들어왔으면 바로 보충한다. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_MAKE_UP, false) == true) {
            intent.removeExtra(EXTRA_MAKE_UP)
            makeUpNow()
        }
    }

    private fun refresh() {
        val cookie = store.cookie
        val today = Store.checkInDay()
        val signedToday = store.signedDay == today.toString()
        // 지난달 기록이면 이번 달 누적은 0부터 다시 센다.
        val sameMonth = store.signedDay?.startsWith(today.toString().substring(0, 7)) == true
        screen.render(
            MainUiState(
                loggedIn = cookie != null,
                uid = cookie?.let { LoginActivity.uidOf(it) },
                signedToday = signedToday,
                totalDays = if (sameMonth) store.signedTotal else 0,
                month = today.monthValue,
                reward = if (signedToday) store.lastReward else null,
                lastChecked = store.lastRunAt.takeIf { it > 0 }
                    ?.let { DateFormat.format("M월 d일 HH:mm", Date(it)).toString() },
                note = if (!signedToday) store.lastResult else null,
                makeUpMissed = store.makeUpMissed.takeIf { store.makeUpDay == today.toString() },
                makeUpAlerts = store.makeUpAlerts,
                busy = busy,
            )
        )
    }

    private fun checkInNow() = runInBackground("출석하는 중...") {
        CheckIn.run(this)?.let { CheckIn.describe(it) }
    }

    private fun makeUpNow() = runInBackground("보충 출석 중...") {
        val result = MakeUp.perform(this)
        runOnUiThread { showMakeUpResult(result) }
        null
    }

    /** 네트워크 작업을 백그라운드에서 돌리는 동안 버튼을 막고 진행 안내를 보여 준다. 반환한 문장은 토스트로. */
    private fun runInBackground(label: String, work: () -> String?) {
        if (busy != null) return
        busy = label
        refresh()
        Thread {
            val message = try {
                work()
            } catch (e: IOException) {
                "네트워크 오류: ${e.message}"
            }
            runOnUiThread {
                busy = null
                refresh()
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showMakeUpResult(result: MakeUpResult) {
        val title = if (result is MakeUpResult.Done) "보충 출석 완료" else "보충 출석"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(MakeUp.describe(result))
            .setPositiveButton("확인", null)
            .apply {
                if (result == MakeUpResult.LoginExpired) {
                    setNeutralButton("로그인") { _, _ -> startActivity(LoginActivity.loginIntent(this@MainActivity)) }
                }
            }
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("로그아웃할까요?")
            .setMessage("저장된 로그인을 지우고 자동 출석을 멈춰요. 다시 쓰려면 로그인하면 돼요.")
            .setPositiveButton("로그아웃") { _, _ ->
                CheckIn.cancel(this)
                store.clear()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        private const val EXTRA_MAKE_UP = "make_up"

        fun makeUpIntent(context: Context) = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_MAKE_UP, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** 화면을 시스템 바 아래까지 그리고, 내용은 상태 바·내비게이션 바를 피하게 한다. */
        fun applySystemBarInsets(view: View) {
            val start = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.updatePadding(
                    left = start.left + bars.left, top = start.top + bars.top,
                    right = start.right + bars.right, bottom = start.bottom + bars.bottom,
                )
                insets
            }
        }
    }
}
