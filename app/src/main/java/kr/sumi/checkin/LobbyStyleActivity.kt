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
        header.findViewById<TextView>(R.id.headerTitle).text = getString(R.string.lobby_style_title)
        header.findViewById<View>(R.id.headerBack).setOnClickListener { finish() }
        header.findViewById<TextView>(R.id.headerStatus).text = getString(R.string.lobby_style_intro)
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
            val result = try { Lobby.refreshCharacters(this, store, force = characters == null) } catch (e: IOException) { loadError = getString(R.string.lobby_chars_load_failed); null }
            val all = Lobby.wikiCharacters(this)
            Lobby.refreshLocalNames(this)
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
                else Toast.makeText(this, R.string.lobby_photo_failed,Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        val store = settings.current()
        val fav = settings.favorite

        section(getString(R.string.lobby_section_style))
        choice(getString(R.string.lobby_style_card), getString(R.string.lobby_style_card_sub), settings.homeStyle == Lobby.STYLE_CARD) {
            settings.homeStyle = Lobby.STYLE_CARD; render()
        }
        choice(getString(R.string.lobby_style_lobby),
            if (fav == null) getString(R.string.lobby_style_lobby_pick) else getString(R.string.lobby_style_lobby_sub, CharNames.of(fav.name)), settings.homeStyle == Lobby.STYLE_LOBBY) {
            if (store == null) Toast.makeText(this, R.string.lobby_need_login, Toast.LENGTH_SHORT).show()
            else if (settings.favorite == null) Toast.makeText(this, R.string.lobby_pick_fav_first, Toast.LENGTH_SHORT).show()
            else { settings.homeStyle = Lobby.STYLE_LOBBY; render() }
        }

        section(getString(R.string.lobby_section_fav))
        val chars = characters
        when {
            store == null -> note(getString(R.string.lobby_login_to_pick))
            chars == null -> note(loadError ?: getString(R.string.lobby_chars_loading))
            chars.isEmpty() -> note(getString(R.string.lobby_chars_none))
            // 순서는 등급·레벨로 고정한다(고른 최애를 앞으로 옮기면 누를 때 칸이 순간이동해 보인다).
            else -> characterGrid(chars.sortedWith(compareByDescending<OwnedCharacter> { it.rarity % 100 }.thenByDescending { it.level })
                .map { Pick(it.name, it.element, it.image, it.id, it.rarity) }, fav)
        }
        if (store != null && chars != null) waitingSection(chars, fav)

        section(getString(R.string.lobby_section_voice))
        if (!Lobby.officialVoicesAllowed(this)) note(getString(R.string.lobby_voice_unavailable)) else {
            voiceChips()
            // 음성이 앱 언어와 다르면: 대사 글도 음성 언어로 볼지(예: 한국어 앱에서 일본어 음성 + 일본어 대사).
            val voice = settings.voiceLanguage
            val voiceLang = mapOf("ko" to DataLang.KO, "ja" to DataLang.JA, "zh" to "zh-cn", "en" to DataLang.EN)[voice]
            if (voiceLang != null && voiceLang != DataLang.code()) {
                val label = getString(when (voice) { "ko" -> R.string.lobby_voice_ko; "ja" -> R.string.lobby_voice_ja; "zh" -> R.string.lobby_voice_zh; else -> R.string.lobby_voice_en })
                choice(getString(R.string.lobby_quote_follows_voice, label), getString(R.string.lobby_quote_follows_voice_sub), settings.quoteFollowsVoice) {
                    settings.quoteFollowsVoice = !settings.quoteFollowsVoice
                    render()
                }
            }
        }
        note(getString(R.string.lobby_voice_note))
        val voiceBytes = Lobby.voiceCacheBytes(this)
        if (voiceBytes > 0) choice(getString(R.string.lobby_voice_clear), getString(R.string.lobby_voice_clear_sub, "%.1f".format(voiceBytes / 1_000_000.0)), false) {
            Lobby.clearVoiceCache(this); render()
        }

        section(getString(R.string.lobby_section_bg))
        val official = Lobby.officialArtAllowed(this)
        if (official) choice(getString(R.string.lobby_bg_official), getString(R.string.lobby_bg_official_sub), settings.lobbyBackground == Lobby.BG_OFFICIAL) {
            settings.lobbyBackground = Lobby.BG_OFFICIAL; render()
        }
        choice(getString(R.string.lobby_bg_photo),
            getString(if (Lobby.photoFile(this).exists()) R.string.lobby_bg_photo_set else R.string.lobby_bg_photo_pick), settings.lobbyBackground == Lobby.BG_PHOTO) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO)
        }
        choice(getString(R.string.lobby_bg_pattern), getString(R.string.lobby_bg_pattern_sub), settings.lobbyBackground == Lobby.BG_PATTERN || (!official && settings.lobbyBackground == Lobby.BG_OFFICIAL)) {
            settings.lobbyBackground = Lobby.BG_PATTERN; render()
        }
        note(getString(if (official) R.string.lobby_bg_note_official else R.string.lobby_bg_note_off))
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
        choice(getString(R.string.lobby_waiting_choice),
            if (waitingFav) getString(R.string.lobby_waiting_choice_fav, CharNames.of(fav!!.name)) else getString(R.string.lobby_waiting_choice_sub),
            waitingFav) { showWaiting = !showWaiting; render() }
        if (!showWaiting) return
        val all = wiki
        when {
            all == null -> note(getString(R.string.lobby_wiki_loading))
            all.isEmpty() -> note(getString(R.string.lobby_wiki_failed))
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
                contentDescription = if (selected) getString(R.string.lobby_cell_fav_desc, CharNames.of(c.name)) else CharNames.of(c.name)
                setOnClickListener {
                    if (fav?.name != c.name) Lobby.clearVoiceCache(this@LobbyStyleActivity)
                    settings.favorite = Favorite(c.name, c.element, c.image, c.id)
                    if (c.id == 0) Lobby.watchRerun(this@LobbyStyleActivity, c.name)
                    if (settings.homeStyle != Lobby.STYLE_LOBBY) settings.homeStyle = Lobby.STYLE_LOBBY
                    // 공식 그림을 미리 받아 둔다(로비를 열 때 바로 보이게).
                    pool.execute { Lobby.background(this@LobbyStyleActivity, settings, resources.displayMetrics.widthPixels) }
                    render()
                    askVoiceOnce()
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
                text = CharNames.of(c.name)
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
                val bmp = Lobby.bust(this, c.image, keep = false)
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
        // Lobby.VOICE_LANGUAGES의 값은 위키 자료의 언어 이름(한국어)이라 화면에는 리소스 이름을 쓴다.
        fun voiceLabel(code: String) = when (code) {
            "ko" -> getString(R.string.lobby_voice_ko)
            "ja" -> getString(R.string.lobby_voice_ja)
            "zh" -> getString(R.string.lobby_voice_zh)
            "en" -> getString(R.string.lobby_voice_en)
            else -> Lobby.VOICE_LANGUAGES.getValue(code)
        }
        val options = Lobby.VOICE_LANGUAGES.keys.map { it to voiceLabel(it) } + ("off" to getString(R.string.lobby_voice_off))
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
                contentDescription = getString(if (selected) R.string.lobby_voice_chip_desc_selected else R.string.lobby_voice_chip_desc, label)
                setOnClickListener { if (settings.voiceLanguage != code) Lobby.clearVoiceCache(this@LobbyStyleActivity); settings.voiceLanguage = code; render() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (code != "ko") leftMargin = dp(6) })
        }
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

    /**
     * 최애를 처음 고르면 한 번: 음성도 켤지 묻는다(음성 설정은 화면 아래쪽이라 모르고 지나치기 쉽다).
     * 음성은 누른 대사만 그때 받아 재생하고 저장해 둔다. 고른 언어는 아래 음성 칸에서 언제든 바꾼다.
     */
    private fun askVoiceOnce() {
        if (settings.voiceAsked || settings.voiceLanguage != "off" || !Lobby.officialVoicesAllowed(this)) return
        settings.voiceAsked = true
        val codes = Lobby.VOICE_LANGUAGES.keys.toList()
        val labels = codes.map { code ->
            getString(when (code) { "ko" -> R.string.lobby_voice_ko; "ja" -> R.string.lobby_voice_ja; "zh" -> R.string.lobby_voice_zh; else -> R.string.lobby_voice_en })
        } + getString(R.string.lobby_voice_ask_no)
        AppDialog(this)
            .setTitle(getString(R.string.lobby_voice_ask_title))
            .setMessage(getString(R.string.lobby_voice_ask_body))
            .setSingleChoiceItems(labels.toTypedArray(), -1) { dialog, which ->
                dialog.dismiss()
                if (which < codes.size) { settings.voiceLanguage = codes[which]; render() }
            }
            .show()
    }

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
