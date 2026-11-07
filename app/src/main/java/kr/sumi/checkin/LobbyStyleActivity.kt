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
 * 홈 꾸미기. 위에 지금 로비를 크게 미리 보여 주고(카드형·로비형 전환도 여기서), 아래는 탭 셋:
 * 캐릭터(내 캐릭터·기다리는 캐릭터), 배경(공식 그림·내 사진·원소 무늬), 음성(언어·들어보기·대사 언어).
 * 공식 그림·음성은 사이트 설정으로 꺼져 있으면 고를 수 없다.
 */
class LobbyStyleActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var list: LinearLayout
    private val pool = Executors.newFixedThreadPool(3)
    private var characters: List<OwnedCharacter>? = null
    private var wiki: List<Lobby.WikiCharacter>? = null
    private var showWaiting = false
    private var tab = TAB_CHARACTER
    private lateinit var hero: FrameLayout
    private lateinit var tabs: LinearLayout
    private var statusTop = 0
    /** 미리보기 배경 그림과 그것을 고른 조건(최애·배경 방식·사진). 조건이 같으면 다시 읽지 않는다. */
    private var heroArt: android.graphics.Bitmap? = null
    private var heroArtKey: String? = null
    private var player: android.media.MediaPlayer? = null
    /** 받은 얼굴 그림(주소 → 그림). 화면을 다시 그릴 때 깜빡이지 않게 바로 넣는다. */
    private val busts = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()
    private var loadError: String? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.drawBehindSystemBars(window)
        settings = AppSettings(this)
        val root = buildContent()
        setContentView(root)
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, insets ->
            statusTop = insets.systemWindowInsetTop
            v.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            renderHero()
            insets
        }
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

    /** 화면 틀: 미리보기, 탭, 아래 내용(스크롤). 미리보기는 화면 위 끝(상태 바 뒤)까지. 테스트도 이것으로 그린다. */
    internal fun buildContent(): LinearLayout {
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        hero = FrameLayout(this)
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundResource(R.color.surface) }
        val heroHeight = minOf(dp(300), resources.displayMetrics.heightPixels * 38 / 100)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.bg)
            addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, heroHeight))
            addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@LobbyStyleActivity).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    /** 테스트용: 설정·내 캐릭터·탭을 넣고 그린다. */
    internal fun showForTest(settings: AppSettings, characters: List<OwnedCharacter>?, tab: Int, art: android.graphics.Bitmap?) {
        this.settings = settings
        this.characters = characters
        this.tab = tab
        heroArt = art
        heroArtKey = listOf(settings.favorite?.name, settings.lobbyBackground, Lobby.officialArtAllowed(this), Lobby.photoFile(this).lastModified()).joinToString("|")
        render()
    }

    override fun onPause() {
        super.onPause()
        player?.release()
        player = null
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
        renderHero()
        renderTabs()
        list.removeAllViews()
        when (tab) {
            TAB_CHARACTER -> characterPanel()
            TAB_BG -> backgroundPanel()
            else -> voicePanel()
        }
    }

    /** 위의 미리보기: 지금 최애·배경으로 그린 로비, 뒤로 가기, 카드형·로비형 전환. */
    private fun renderHero() {
        hero.removeAllViews()
        val fav = settings.favorite
        val w = resources.displayMetrics.widthPixels
        val h = hero.layoutParams?.height ?: dp(300)
        val t = LobbyTheme(getColor(if (fav != null) Lobby.elementColorRes(fav.element) else R.color.hero))
        // 배경: 고른 방식의 그림(배경 스레드에서 읽어 다시 그린다), 없으면 원소 무늬.
        val key = listOf(fav?.name, settings.lobbyBackground, Lobby.officialArtAllowed(this), Lobby.photoFile(this).lastModified()).joinToString("|")
        if (key != heroArtKey) {
            heroArtKey = key
            heroArt = null
            if (fav != null) pool.execute {
                val art = Lobby.cachedBackground(this, settings, w) ?: Lobby.background(this, settings, w)
                runOnUiThread { if (!isDestroyed && heroArtKey == key && art != null) { heroArt = art; renderHero() } }
            }
        }
        val art = heroArt
        if (art != null && fav != null) {
            // 얼굴이 미리보기 높이의 40% 쯤에 오게 그림을 올린다(얼굴 위치를 모르면 그림 위쪽 1/4).
            val scale = w.toFloat() / art.width
            val imgH = art.height * scale
            val faceY = (Lobby.portraitFace(this, fav.name)?.centerY()?.takeIf { settings.lobbyBackground == Lobby.BG_OFFICIAL } ?: 0.25f) * imgH
            val dy = (h * 0.4f - faceY).coerceIn(h - imgH, 0f)
            hero.addView(ImageView(this).apply {
                setImageBitmap(art)
                scaleType = ImageView.ScaleType.MATRIX
                imageMatrix = android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(0f, dy) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.deep, t.dark))
            if (fav != null) hero.addView(ImageView(this).apply {
                setImageResource(Lobby.elementIcon(fav.element)); setColorFilter(Color.WHITE); alpha = 0.14f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER))
        }
        if (art != null && fav != null) hero.background = null
        // 위아래를 어둡게(글자가 잘 보이게)
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x55000000, 0x00000000, 0x00000000, (0xD0 shl 24) or (t.dark and 0xFFFFFF)))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 위쪽: 뒤로 가기·제목, 카드형·로비형
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), statusTop + dp(6), dp(12), 0)
            addView(ImageView(this@LobbyStyleActivity).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = getString(R.string.main_back)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@LobbyStyleActivity).apply {
                text = getString(R.string.lobby_style_title); textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(styleSegment())
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        if (fav == null) {
            hero.addView(TextView(this).apply {
                text = getString(if (settings.current() == null) R.string.lobby_login_to_pick else R.string.lobby_preview_pick)
                textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            return
        }
        // 말풍선: 열린 대사 중 짧은 것 하나(받아 둔 대사가 없으면 없다)
        previewLine(fav)?.let { line ->
            hero.addView(TextView(this).apply {
                text = "「${line.text}」"
                textSize = 12.5f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                LobbyFont.title?.let { typeface = it }
                setTextColor(0xFF1B2030.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(10))
                val r = dp(14).toFloat()
                background = GradientDrawable().apply { setColor(0xEEFFFFFF.toInt()); cornerRadii = floatArrayOf(r, r, r, r, r, r, dp(4).toFloat(), dp(4).toFloat()) }
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(14), statusTop + dp(56), w / 3, 0) })
        }
        // 아래쪽: 최애 표시·이름·정보
        val owned = characters?.firstOrNull { it.name == fav.name }
        val elementEn = fav.element.replaceFirstChar { it.uppercase() }
        val elementName = RerunActivity.elementName(this, elementEn).takeIf { it != elementEn } ?: Lobby.ELEMENT_KO[fav.element] ?: ""
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
            addView(TextView(this@LobbyStyleActivity).apply {
                text = getString(R.string.lobby_fav_badge); textSize = 10.5f; setTextColor(Color.WHITE)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(99).toFloat() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(TextView(this@LobbyStyleActivity).apply {
                text = CharNames.of(fav.name); textSize = 30f; setTextColor(Color.WHITE)
                LobbyFont.title?.let { typeface = it } ?: setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(4))
            })
            addView(TextView(this@LobbyStyleActivity).apply {
                text = if (owned != null) getString(R.string.main_lobby_meta_owned, elementName, elementEn, owned.level, owned.fetter, owned.constellation)
                    else getString(R.string.main_lobby_meta_element, elementName, elementEn)
                textSize = 11.5f; setTextColor(0xD9FFFFFF.toInt())
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        // 카드형을 쓰는 중이면 미리보기 위에 그렇다고 적는다(로비는 꺼져 있다).
        if (settings.homeStyle != Lobby.STYLE_LOBBY) hero.addView(TextView(this).apply {
            text = getString(R.string.lobby_preview_card); textSize = 12.5f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(99).toFloat() }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }

    /** 미리보기 말풍선에 쓸 대사: 열린 평소 이야기·인사 중 짧은 것 하나. 최애가 바뀔 때만 다시 고른다. */
    private var previewFor: String? = null
    private var previewCache: Lobby.VoiceLine? = null
    private fun previewLine(fav: Favorite): Lobby.VoiceLine? {
        val key = "${fav.name}|${Lobby.lineLang(this)}"
        if (previewFor == key && previewCache != null) return previewCache
        val lines = Lobby.unlockedVoices(this, fav.name).filter { it.text.length <= 60 }
        if (lines.isEmpty()) {
            if (Lobby.needsVoices(this, fav.name)) pool.execute { Lobby.loadVoices(this, fav.name); runOnUiThread { if (!isDestroyed) render() } }
            return null
        }
        previewFor = key
        previewCache = (Lobby.talk(lines, fav.name) + Lobby.pick(lines, "첫 만남")).firstOrNull() ?: lines.first()
        return previewCache
    }

    /** 카드형·로비형 전환(미리보기 오른쪽 위). */
    private fun styleSegment() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = GradientDrawable().apply { setColor(0x59000000); cornerRadius = dp(99).toFloat() }
        for ((style, label) in listOf(Lobby.STYLE_CARD to R.string.lobby_style_card, Lobby.STYLE_LOBBY to R.string.lobby_style_lobby)) {
            val on = settings.homeStyle == style
            addView(TextView(this@LobbyStyleActivity).apply {
                text = getString(label); textSize = 12.5f
                setTextColor(if (on) 0xFF1B2030.toInt() else Color.WHITE)
                if (on) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                if (on) background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(99).toFloat() }
                contentDescription = getString(label)
                setOnClickListener {
                    when {
                        style == Lobby.STYLE_CARD -> { settings.homeStyle = Lobby.STYLE_CARD; render() }
                        settings.current() == null -> Toast.makeText(this@LobbyStyleActivity, R.string.lobby_need_login, Toast.LENGTH_SHORT).show()
                        settings.favorite == null -> Toast.makeText(this@LobbyStyleActivity, R.string.lobby_pick_fav_first, Toast.LENGTH_SHORT).show()
                        else -> { settings.homeStyle = Lobby.STYLE_LOBBY; render() }
                    }
                }
            })
        }
    }

    /** 탭: 캐릭터 · 배경 · 음성. 고른 탭은 최애 원소 색 밑줄. */
    private fun renderTabs() {
        tabs.removeAllViews()
        val accent = settings.favorite?.let { getColor(Lobby.elementColorRes(it.element)) } ?: getColor(R.color.action)
        for ((key, label) in listOf(TAB_CHARACTER to R.string.lobby_tab_character, TAB_BG to R.string.lobby_tab_bg, TAB_VOICE to R.string.lobby_tab_voice)) {
            val on = tab == key
            tabs.addView(TextView(this).apply {
                text = getString(label); textSize = 14f; gravity = Gravity.CENTER
                setTextColor(getColor(if (on) R.color.ink else R.color.ink_muted))
                if (on) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(13), 0, dp(13))
                background = if (on) android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.ColorDrawable(getColor(R.color.surface)),
                    android.graphics.drawable.ColorDrawable(accent),
                )).apply { setLayerInset(1, dp(18), 0, dp(18), 0); setLayerHeight(1, dp(3)); setLayerGravity(1, Gravity.BOTTOM) } else null
                setOnClickListener { if (tab != key) { tab = key; player?.release(); player = null; render() } }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    private fun characterPanel() {
        val store = settings.current()
        val fav = settings.favorite
        val chars = characters
        if (store == null) return note(getString(R.string.lobby_login_to_pick))
        // 내 캐릭터 / 기다리는 캐릭터
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
            addView(pill(getString(R.string.lobby_owned_tab, chars?.size ?: 0), !showWaiting) { showWaiting = false; render() })
            addView(pill(getString(R.string.lobby_waiting_tab), showWaiting) { showWaiting = true; render() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        })
        when {
            chars == null -> note(loadError ?: getString(R.string.lobby_chars_loading))
            showWaiting -> {
                note(getString(R.string.lobby_waiting_choice_sub))
                val all = wiki
                val ownedNames = chars.map { it.name }.toSet()
                when {
                    all == null -> note(getString(R.string.lobby_wiki_loading))
                    all.isEmpty() -> note(getString(R.string.lobby_wiki_failed))
                    else -> characterGrid(all.filter { it.name !in ownedNames }.map { Pick(it.name, it.element, it.icon, 0, it.rarity) }, fav)
                }
            }
            chars.isEmpty() -> note(getString(R.string.lobby_chars_none))
            // 순서는 등급·레벨로 고정한다(고른 최애를 앞으로 옮기면 누를 때 칸이 순간이동해 보인다).
            else -> characterGrid(chars.sortedWith(compareByDescending<OwnedCharacter> { it.rarity % 100 }.thenByDescending { it.level })
                .map { Pick(it.name, it.element, it.image, it.id, it.rarity) }, fav)
        }
    }

    /** 배경: 그림 타일 셋(공식 그림·내 사진·원소 무늬). */
    private fun backgroundPanel() {
        val official = Lobby.officialArtAllowed(this)
        val fav = settings.favorite
        val el = getColor(fav?.let { Lobby.elementColorRes(it.element) } ?: R.color.hero)
        val t = LobbyTheme(el)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        fun tile(label: String, selected: Boolean, thumb: View, onClick: () -> Unit) = row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = label
            setOnClickListener { onClick() }
            addView(FrameLayout(this@LobbyStyleActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(getColor(R.color.surface))
                    if (selected) setStroke(dp(3), getColor(R.color.action)) else setStroke(dp(1), getColor(R.color.line))
                }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                setPadding(dp(if (selected) 3 else 1), dp(if (selected) 3 else 1), dp(if (selected) 3 else 1), dp(if (selected) 3 else 1))
                addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))
            addView(TextView(this@LobbyStyleActivity).apply {
                text = label; textSize = 12.5f; gravity = Gravity.CENTER
                setTextColor(getColor(if (selected) R.color.ink else R.color.ink_muted))
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(7), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (row.childCount > 0) leftMargin = dp(10) })

        if (official) tile(getString(R.string.lobby_bg_official), settings.lobbyBackground == Lobby.BG_OFFICIAL, ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 공식 그림을 쓰는 중이면 미리보기와 같은 그림, 아니면 최애 얼굴
            heroArt?.takeIf { settings.lobbyBackground == Lobby.BG_OFFICIAL }?.let { setImageBitmap(it) }
                ?: fav?.image?.let { url -> busts[url]?.let { setImageBitmap(it) } }
        }) { settings.lobbyBackground = Lobby.BG_OFFICIAL; render() }
        val photo = Lobby.photoFile(this)
        tile(getString(R.string.lobby_bg_photo), settings.lobbyBackground == Lobby.BG_PHOTO, if (photo.exists()) ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            heroArt?.takeIf { settings.lobbyBackground == Lobby.BG_PHOTO }?.let { setImageBitmap(it) }
                ?: android.graphics.BitmapFactory.decodeFile(photo.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.let { setImageBitmap(it) }
        } else TextView(this).apply {
            text = "＋"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.ink_muted))
        }) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO)
        }
        tile(getString(R.string.lobby_bg_pattern), settings.lobbyBackground == Lobby.BG_PATTERN || (!official && settings.lobbyBackground == Lobby.BG_OFFICIAL), FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.deep, t.dark))
            if (fav != null) addView(ImageView(this@LobbyStyleActivity).apply {
                setImageResource(Lobby.elementIcon(fav.element)); setColorFilter(Color.WHITE); alpha = 0.3f
            }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        }) { settings.lobbyBackground = Lobby.BG_PATTERN; render() }
        list.addView(row)
        note(getString(if (official) R.string.lobby_bg_note_official else R.string.lobby_bg_note_off))
    }

    /** 음성: 언어, 들어보기, 대사도 음성 언어로, 저장 공간. */
    private fun voicePanel() {
        if (!Lobby.officialVoicesAllowed(this)) return note(getString(R.string.lobby_voice_unavailable))
        voiceChips()
        val fav = settings.favorite
        val voice = settings.voiceLanguage
        if (fav != null && voice != "off") {
            val line = previewLine(fav)?.takeIf { it.audioFor(voice) != null }
                ?: Lobby.unlockedVoices(this, fav.name).firstOrNull { it.audioFor(voice) != null && it.text.length <= 60 }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply { setColor(getColor(R.color.surface)); cornerRadius = dp(16).toFloat() }
                contentDescription = getString(R.string.lobby_listen)
                addView(TextView(this@LobbyStyleActivity).apply {
                    text = "▶"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(Lobby.elementColorRes(fav.element))) }
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(LinearLayout(this@LobbyStyleActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(this@LobbyStyleActivity).apply { text = getString(R.string.lobby_listen); textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.ink)) })
                    addView(TextView(this@LobbyStyleActivity).apply {
                        text = line?.let { "「${it.text}」" } ?: getString(R.string.lobby_listen_loading)
                        textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(getColor(R.color.ink_muted))
                    })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                setOnClickListener { line?.audioFor(voice)?.let { listen(it, "${fav.name}|$voice") } }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            // 음성이 앱 언어와 다르면: 대사 글도 음성 언어로 볼지(예: 한국어 앱에서 일본어 음성 + 일본어 대사).
            val voiceLang = mapOf("ko" to DataLang.KO, "ja" to DataLang.JA, "zh" to "zh-cn", "en" to DataLang.EN)[voice]
            if (voiceLang != null && voiceLang != DataLang.code()) {
                val label = getString(when (voice) { "ko" -> R.string.lobby_voice_ko; "ja" -> R.string.lobby_voice_ja; "zh" -> R.string.lobby_voice_zh; else -> R.string.lobby_voice_en })
                toggle(getString(R.string.lobby_quote_follows_voice, label), getString(R.string.lobby_quote_follows_voice_sub), settings.quoteFollowsVoice) {
                    settings.quoteFollowsVoice = !settings.quoteFollowsVoice
                    previewFor = null
                    render()
                }
            }
        }
        // 맨 아래 한 줄: 출처, 저장된 음성과 지우기
        val voiceBytes = Lobby.voiceCacheBytes(this)
        list.addView(TextView(this).apply {
            text = getString(R.string.lobby_voice_fine) + if (voiceBytes > 0) " · " + getString(R.string.lobby_voice_stored, "%.1f".format(voiceBytes / 1_000_000.0)) else ""
            textSize = 11.5f; setTextColor(getColor(R.color.ink_muted)); setPadding(dp(4), dp(14), dp(4), 0)
        })
        if (voiceBytes > 0) list.addView(TextView(this).apply {
            text = getString(R.string.lobby_voice_clear); textSize = 12.5f; setTextColor(getColor(R.color.accent)); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { Lobby.clearVoiceCache(this@LobbyStyleActivity); render() }
        })
    }

    /** 들어보기: 받아 두지 않았으면 받아서(배경 스레드) 바로 재생. */
    private fun listen(url: String, owner: String) {
        player?.release()
        player = null
        pool.execute {
            val file = Lobby.voiceFile(this, url, owner) ?: return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                player?.release()
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; true }
                    try { setDataSource(file.path); prepareAsync() } catch (e: Exception) { release(); player = null }
                }
            }
        }
    }

    /** 둥근 선택 버튼(내 캐릭터·기다리는 캐릭터). */
    private fun pill(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12.5f
        setTextColor(getColor(if (on) R.color.on_action else R.color.ink_muted))
        if (on) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            if (on) setColor(getColor(R.color.action)) else { setColor(getColor(R.color.surface)); setStroke(dp(1), getColor(R.color.line)) }
        }
        setOnClickListener { onClick() }
    }

    /** 스위치 줄(흰 카드): 제목·설명과 오른쪽 켜고 끄기. */
    private fun toggle(title: String, sub: String, on: Boolean, onClick: () -> Unit) = list.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(getColor(R.color.surface)); cornerRadius = dp(16).toFloat() }
        setOnClickListener { onClick() }
        addView(LinearLayout(this@LobbyStyleActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.ink)) })
            addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(getColor(R.color.ink_muted)); setPadding(0, dp(2), dp(8), 0) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(android.widget.Switch(this@LobbyStyleActivity).apply { isChecked = on; isClickable = false; isFocusable = false })
    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

    /** 고를 수 있는 캐릭터 한 칸. 아직 없는 캐릭터는 id가 0이고 그림은 위키 얼굴 그림. */
    private class Pick(val name: String, val element: String, val image: String, val id: Int, val rarity: Int)

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
        val options = listOf("off" to getString(R.string.lobby_voice_off)) + Lobby.VOICE_LANGUAGES.keys.map { it to voiceLabel(it) }
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
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (code != "off") leftMargin = dp(6) })
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
        internal const val TAB_CHARACTER = 0
        internal const val TAB_BG = 1
        internal const val TAB_VOICE = 2
        fun intent(context: Context) = Intent(context, LobbyStyleActivity::class.java)
    }
}
