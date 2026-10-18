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
    private var status = "불러오는 중..."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        store = intent.getStringExtra(EXTRA_ACCOUNT)?.let { AppSettings(this).account(it) } ?: return finish()

        screen = RewardHistoryScreen(this)
        screen.back.setOnClickListener { finish() }
        screen.more.setOnClickListener { loadPage() }
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
        status = "불러오는 중..."
        screen.more.isEnabled = false
        render()
        Thread {
            val result = try {
                val json = CheckInClient(cookie).awardHistory(page + 1, PAGE_SIZE)
                if (json.optInt("retcode", -1) in CheckInClient.NOT_LOGGED_IN) {
                    Result.failure(IOException("로그인이 풀렸어요. 다시 로그인해 주세요."))
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
                    status = "출석·보충 보상 총 ${"%,d".format(total)}번 받았어요"
                    // 이번 달 합계가 맞도록, 받아 온 목록이 아직 이번 달에서 끝나면 한 페이지 더 받는다.
                    val month = ServerClock.today().toString().take(7)
                    if (entries.size < total && entries.last().date.startsWith(month) && page < 3) {
                        screen.root.post { loadPage() }
                    }
                }.onFailure {
                    status = "내역을 불러오지 못했어요. ${it.message ?: ""}".trim()
                }
                render()
            }
        }.start()
    }

    private fun render() {
        val unseenAwards = if (page > 0) RewardHistory.unseenAwards(entries, seenAwardKey) else 0
        screen.render(HistoryUiState(
            title = "보상 내역 · ${store.displayName}",
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

    companion object {
        private const val EXTRA_ACCOUNT = "account"
        private const val PAGE_SIZE = RewardHistory.PAGE_SIZE

        fun intent(context: Context, accountId: String) =
            Intent(context, RewardHistoryActivity::class.java).putExtra(EXTRA_ACCOUNT, accountId)
    }
}
