package kr.sumi.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.IOException

/**
 * 고른 계정의 보상 내역. 알림함처럼 화면을 열면 확인한 것으로 치고, 이번에 처음 보는 보상만 표시한다.
 * 출석·보충 보상은 호요랩의 출석 보상 기록(20개씩 더 보기), 리딤 코드는 호요랩에 조회 기능이 없어
 * 이 앱이 받은 기록이고 보상은 위키에 적힌 값이다. 그리는 일은 RewardHistoryScreen이 한다.
 */
class RewardHistoryActivity : Activity() {
    private lateinit var store: Store
    private lateinit var screen: RewardHistoryScreen
    // 화면을 열 때의 확인 표시. 열자마자 확인한 것으로 저장하지만, 이번 화면에서는 이 기준으로 새 보상을 칠한다.
    private var seenAwardKey: String? = null
    private var seenRedeemAt = 0L
    private val entries = mutableListOf<RewardEntry>()
    private var total = 0
    private var page = 0
    private var loading = false
    private var status = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        store = intent.getStringExtra(EXTRA_ACCOUNT)?.let { AppSettings(this).account(it) } ?: return finish()
        status = getString(R.string.home_history_loading)

        screen = RewardHistoryScreen(this)
        screen.back.setOnClickListener { finish() }
        screen.more.setOnClickListener { loadPage() }
        screen.onCodeClick = { showCode(it) }
        setContentView(screen.root)
        MainActivity.applySystemBarInsets(screen.root)

        // 리딤 코드는 여는 순간 확인한 것으로 친다. 출석 보상은 첫 페이지를 받은 뒤에(기준이 있어야 해서).
        seenRedeemAt = store.historySeenRedeemAt
        store.historySeenRedeemAt = ServerClock.now()

        render()
        loadPage()
        Thread { if (Redeem.fillMissingRewards(store)) runOnUiThread { render() } }.start()
    }

    private fun loadPage() {
        val cookie = store.cookie ?: return
        if (loading) return
        loading = true
        status = getString(R.string.home_history_loading)
        screen.more.isEnabled = false
        render()
        Thread {
            val result = try {
                val json = CheckInClient(cookie).awardHistory(page + 1, PAGE_SIZE)
                if (json.optInt("retcode", -1) in CheckInClient.NOT_LOGGED_IN) {
                    Result.failure(IOException(getString(R.string.core_signed_out)))
                } else {
                    Result.success(json)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
            runOnUiThread {
                loading = false
                screen.more.isEnabled = true
                result.onSuccess { json ->
                    page++
                    val pageEntries = RewardHistory.parseAwards(json)
                    if (page == 1) {
                        RewardHistory.ensureBaseline(store, pageEntries)
                        seenAwardKey = store.historySeenAwardKey
                        pageEntries.firstOrNull()?.let { store.historySeenAwardKey = it.key }
                        store.historyUnseen = 0
                    }
                    entries += pageEntries
                    total = json.optJSONObject("data")?.optInt("total") ?: entries.size
                    status = resources.getQuantityString(R.plurals.home_history_status, total, "%,d".format(total))
                    // 이번 달 합계가 맞도록, 받아 온 목록이 아직 이번 달에서 끝나면 한 페이지 더 받는다.
                    val month = ServerClock.today().toString().take(7)
                    if (entries.size < total && entries.last().date.startsWith(month) && page < 3) {
                        screen.root.post { loadPage() }
                    }
                }.onFailure {
                    status = getString(R.string.home_history_load_failed, it.message ?: "").trim()
                }
                render()
            }
        }.start()
    }

    private fun render() {
        val unseenAwards = if (page > 0) RewardHistory.unseenAwards(entries, seenAwardKey) else 0
        screen.render(HistoryUiState(
            title = getString(R.string.home_history_title, store.displayName),
            status = status,
            month = ServerClock.today().toString().take(7),
            entries = entries.toList(),
            total = total,
            loaded = page > 0,
            codes = store.redeemLog,
            newAwardKeys = entries.take(unseenAwards).map { it.key }.toSet(),
            seenRedeemAt = seenRedeemAt,
        ))
    }

    /** 리딤 코드 상세: 받은 시각, 보상 전체, 코드 복사. */
    private fun showCode(code: RedeemedCode) {
        val items = code.reward?.let { Redeem.rewardItems(it) }.orEmpty()
        val rewards = if (items.isEmpty()) getString(R.string.home_history_no_reward_info)
        else items.joinToString("\n") { (name, count) -> "· $name ×${"%,d".format(count)}" }
        AppDialog(this)
            .setTitle(code.code)
            .setMessage(getString(R.string.home_history_received,
                android.text.format.DateFormat.format(getString(R.string.home_history_code_date_pattern), java.util.Date(code.at)).toString()) +
                "${code.role?.let { "\n$it" } ?: ""}\n\n$rewards")
            .setNeutralButton(getString(R.string.home_history_copy)) {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.home_history_clip_label), code.code))
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    android.widget.Toast.makeText(this, getString(R.string.home_history_copied), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(getString(R.string.home_history_ok))
            .show()
    }

    companion object {
        private const val EXTRA_ACCOUNT = "account"
        private const val PAGE_SIZE = RewardHistory.PAGE_SIZE

        fun intent(context: Context, accountId: String) =
            Intent(context, RewardHistoryActivity::class.java).putExtra(EXTRA_ACCOUNT, accountId)
    }
}
