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
 * 캐릭터 그림 없이 원소 색·이름·버전만으로 그린다(자료는 위키의 기원 기록, Reruns 참고).
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

        /** 원소를 떠올리게 하는 직접 그린 아이콘(불꽃·물방울·번개·눈송이·바람·보석·잎). */
        fun elementIcon(element: String) = when (element) {
            "Anemo" -> R.drawable.ic_element_anemo
            "Geo" -> R.drawable.ic_element_geo
            "Electro" -> R.drawable.ic_element_electro
            "Dendro" -> R.drawable.ic_element_dendro
            "Hydro" -> R.drawable.ic_element_hydro
            "Pyro" -> R.drawable.ic_element_pyro
            "Cryo" -> R.drawable.ic_element_cryo
            else -> null
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

/**
 * 복각표 목록을 그린다(화면 스냅숏 테스트에서도 쓰려고 액티비티와 나눴다).
 * 맨 위는 가장 오래 기다린 캐릭터를 출석 카드처럼 큰 숫자로, 아래는 같은 버전 수끼리 캐릭터 동그라미를 격자로 놓는다.
 * 캐릭터 그림 대신 원소 색 동그라미에 원소 아이콘(불꽃·물방울 등)을 넣는다.
 */
class RerunList(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = context.getColor(id)
    private val numerals: Typeface = context.resources.getFont(R.font.hoyo_numerals)
    private lateinit var list: LinearLayout

    fun fill(list: LinearLayout, data: RerunData, today: java.time.LocalDate) {
        this.list = list
        val ranked = data.ranked(today, standard = false)
        ranked.firstOrNull()?.let { (_, top) -> list.addView(hero(data, top, ranked.filter { it.second == top }.map { it.first })) }
        list.addView(text("아이콘은 원소, 금색 테두리는 집대성 기원으로 나온 캐릭터예요.", 12f, R.color.ink_muted).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        for ((since, group) in ranked.groupBy { it.second }.filterKeys { it != ranked.firstOrNull()?.second }) {
            section(since, group.map { it.first })
        }
        val standard = data.ranked(today, standard = true)
        if (standard.isNotEmpty()) {
            list.addView(text("상시 캐릭터", 16f, R.color.ink, bold = true).apply { setPadding(dp(4), dp(28), 0, 0) })
            list.addView(text("상시 기원에서도 나와요", 12f, R.color.ink_muted).apply { setPadding(dp(4), dp(2), 0, 0) })
            for ((since, group) in standard.groupBy { it.second }) section(since, group.map { it.first })
        }
        list.addView(text(
            "자료: Genshin Impact Wiki의 기원 기록(CC BY-SA)을 하루 한 번 정리해요. 비공식 정보라 실제 복각 일정은 다를 수 있어요.",
            12f, R.color.ink_muted,
        ).apply { setPadding(dp(4), dp(24), dp(4), 0) })
    }

    /** 가장 오래 기다린 캐릭터: 남색 카드에 금색 큰 숫자(출석 카드와 같은 모양). */
    private fun hero(data: RerunData, since: Int, characters: List<RerunCharacter>) = android.widget.FrameLayout(context).apply {
        setBackgroundResource(R.drawable.bg_hero)
        clipToOutline = true
        addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.hero_motif)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, android.widget.FrameLayout.LayoutParams(dp(160), dp(160), Gravity.TOP or Gravity.END).apply { setMargins(0, -dp(24), -dp(32), 0) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(text("가장 오래 기다린 캐릭터", 13f, R.color.hero_muted))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(TextView(context).apply {
                    text = "$since"
                    textSize = 64f
                    typeface = numerals
                    includeFontPadding = false
                    setTextColor(color(R.color.gold))
                })
                addView(text("버전째", 20f, R.color.hero_ink).apply { setPadding(dp(8), 0, 0, dp(8)) })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
                for (c in characters.take(4)) addView(token(data, c, onDark = true), LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
            })
            if (characters.size > 4) addView(text("외 ${characters.size - 4}명", 12f, R.color.hero_muted).apply { setPadding(0, dp(4), 0, 0) })
        })
        contentDescription = "가장 오래 기다린 캐릭터: ${characters.joinToString { it.ko }}, ${since}버전째"
    }

    /** 같은 버전 수 한 묶음: 큰 숫자 머리말 + 캐릭터 동그라미 격자(한 줄에 4명). */
    private fun section(since: Int, characters: List<RerunCharacter>) {
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(4), dp(22), 0, dp(10))
            if (since == 0) {
                addView(text("지금 픽업 중", 18f, RerunActivity.tone(0), bold = true))
            } else {
                addView(TextView(context).apply {
                    text = "$since"
                    textSize = 30f
                    typeface = numerals
                    includeFontPadding = false
                    setTextColor(color(RerunActivity.tone(since)))
                })
                addView(text("버전 전", 15f, R.color.ink, bold = true).apply { setPadding(dp(6), 0, 0, dp(3)) })
            }
            addView(text("${characters.size}명", 12f, R.color.ink_muted).apply { setPadding(dp(8), 0, 0, dp(4)) })
        })
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_group)
            setPadding(dp(8), dp(14), dp(8), dp(6))
            for (row in characters.chunked(4)) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in row) addView(token(null, c, onDark = false), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                repeat(4 - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        })
    }

    /** 캐릭터 동그라미: 원소 색으로 칠하고 원소 아이콘, 아래에 짧은 이름(과 마지막 픽업 버전). 집대성으로 나온 캐릭터는 금색 테두리. */
    private fun token(data: RerunData?, c: RerunCharacter, onDark: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val short = shortName(c.ko)
        val base = color(RerunActivity.elementColor(c.element))
        addView(android.widget.ImageView(context).apply {
            RerunActivity.elementIcon(c.element)?.let { setImageResource(it) }
            setPadding(dp(13), dp(13), dp(13), dp(13))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(lighten(base), base),
            ).apply {
                shape = GradientDrawable.OVAL
                if (c.chronicled) setStroke(dp(2), color(R.color.gold))
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(text(short, 13f, if (onDark) R.color.hero_ink else R.color.ink).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        data?.let {
            addView(text(it.label(c.last), 11f, if (onDark) R.color.hero_muted else R.color.ink_muted).apply { gravity = Gravity.CENTER })
        }
        contentDescription = "${c.ko}, ${RerunActivity.elementName(c.element)}" + (if (c.chronicled) ", 집대성" else "")
    }

    /** '카에데하라 카즈하' → '카즈하'처럼 성을 뗀 이름. 한 단어면 그대로. */
    private fun shortName(ko: String) = ko.trim().split(" ").last()

    private fun lighten(c: Int): Int {
        fun mix(v: Int) = v + ((255 - v) * 0.35f).toInt()
        return (0xFF shl 24) or (mix(c shr 16 and 0xFF) shl 16) or (mix(c shr 8 and 0xFF) shl 8) or mix(c and 0xFF)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
