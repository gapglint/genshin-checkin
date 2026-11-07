package kr.sumi.checkin

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 단위 테스트용 Text: 앱 리소스 파일(values 폴더들의 strings로 시작하는 xml)을 직접 읽어 문구를 만든다.
 * 테스트 클래스에서 init { TestText.install() }(한국어). 영어는 install("en"), 일본어는 install("ja").
 */
object TestText {
    private val cache = mutableMapOf<String, Pair<Map<String, String>, Map<String, Map<String, String>>>>()

    fun install(lang: String = "ko") {
        val (strings, plurals) = load(lang)
        Text.resolver = object : Text.Resolver {
            override fun string(id: Int, args: Array<out Any>): String {
                val name = name(R.string::class.java, id)
                return format(strings[name] ?: error("문구 없음: $name ($lang)"), args)
            }

            override fun plural(id: Int, count: Int, args: Array<out Any>): String {
                val name = name(Class.forName("kr.sumi.checkin.R\$plurals"), id) // 복수 문구가 하나도 없으면 R.plurals가 없다
                val forms = plurals[name] ?: error("복수 문구 없음: $name ($lang)")
                val form = forms[if (count == 1 && "one" in forms) "one" else "other"] ?: forms.values.first()
                return format(form, args)
            }
        }
    }

    private fun name(cls: Class<*>, id: Int) =
        cls.fields.firstOrNull { it.getInt(null) == id }?.name ?: error("리소스 번호를 모름: $id")

    private fun format(s: String, args: Array<out Any>) = if (args.isEmpty()) s else String.format(Locale.ROOT, s, *args)

    private fun load(lang: String) = cache.getOrPut(lang) {
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        // 기본(values)은 영어. 다른 언어는 values를 먼저 읽고 values-언어로 덮는다(안드로이드와 같다).
        val dirs = listOf("values") + if (lang.isEmpty() || lang == "en") emptyList() else listOf("values-$lang")
        for (dir in dirs) {
            val files = File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
            for (file in files.sortedBy { it.name }) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val stringNodes = doc.getElementsByTagName("string")
                for (i in 0 until stringNodes.length) {
                    val e = stringNodes.item(i) as Element
                    strings[e.getAttribute("name")] = unescape(e.textContent)
                }
                val pluralNodes = doc.getElementsByTagName("plurals")
                for (i in 0 until pluralNodes.length) {
                    val e = pluralNodes.item(i) as Element
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
        strings to plurals
    }

    /** 안드로이드 문자열 규칙: 앞뒤 따옴표, \n, \', \", \\, \@, \? */
    private fun unescape(raw: String): String {
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }
}
