package kr.sumi.checkin

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 호요랩에서 받는 자료(보상 이름, 이벤트 제목, 위키 대사·캐릭터 이름)의 언어. 앱 언어를 따른다:
 * 한국어 → ko-kr, 일본어 → ja-jp, 그 밖 → en-us.
 * 앱이 시작될 때(App.onCreate) 기기 언어 목록을 보도록 준비한다. 준비 전(단위 테스트)에는 한국어이고, 테스트는 [override]로 바꾼다.
 */
object DataLang {
    const val KO = "ko-kr"
    const val JA = "ja-jp"
    const val EN = "en-us"

    /** 테스트용으로 언어를 고정한다. null이면 기기 언어. */
    @Volatile var override: String? = null

    /** 기기 언어 목록(앞이 우선). 준비 전에는 null. */
    @Volatile private var languages: (() -> List<String>)? = null

    fun init() {
        languages = {
            val list = android.os.LocaleList.getDefault()
            (0 until list.size()).map { list[it].language }.ifEmpty { listOf(Locale.getDefault().language) }
        }
    }

    /** 지금 자료 언어: "ko-kr" | "ja-jp" | "en-us". */
    fun code(): String = override ?: languages?.let { fromLanguages(it()) } ?: KO

    /** 앞 두 글자(리딤 응답 문구의 lang 값 등): "ko" | "ja" | "en". */
    fun short() = code().substringBefore('-')

    fun isKorean() = code() == KO

    /**
     * 기기 언어 목록에서 앱이 가진 언어(한국어·일본어·영어)를 앞에서부터 찾는다. 안드로이드가 문구 리소스를 고르는 것과 같은 순서다.
     * 하나도 없으면 영어 자료.
     */
    internal fun fromLanguages(languages: List<String>): String {
        for (l in languages) when (l.lowercase()) {
            "ko" -> return KO
            "ja" -> return JA
            "en" -> return EN
        }
        return EN
    }
}

/**
 * 화면에 보일 캐릭터 이름. 앱은 캐릭터를 늘 한국어 위키 이름으로 기억하고(최애, 얼굴 그림, 복각표), 보일 때만 바꾼다.
 * 한국어면 그대로, 아니면 위키 캐릭터 목록을 그 언어로도 받아 문서 번호로 맞춘 이름([Lobby.refreshLocalNames]).
 * 맞춘 이름이 없으면 영어 이름(복각표 자료에 있을 때), 그것도 없으면 한국어 이름.
 */
object CharNames {
    private var app: Context? = null
    @Volatile private var memo: Pair<String, Map<String, String>>? = null

    fun init(context: Context) { app = context.applicationContext }

    /** [ko]: 한국어 이름, [en]: 복각표의 영어 이름(있으면). */
    fun of(ko: String, en: String? = null): String {
        val lang = DataLang.code()
        if (lang == DataLang.KO) return ko
        val english = en?.takeIf { it.isNotEmpty() }
        if (lang == DataLang.EN && english != null) return english
        return names(lang)[ko] ?: FIXED[ko]?.get(lang) ?: english ?: ko
    }

    /** 위키 캐릭터 목록에서 빼는 이름(전적의 여행자). */
    private val FIXED = mapOf("여행자" to mapOf(DataLang.JA to "旅人", DataLang.EN to "Traveler"))

    /** 새로 받은 이름표를 바로 쓰게 한다. */
    internal fun set(lang: String, names: Map<String, String>) { memo = lang to names }

    private fun names(lang: String): Map<String, String> {
        memo?.let { (l, m) -> if (l == lang) return m }
        val context = app ?: return emptyMap()
        return Lobby.cachedLocalNames(context, lang).also { memo = lang to it }
    }

    internal fun decode(raw: String?): Map<String, String> = try {
        raw?.let { JSONObject(it) }?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }
}
