package kr.sumi.checkin

import android.content.Context
import java.io.File

/**
 * 스토어 스크린샷용 게임 아이템 그림. store/fetch_item_art.py가 받아 둔 것(store/item-art/, 저장소에는 없다)이 있으면
 * 이름 → 그림 주소를 주고, 그림 파일을 앱이 받아 둔 것처럼 넣어 준다. 없으면 빈 주소라 앱 아이콘으로 그린다.
 */
object StoreItemArt {
    private val dir = File("../store/item-art")

    /** 아이템 이름 → 호요랩 그림 주소. */
    val urls: Map<String, String> by lazy {
        File(dir, "items.tsv").takeIf { it.exists() }?.readLines().orEmpty()
            .mapNotNull { line -> line.split("\t").takeIf { it.size == 3 }?.let { it[0] to it[1] } }.toMap()
    }

    /** 이름에 맞는 그림 주소. 이번 달 목록에 같은 이름이 없으면 같은 종류(경험책·광석)로, 그것도 없으면 빈 문자열. */
    fun url(name: String): String = urls[name]
        ?: urls.entries.firstOrNull { (n, _) -> listOf("경험", "광", "모라", "원석").any { name.contains(it) && n.contains(it) } }?.value
        ?: ""

    /** 받아 둔 그림 파일을 [context]의 임시 저장 공간에 앱이 받은 것처럼 넣는다. */
    fun install(context: Context) {
        val items = File(context.cacheDir, "items").apply { mkdirs() }
        File(dir, "items.tsv").takeIf { it.exists() }?.readLines()?.forEach { line ->
            val (_, url, file) = line.split("\t").takeIf { it.size == 3 } ?: return@forEach
            File(dir, file).takeIf { it.exists() }?.copyTo(File(items, "${url.hashCode().toUInt()}.png"), overwrite = true)
        }
    }
}
