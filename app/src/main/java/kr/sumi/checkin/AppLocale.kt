package kr.sumi.checkin

import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 앱 언어: 휴대전화 언어(기본) 또는 한국어·일본어·영어를 앱 안에서 고른다.
 * Android 13부터는 시스템의 앱별 언어(LocaleManager)에 맡기고 시스템 설정의 '앱 언어'와 같은 값을 쓴다.
 * 그 전에는 고른 언어로 앱의 리소스 설정을 바꾸고, 화면마다 그 언어로 연다([wrap]).
 */
object AppLocale {
    /** 고를 수 있는 언어(빈 문자열은 휴대전화 언어). */
    val CHOICES = listOf("", "ko", "ja", "en")
    private const val PREFS = "app_locale"

    /** 고른 언어. Android 13부터는 시스템 값, 그 전에는 앱에 저장한 값. */
    fun chosen(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) systemChoice(context)
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "") ?: ""

    fun set(context: Context, lang: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // 시스템이 앱의 화면들을 그 언어로 다시 연다.
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                if (lang.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(lang)
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang).apply()
        apply(context)
        Notifier.createChannel(context.applicationContext)
    }

    private fun systemChoice(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        val list = context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        return if (list.isEmpty) "" else list[0].language
    }

    /** Android 12까지: 고른 언어를 앱 전체(알림·위젯 문구를 만드는 앱 Context 포함)와 기본 언어 목록에 넣는다. */
    @Suppress("DEPRECATION")
    fun apply(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val lang = chosen(context).ifEmpty { return }
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        LocaleList.setDefault(LocaleList(locale))
        val res = context.applicationContext.resources
        val config = android.content.res.Configuration(res.configuration).apply { setLocale(locale); setLocales(LocaleList(locale)) }
        res.updateConfiguration(config, res.displayMetrics)
    }

    /** Android 12까지: 화면(Activity)을 고른 언어로 연다. 휴대전화 언어면 그대로. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", "").orEmpty().ifEmpty { return base }
        val locale = Locale.forLanguageTag(lang)
        val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(locale); setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }

    /** 고르기 목록·설정 타일에 보일 이름. 언어 이름은 그 언어로(한국어·日本語·English). */
    fun label(context: Context, lang: String): String =
        if (lang.isEmpty()) context.getString(R.string.main_language_system)
        else Locale.forLanguageTag(lang).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.uppercase() } }
}
