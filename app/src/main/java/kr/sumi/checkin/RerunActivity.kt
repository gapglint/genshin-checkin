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
        // 설명과 지금 버전은 판 위에 있어서 머리말 아래 안내는 짧게.
        status.text = "오래 기다린 캐릭터일수록 복각이 가까울 수 있어요."

        val settings = AppSettings(this)
        RerunList(this, Reruns.watched(this), faces, { c -> toggleWatch(c) }, settings.rerunFours) {
            settings.rerunFours = !settings.rerunFours
            render()
        }.fill(list, data, today)
        loadFaces(data)
    }

    /** 얼굴 그림(공식 그림, 받아서 폰에만 저장). 저장된 것은 바로, 없는 것은 받아서 다 모이면 다시 그린다. */
    private val faces = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private var loadingFaces = false

    private fun loadFaces(data: RerunData) {
        if (loadingFaces || !Lobby.officialArtAllowed(this)) return
        val missing = (data.characters + if (AppSettings(this).rerunFours) data.fours else emptyList()).map { it.ko }.filter { !faces.containsKey(it) }
        if (missing.isEmpty()) return
        loadingFaces = true
        Thread {
            var added = 0
            for (ko in missing) {
                if (isDestroyed) break
                Lobby.face(this, ko)?.let { faces[ko] = it; added++ }
            }
            runOnUiThread { loadingFaces = false; if (added > 0 && !isDestroyed) render() }
        }.start()
    }

    /** 캐릭터를 누르면 픽업 알림을 켜고 끈다. 이미 올라온 픽업이 있으면 바로 한 번 알린다. */
    private fun toggleWatch(c: RerunCharacter) {
        val on = c.name !in Reruns.watched(this)
        Reruns.setWatched(this, c.name, on)
        // 로그인 전이면 기다리는 캐릭터 알림을 위해 6시간 확인 작업을 켜거나 끈다.
        PublicCodes.schedule(this)
        android.widget.Toast.makeText(
            this,
            if (on) "${c.ko} 픽업이 뜨면 알려 드릴게요" else "${c.ko} 픽업 알림을 껐어요",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        if (on) Reruns.checkWatch(this)
        render()
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

        /** 남색 바탕 위 이름 글자색: 원소 색을 밝게. */
        fun light(c: Int): Int {
            fun mix(v: Int) = v + ((255 - v) * 0.45f).toInt()
            return (0xFF shl 24) or (mix(c shr 16 and 0xFF) shl 16) or (mix(c shr 8 and 0xFF) shl 8) or mix(c and 0xFF)
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
 * 커뮤니티 복각표처럼 모든 캐릭터를 남색 판 위에 오래된 순으로 나란히 놓고, 지난 버전 수를 동그라미에 붙인다.
 * 오래 기다린 캐릭터일수록 숫자 색이 진하고 크게, 최근에 나온 캐릭터는 흐리게 그려 눈이 먼저 가게 한다.
 * 캐릭터 그림 대신 원소 색 동그라미에 원소 아이콘을 넣고, 이름도 원소 색으로 쓴다.
 */
class RerunList(
    private val context: Context,
    /** 픽업 알림을 받기로 한 캐릭터(종 표시). */
    private val watched: Set<String> = emptySet(),
    /** 공식 얼굴 그림(한국어 이름 → 그림). 없는 캐릭터는 원소 아이콘으로 그린다. */
    private val faces: Map<String, android.graphics.Bitmap> = emptyMap(),
    /** 캐릭터를 누르면 알림 받기를 켜고 끈다. null이면 누를 수 없다(스냅숏 등). */
    private val onToggle: ((RerunCharacter) -> Unit)? = null,
    /** 4성도 보일지(픽업 판과 '4성 캐릭터' 판). */
    private val showFours: Boolean = false,
    /** '4성도 보기'를 누르면. null이면 그 줄을 그리지 않는다. */
    private val onFours: (() -> Unit)? = null,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(id: Int) = context.getColor(id)
    private val numerals: Typeface = context.resources.getFont(R.font.hoyo_numerals)

    private var current: String? = null
    private var data: RerunData? = null
    private var today: java.time.LocalDate = java.time.LocalDate.now()
    private fun phase(c: RerunCharacter) = data?.phase(c, today) ?: RerunPhase.PAST

    fun fill(list: LinearLayout, data: RerunData, today: java.time.LocalDate) {
        current = data.current(today)?.v
        val live = data.live(today)
        this.data = data
        this.today = today
        pickups(data, live, today, "지금 픽업 중")?.let { list.addView(it) }
        pickups(data, data.upcoming(today), today, "다음 픽업")?.let {
            list.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        // '4성도 보기'는 찾기 쉽게 픽업 판 바로 아래(판들 위)에.
        val toggle = onFours != null && data.fours.isNotEmpty()
        if (toggle) list.addView(foursToggle(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = if (list.childCount == 0) 0 else dp(12) })
        list.addView(board(data, data.ranked(today, standard = false), "한정 캐릭터", legend = true, now = current?.let { data.label(it) }),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = if (live.isEmpty() && !toggle) 0 else dp(16) })
        val standard = data.ranked(today, standard = true)
        if (standard.isNotEmpty()) {
            list.addView(board(data, standard, "상시 캐릭터", legend = false, note = "상시 기원에서도 나와요").apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(16)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        val fours = data.rankedFours(today)
        if (showFours && fours.isNotEmpty()) {
            list.addView(board(data, fours, "4성 캐릭터", legend = false, note = "5성 픽업에 함께 나와요 · 숫자는 마지막 픽업 뒤 지난 버전"),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }

        list.addView(text(
            "자료: Genshin Impact Wiki의 기원 기록(CC BY-SA)을 하루 한 번 정리해요. 비공식 정보라 실제 복각 일정은 다를 수 있어요." +
                if (faces.isNotEmpty()) "\n캐릭터 그림 © HoYoverse · 호요랩 위키 · 비공식 팬 도구" else "",
            12f, R.color.ink_muted,
        ).apply { setPadding(dp(4), dp(20), dp(4), 0) })
    }

    /** 진행 중(또는 다음) 픽업: 금색 테두리 판에 캐릭터와 기간. 픽업이 없으면 null. */
    /** '4성도 보기' 줄(누르면 켜고 끈다). */
    private fun foursToggle(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color(R.color.surface)) }
        isClickable = true
        foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
        setOnClickListener { onFours?.invoke() }
        contentDescription = "4성도 보기, " + if (showFours) "켜짐" else "꺼짐"
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("4성도 보기", 14f, R.color.ink, bold = true))
            addView(text("픽업 4성과 마지막 픽업 시기 · 누르면 4성도 알림 받기", 12f, R.color.ink_muted))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text(if (showFours) "켜짐" else "꺼짐", 13f, if (showFours) R.color.action else R.color.ink_muted, bold = true))
    }

    private fun pickups(data: RerunData, banners: List<Banner>, today: java.time.LocalDate, title: String): View? {
        // 5성 먼저, 4성은 뒤에 '4성' 표시로.
        val chars = (banners.flatMap { it.chars } + if (showFours) banners.flatMap { it.four } else emptyList()).distinct().mapNotNull { data.character(it) }
        if (chars.isEmpty()) return null
        val start = banners.minOf { it.start }
        val end = banners.minOf { it.end }
        fun day(d: java.time.LocalDate) = "${d.monthValue}월 ${d.dayOfMonth}일"
        val period = if (start.isAfter(today)) "${day(start)} ~ ${day(end)}"
            else "${day(end)}까지 · ${java.time.temporal.ChronoUnit.DAYS.between(today, end)}일 남음"
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(color(R.color.hero))
                setStroke(dp(2), color(R.color.gold))
            }
            setPadding(dp(6), dp(16), dp(6), dp(4))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), dp(12))
                addView(text(title, 18f, R.color.gold, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(period, 13f, R.color.hero_ink))
            })
            for (row in chars.chunked(COLUMNS)) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in row) addView(tile(data, c, data.since(c, today) ?: 0, markFour = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                repeat(COLUMNS - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
    }

    /**
     * 남색 판: 위에 제목과 '숫자가 무엇인지' 설명(같은 모양의 작은 표시로), 아래에 캐릭터를 한 줄에 5명씩 오래된 순으로.
     */
    private fun board(
        data: RerunData, ranked: List<Pair<RerunCharacter, Int>>, title: String,
        legend: Boolean, now: String? = null, note: String? = null,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_hero)
        clipToOutline = true
        setPadding(dp(6), dp(18), dp(6), dp(6))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(text(title, 18f, R.color.hero_ink, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            now?.let { addView(pill("지금 $it", R.color.hero_line, R.color.hero_ink, 12f)) }
        })
        if (legend) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), 0)
                addView(badge(12, small = true))
                addView(text("마지막 픽업 뒤 지난 버전 수", 13f, R.color.hero_muted).apply { setPadding(dp(8), 0, 0, 0) })
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(12), 0)
                addView(pill("신규", R.color.rerun_recent, R.color.on_action, 11f))
                addView(pill("집록", R.color.gold, R.color.on_gold, 11f).apply { (layoutParams as? LinearLayout.LayoutParams) }, gap())
                addView(pill("복각 없음", R.color.element_hydro, R.color.on_action, 11f), gap())
                addView(text("표시가 붙어요", 12f, R.color.hero_muted).apply { setPadding(dp(8), 0, 0, 0) }, gap())
            })
            if (onToggle != null) addView(text("캐릭터를 누르면 그 캐릭터 픽업이 뜰 때 알려 드려요 🔔", 12f, R.color.hero_muted).apply {
                setPadding(dp(12), dp(8), dp(12), 0)
            })
        }
        note?.let { addView(text(it, 13f, R.color.hero_muted).apply { setPadding(dp(12), dp(6), dp(12), 0) }) }
        addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, dp(16)))
        for (row in ranked.chunked(COLUMNS)) addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((c, since) in row) addView(tile(data, c, since), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            repeat(COLUMNS - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }

    private fun gap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }

    private fun pill(value: String, bg: Int, fg: Int, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(fg))
        includeFontPadding = false
        setPadding(dp(7), dp(3), dp(7), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color(bg)) }
    }

    /** 지난 버전 수 표시: 오래될수록 진한 색, 9 이상은 더 크게. */
    /**
     * 지난 버전 수 표시. 이번 버전 캐릭터는 숫자 대신 지금 픽업 중이면 NOW, 전반에 나왔다 끝났으면 전반,
     * 후반에 나올 예정이면 후반, 다음 버전 이후로 발표됐으면 이후(숫자 0은 글꼴 때문에 O처럼 보여 헷갈린다).
     */
    private fun badge(since: Int, small: Boolean = false, phase: RerunPhase = if (since == 0) RerunPhase.NOW else RerunPhase.PAST) = TextView(context).apply {
        val word = phase != RerunPhase.PAST
        val upcoming = phase == RerunPhase.SECOND_HALF || phase == RerunPhase.LATER
        text = when (phase) {
            RerunPhase.NOW -> "NOW"
            RerunPhase.FIRST_HALF -> "전반"
            RerunPhase.SECOND_HALF -> "후반"
            RerunPhase.LATER -> "이후"
            RerunPhase.PAST -> "$since"
        }
        textSize = when {
            word -> 10f
            small -> 13f
            since >= 9 -> 17f
            else -> 14f
        }
        typeface = if (word) Typeface.DEFAULT_BOLD else numerals
        includeFontPadding = false
        gravity = Gravity.CENTER
        minWidth = dp(24)
        setTextColor(if (upcoming) color(R.color.on_gold) else 0xFFFFFFFF.toInt())
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color(if (upcoming) R.color.gold else RerunActivity.tone(since)))
            setStroke(dp(2), color(R.color.hero))
        }
    }

    /** 캐릭터 한 칸: 원소 동그라미 + 오른쪽 아래 지난 버전 수 + 원소 색 이름. 최근에 나온 캐릭터는 흐리게. */
    private fun tile(data: RerunData, c: RerunCharacter, since: Int, markFour: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val base = color(RerunActivity.elementColor(c.element))
        val strong = since >= 9
        alpha = when {
            phase(c).let { it == RerunPhase.NOW || it == RerunPhase.SECOND_HALF || it == RerunPhase.LATER } -> 1f // 지금·곧 픽업이면 흐리게 하지 않는다
            since <= 2 -> 0.55f
            since <= 5 -> 0.85f
            else -> 1f
        }
        addView(android.widget.FrameLayout(context).apply {
            addView(android.widget.ImageView(context).apply {
                val face = faces[c.ko]
                if (face != null) {
                    // 얼굴 그림: 원소 색 테두리 안에 동그랗게
                    setImageBitmap(face)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                } else {
                    RerunActivity.elementIcon(c.element)?.let { setImageResource(it) }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // 테두리 색으로 등급(5성 금색, 4성 보라색).
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(lighten(base, 0.35f), base)).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(3), color(if (c.rarity == 4) R.color.rarity4 else R.color.rarity5))
                }
            }, android.widget.FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
            addView(badge(since, phase = phase(c)), android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            // 픽업 알림을 받기로 한 캐릭터: 왼쪽 위에 종.
            if (c.name in watched) addView(TextView(context).apply {
                text = "🔔"
                textSize = 12f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.gold)); setStroke(dp(2), color(R.color.hero)) }
            }, android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START))
        }, LinearLayout.LayoutParams(dp(62), dp(58)))
        addView(text(shortName(c.ko), if (strong) 13f else 12f, 0, bold = strong).apply {
            setTextColor(lighten(base, 0.45f))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // 신규 > 집록 > 복각 없음 순으로 하나만 붙이고, 없으면 마지막 픽업 버전.
        val tag = when {
            markFour && c.rarity == 4 -> pill("4성", R.color.rarity4, R.color.on_gold, 10f)
            c.first != null && c.first == current -> pill("신규", R.color.rerun_recent, R.color.on_action, 10f)
            c.chronicled -> pill("집록", R.color.gold, R.color.on_gold, 10f)
            !c.standard && c.runs <= 1 -> pill("복각 없음", R.color.element_hydro, R.color.on_action, 10f)
            else -> null
        }
        addView(tag ?: text(data.label(c.last), 10f, R.color.hero_muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        onToggle?.let { toggle ->
            isClickable = true
            foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
            setOnClickListener { toggle(c) }
        }
        contentDescription = (if (c.name in watched) "픽업 알림 받는 중, " else "") + "${c.ko}, ${RerunActivity.elementName(c.element)}, " +
            (if (since == 0) "지금 픽업 중" else "${since}버전 전") + ", 마지막 ${data.label(c.last)}" +
            (if (c.chronicled) ", 집록" else "")
    }

    /** '카에데하라 카즈하' → '카즈하'처럼 성을 뗀 이름. 한 단어면 그대로. */
    private fun shortName(ko: String) = ko.trim().split(" ").last()

    private fun lighten(c: Int, amount: Float): Int {
        fun mix(v: Int) = v + ((255 - v) * amount).toInt()
        return (0xFF shl 24) or (mix(c shr 16 and 0xFF) shl 16) or (mix(c shr 8 and 0xFF) shl 8) or mix(c and 0xFF)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        if (color != 0) setTextColor(color(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    companion object {
        private const val COLUMNS = 5
    }
}
