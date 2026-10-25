package kr.sumi.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 홈 꾸미기: 홈 스타일(카드형·로비형), 최애 캐릭터(호요랩 전적의 내 캐릭터, 또는 아직 없어 기다리는 캐릭터), 로비 배경(공식 그림·내 사진·원소 무늬).
 * 공식 그림은 사이트 설정으로 꺼져 있으면 고를 수 없다.
 */
class LobbyStyleActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var list: LinearLayout
    private val pool = Executors.newFixedThreadPool(3)
    private var characters: List<OwnedCharacter>? = null
    private var wiki: List<Lobby.WikiCharacter>? = null
    private var showWaiting = false
    /** 받은 얼굴 그림(주소 → 그림). 화면을 다시 그릴 때 깜빡이지 않게 바로 넣는다. */
    private val busts = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private var loadError: String? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        settings = AppSettings(this)
        val header = LayoutInflater.from(this).inflate(R.layout.header_bar, null)
        header.findViewById<TextView>(R.id.headerTitle).text = "홈 꾸미기"
        header.findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
        header.findViewById<TextView>(R.id.headerStatus).text = "로비형은 최애 캐릭터가 홈의 주인공이 되는 화면이에요. 언제든 카드형으로 돌아갈 수 있어요."
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(32)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@LobbyStyleActivity).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        MainActivity.applySystemBarInsets(root)
        render()
        val store = settings.current()
        if (store == null) return
        characters = Lobby.cachedCharacters(this, store).takeIf { it.isNotEmpty() }
        pool.execute {
            Lobby.refreshConfig(this)
            val result = try { Lobby.refreshCharacters(this, store, force = characters == null) } catch (e: IOException) { loadError = "캐릭터를 불러오지 못했어요"; null }
            val all = Lobby.wikiCharacters(this)
            runOnUiThread { if (!isDestroyed) { if (result != null) characters = result; wiki = all; render() } }
        }
    }

    override fun onPause() {
        super.onPause()
        // 최애·홈 스타일이 바뀌었을 수 있으니 '오늘' 위젯도 맞춘다.
        AppWidgets.updateAll(this)
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("사진 고르기 결과")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK || uri == null) return
        pool.execute {
            val ok = try { Lobby.savePhoto(this, uri, resources.displayMetrics.widthPixels); true } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) settings.lobbyBackground = Lobby.BG_PHOTO
                else Toast.makeText(this, "사진을 불러오지 못했어요", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        val store = settings.current()
        val fav = settings.favorite

        section("홈 스타일")
        choice("카드형", "출석·레진·달력을 카드로 한눈에 (기본)", settings.homeStyle == Lobby.STYLE_CARD) {
            settings.homeStyle = Lobby.STYLE_CARD; render()
        }
        choice("로비형", if (fav == null) "아래에서 최애 캐릭터를 먼저 골라 주세요" else "${fav.name}이(가) 홈의 주인공이 돼요", settings.homeStyle == Lobby.STYLE_LOBBY) {
            if (store == null) Toast.makeText(this, "로그인하면 쓸 수 있어요", Toast.LENGTH_SHORT).show()
            else if (settings.favorite == null) Toast.makeText(this, "최애 캐릭터를 먼저 골라 주세요", Toast.LENGTH_SHORT).show()
            else { settings.homeStyle = Lobby.STYLE_LOBBY; render() }
        }

        section("최애 캐릭터")
        val chars = characters
        when {
            store == null -> note("로그인하면 내 캐릭터 중에서 고를 수 있어요.")
            chars == null -> note(loadError ?: "내 캐릭터를 불러오는 중이에요...")
            chars.isEmpty() -> note("호요랩 전적에서 캐릭터를 찾지 못했어요.")
            // 순서는 등급·레벨로 고정한다(고른 최애를 앞으로 옮기면 누를 때 칸이 순간이동해 보인다).
            else -> characterGrid(chars.sortedWith(compareByDescending<OwnedCharacter> { it.rarity % 100 }.thenByDescending { it.level })
                .map { Pick(it.name, it.element, it.image, it.id, it.rarity) }, fav)
        }
        if (store != null && chars != null) waitingSection(chars, fav)

        section("캐릭터 음성")
        if (!Lobby.officialVoicesAllowed(this)) note("지금은 캐릭터 음성을 쓸 수 없어요. 대사 글은 그대로 보여요.") else voiceChips()
        note("기본은 꺼져 있어요. 대사 글은 한국어로 보여요. 음성은 말풍선이나 캐릭터를 눌렀을 때 그 대사만 받고, 받은 건 폰에 두고 다시 써요. 최애나 언어를 바꾸면 이전 음성은 지워져요.")
        val voiceBytes = Lobby.voiceCacheBytes(this)
        if (voiceBytes > 0) choice("저장된 음성 지우기", "지금 %.1fMB · 다시 들으면 다시 받아요".format(voiceBytes / 1_000_000.0), false) {
            Lobby.clearVoiceCache(this); render()
        }

        section("로비 배경")
        val official = Lobby.officialArtAllowed(this)
        if (official) choice("공식 그림", "호요랩 위키의 캐릭터 그림을 받아 이 폰에만 저장해요", settings.lobbyBackground == Lobby.BG_OFFICIAL) {
            settings.lobbyBackground = Lobby.BG_OFFICIAL; render()
        }
        choice("내 사진", if (Lobby.photoFile(this).exists()) "고른 사진을 써요 · 눌러서 바꾸기" else "갤러리에서 사진 고르기", settings.lobbyBackground == Lobby.BG_PHOTO) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO)
        }
        choice("원소 무늬", "최애 원소 색과 무늬만", settings.lobbyBackground == Lobby.BG_PATTERN || (!official && settings.lobbyBackground == Lobby.BG_OFFICIAL)) {
            settings.lobbyBackground = Lobby.BG_PATTERN; render()
        }
        note(
            (if (official) "공식 그림의 저작권은 HoYoverse에 있어요. 앱에 넣지 않고 호요랩 위키에서 받아 이 폰에서만 보여 줘요.\n" else "지금은 공식 그림을 쓸 수 없어요. 내 사진이나 원소 무늬를 써 주세요.\n") +
                "고른 사진은 줄여서 앱 안에만 저장하고, 어디에도 보내지 않아요."
        )
    }

    /** 고를 수 있는 캐릭터 한 칸. 아직 없는 캐릭터는 id가 0이고 그림은 위키 얼굴 그림. */
    private class Pick(val name: String, val element: String, val image: String, val id: Int, val rarity: Int)

    /**
     * 아직 없는 캐릭터(위키 전체 중 내 캐릭터가 아닌 것). 접어 두었다가 누르면 펼친다(얼굴 그림을 한꺼번에 받지 않게).
     * 고르면 그 캐릭터의 픽업 알림(복각표 '기다리는 캐릭터')에도 넣는다.
     */
    private fun waitingSection(owned: List<OwnedCharacter>, fav: Favorite?) {
        val ownedNames = owned.map { it.name }.toSet()
        val waitingFav = fav != null && fav.name !in ownedNames
        choice("아직 없는 캐릭터 중에서", if (waitingFav) "${fav!!.name}을(를) 기다리는 중이에요 · 픽업이 열리면 알려 드려요" else "기다리는 캐릭터를 최애로 · 픽업이 열리면 알려 드려요",
            waitingFav) { showWaiting = !showWaiting; render() }
        if (!showWaiting) return
        val all = wiki
        when {
            all == null -> note("캐릭터 목록을 불러오는 중이에요...")
            all.isEmpty() -> note("캐릭터 목록을 불러오지 못했어요.")
            else -> characterGrid(all.filter { it.name !in ownedNames }
                .map { Pick(it.name, it.element, it.icon, 0, it.rarity) }, fav)
        }
    }

    private fun characterGrid(sorted: List<Pick>, fav: Favorite?) {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        sorted.forEachIndexed { index, c ->
            if (index % 4 == 0) row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { grid.addView(it) }
            val color = getColor(Lobby.elementColorRes(c.element))
            val selected = c.name == fav?.name
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(4))
                contentDescription = c.name + if (selected) ", 최애" else ""
                setOnClickListener {
                    if (fav?.name != c.name) Lobby.clearVoiceCache(this@LobbyStyleActivity)
                    settings.favorite = Favorite(c.name, c.element, c.image, c.id)
                    if (c.id == 0) Lobby.watchRerun(this@LobbyStyleActivity, c.name)
                    if (settings.homeStyle != Lobby.STYLE_LOBBY) settings.homeStyle = Lobby.STYLE_LOBBY
                    // 공식 그림을 미리 받아 둔다(로비를 열 때 바로 보이게).
                    pool.execute { Lobby.background(this@LobbyStyleActivity, settings, resources.displayMetrics.widthPixels) }
                    render()
                }
            }
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            // 테두리 색은 등급(5성 금색, 4성 보라색, 모르면 원소 색), 고른 최애는 오른쪽 위에 체크.
            val ring = when (c.rarity) { 5, 105 -> getColor(R.color.rarity5); 4 -> getColor(R.color.rarity4); else -> color } // 105는 전적의 에일로이(특별 5성)
            cell.addView(FrameLayout(this).apply {
                addView(FrameLayout(this@LobbyStyleActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(LobbyTheme.blend(color, Color.WHITE, 0.55f))
                        setStroke(dp(3), ring)
                    }
                    clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                }, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
                if (selected) addView(TextView(this@LobbyStyleActivity).apply {
                    text = "✓"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_action))
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.action)); setStroke(dp(2), getColor(R.color.surface)) }
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            }, LinearLayout.LayoutParams(dp(66), dp(64)))
            cell.addView(TextView(this).apply {
                text = c.name
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(if (selected) R.color.ink else R.color.ink_muted))
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(4), dp(2), 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            row!!.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            busts[c.image]?.let { image.setImageBitmap(it) } ?: pool.execute {
                val bmp = Lobby.bust(this, c.image)
                if (bmp != null) busts[c.image] = bmp
                runOnUiThread { if (bmp != null) image.setImageBitmap(bmp) }
            }
        }
        // 마지막 줄의 빈 칸
        repeat((4 - sorted.size % 4) % 4) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        list.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    /** 음성 언어 고르기: 한국어·일본어·중국어·영어·끄기를 한 줄 칩으로. */
    private fun voiceChips() = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val options = Lobby.VOICE_LANGUAGES.keys.map { it to Lobby.VOICE_LANGUAGES.getValue(it) } + ("off" to "끄기")
        for ((code, label) in options) {
            val selected = settings.voiceLanguage == code
            addView(TextView(this@LobbyStyleActivity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(getColor(if (selected) R.color.on_action else R.color.ink))
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(99).toFloat()
                    if (selected) setColor(getColor(R.color.action)) else { setColor(getColor(R.color.surface)); setStroke(dp(1), getColor(R.color.line)) }
                }
                contentDescription = "음성 ${text}" + if (selected) ", 선택됨" else ""
                setOnClickListener { if (settings.voiceLanguage != code) Lobby.clearVoiceCache(this@LobbyStyleActivity); settings.voiceLanguage = code; render() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (code != "ko") leftMargin = dp(6) })
        }
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

    private fun section(title: String) = list.addView(TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.ink))
        setPadding(dp(4), dp(22), 0, dp(8))
    })

    private fun note(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setLineSpacing(dp(2).toFloat(), 1f)
        setTextColor(getColor(R.color.ink_muted))
        setPadding(dp(4), dp(10), dp(4), 0)
    })

    /** 고르는 줄 하나(흰 카드). 고른 것은 테두리와 점으로. */
    private fun choice(title: String, sub: String, selected: Boolean, onClick: () -> Unit) = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(getColor(R.color.surface))
            cornerRadius = dp(16).toFloat()
            if (selected) setStroke(dp(2), getColor(R.color.action))
        }
        setOnClickListener { onClick() }
        addView(View(this@LobbyStyleActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(getColor(R.color.action)) else setStroke(dp(2), getColor(R.color.line))
            }
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(LinearLayout(this@LobbyStyleActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(context).apply { text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.ink)) })
            addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(getColor(R.color.ink_muted)); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

    companion object {
        private const val PICK_PHOTO = 41
        fun intent(context: Context) = Intent(context, LobbyStyleActivity::class.java)
    }
}
