package com.lesumein.hoyocheckin

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.webkit.CookieManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var loginStatus: TextView
    private lateinit var lastResult: TextView
    private lateinit var checkInButton: Button
    private lateinit var loginButton: Button

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        loginStatus = findViewById(R.id.loginStatus)
        lastResult = findViewById(R.id.lastResult)
        checkInButton = findViewById(R.id.checkInButton)

        loginButton = findViewById(R.id.loginButton)
        loginButton.setOnClickListener {
            startActivity(LoginActivity.loginIntent(this))
        }
        checkInButton.setOnClickListener { checkInNow() }
        findViewById<Button>(R.id.openPageButton).setOnClickListener {
            startActivity(LoginActivity.checkInPageIntent(this))
        }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { confirmLogout() }

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 앱 업데이트 등으로 예약이 사라졌어도 다시 건다. 이미 있으면 그대로 둔다.
        if (store.cookie != null) CheckIn.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        loginStatus.text = if (store.cookie != null) "로그인됨 · 자동 출첵 켜짐" else "로그인이 필요해요"
        checkInButton.isEnabled = store.cookie != null
        loginButton.text = if (store.cookie != null) "로그인 상태 확인" else "호요랩 로그인"
        val result = store.lastResult
        lastResult.text = if (result == null) {
            "아직 출첵 기록이 없어요."
        } else {
            val at = DateFormat.format("M월 d일 HH:mm", Date(store.lastRunAt))
            "마지막 확인: $at\n$result"
        }
    }

    private fun checkInNow() {
        checkInButton.isEnabled = false
        lastResult.text = "출첵하는 중..."
        Thread {
            val message = try {
                CheckIn.run(this)?.let { CheckIn.describe(it) }
            } catch (e: IOException) {
                "네트워크 오류: ${e.message}"
            }
            runOnUiThread {
                refresh()
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage("저장된 로그인 쿠키를 지우고 자동 출첵을 멈출까요?")
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
}
