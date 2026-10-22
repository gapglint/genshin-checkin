package kr.sumi.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException

/**
 * 복각표: 5성 캐릭터마다 마지막 픽업 뒤 몇 버전이 지났는지. 오래된 순으로, 같은 버전 수끼리 묶는다.
 * 그림 없이 이름·원소 색·버전만 보여 준다(자료는 위키의 기원 기록, Reruns 참고).
 */
class RerunActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = "복각표"
        header.findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
        status = header.findViewById(R.id.headerStatus)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@RerunActivity).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)
        render()
        // 하루 한 번만 새로 받는다. 저장된 자료가 없으면 바로 받는다.
        if (Reruns.cached(this) == null || !Reruns.isFresh(this)) Thread {
            val error = try {
                Reruns.refresh(this, force = Reruns.cached(this) == null); null
            } catch (e: IOException) {
                e.message ?: "네트워크 오류"
            }
            runOnUiThread { if (!isFinishing) render(error) }
        }.start()
    }

    private fun render(error: String? = null) {
        list.removeAllViews()
        val data = Reruns.cached(this)
        val today = ServerClock.today()
        if (data == null) {
            status.text = error?.let { "불러오지 못했어요: $it" } ?: "불러오는 중..."
            return
        }
        val current = data.current(today)
        status.text = "마지막 픽업 뒤 지난 버전 수예요. 오래될수록 복각이 가까울 수 있어요." +
            (current?.let { "\n지금 버전: ${it.label}" } ?: "")

        RerunList(this).fill(list, data, today)
    }

    companion object {
        fun intent(context: Context) = Intent(context, RerunActivity::class.java)

        /** 오래될수록 진한 색: 지금·1~2 초록, 3~5 금색, 6~8 주황, 9 이상 빨강. */
        fun tone(since: Int) = when {
            since <= 2 -> R.color.rerun_recent
            since <= 5 -> R.color.rerun_mid
            since <= 8 -> R.color.rerun_old
            else -> R.color.rerun_oldest
        }

        fun elementColor(element: String) = when (element) {
            "Anemo" -> R.color.element_anemo
            "Geo" -> R.color.element_geo
            "Electro" -> R.color.element_electro
            "Dendro" -> R.color.element_dendro
            "Hydro" -> R.color.element_hydro
            "Pyro" -> R.color.element_pyro
            "Cryo" -> R.color.element_cryo
            else -> R.color.ink_muted
        }

        fun elementName(element: String) = when (element) {
            "Anemo" -> "바람"
            "Geo" -> "바위"
            "Electro" -> "번개"
            "Dendro" -> "풀"
            "Hydro" -> "물"
            "Pyro" -> "불"
            "Cryo" -> "얼음"
            else -> element
        }
    }
}

/** 복각표 목록을 그린다(화면 스냅숏 테스트에서도 쓰려고 액티비티와 나눴다). */
class RerunList(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun getColor(id: Int) = context.getColor(id)
    private fun getDrawable(id: Int) = context.getDrawable(id)
    private lateinit var list: LinearLayout

    fun fill(list: LinearLayout, data: RerunData, today: java.time.LocalDate) {
        this.list = list
        section(data, data.ranked(today, standard = false))
        val standard = data.ranked(today, standard = true)
        if (standard.isNotEmpty()) {
            list.addView(text("상시 캐릭터 (상시 기원에서도 나와요)", 14f, R.color.ink_muted, bold = true).apply {
                setPadding(dp(4), dp(24), 0, dp(4))
            })
            section(data, standard)
        }
        list.addView(text(
            "자료: Genshin Impact Wiki의 기원 기록(CC BY-SA)을 하루 한 번 정리해요. 비공식 정보라 실제 복각 일정은 다를 수 있어요.",
            12f, R.color.ink_muted,
        ).apply { setPadding(dp(4), dp(20), dp(4), 0) })
    }

    /** 같은 버전 수끼리 한 묶음(카드)으로. */
    private fun section(data: RerunData, ranked: List<Pair<RerunCharacter, Int>>) {
        for ((since, group) in ranked.groupBy { it.second }) {
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(16), 0, dp(8))
                addView(TextView(context).apply {
                    text = if (since == 0) "지금 픽업" else "${since}버전 전"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.on_action))
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(getColor(RerunActivity.tone(since))) }
                })
                addView(text("${group.size}명", 12f, R.color.ink_muted).apply { setPadding(dp(8), 0, 0, 0) })
            })
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_group)
                clipToOutline = true
                dividerDrawable = getDrawable(R.drawable.divider_inset)
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                for ((c, _) in group) addView(row(data, c))
            })
        }
    }

    private fun row(data: RerunData, c: RerunCharacter) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        contentDescription = "${c.ko}, ${RerunActivity.elementName(c.element)}, 마지막 픽업 ${data.label(c.last)}"
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(RerunActivity.elementColor(c.element))) }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
        addView(text(c.ko, 15f, R.color.ink), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (c.chronicled) addView(text("집대성", 11f, R.color.accent).apply { setPadding(0, 0, dp(8), 0) })
        addView(text(data.label(c.last), 13f, R.color.ink_muted))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
