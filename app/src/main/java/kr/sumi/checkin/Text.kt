package kr.sumi.checkin

import android.content.Context

/**
 * 화면·알림 문구(언어별 리소스: values = 한국어, values-en = 영어).
 * Context가 있는 곳은 context.getString을 쓰고, Context 없이 문구를 만드는 함수(알림 문구, 요약 등)는 이것을 쓴다.
 * 앱이 시작될 때(App.onCreate) 앱 Context로 준비한다. 단위 테스트는 TestText로 리소스 파일을 직접 읽어 넣는다.
 */
object Text {
    @Volatile
    var resolver: Resolver? = null

    interface Resolver {
        fun string(id: Int, args: Array<out Any>): String
        fun plural(id: Int, count: Int, args: Array<out Any>): String
    }

    fun init(context: Context) {
        val app = context.applicationContext
        resolver = object : Resolver {
            override fun string(id: Int, args: Array<out Any>) = if (args.isEmpty()) app.getString(id) else app.getString(id, *args)
            override fun plural(id: Int, count: Int, args: Array<out Any>) = app.resources.getQuantityString(id, count, *args)
        }
    }

    fun get(id: Int, vararg args: Any): String =
        (resolver ?: error("Text.init이 아직 불리지 않았어요")).string(id, args)

    /** 개수에 따라 형태가 바뀌는 문구(영어 단수·복수). 한국어는 한 가지 형태만 둔다. */
    fun plural(id: Int, count: Int, vararg args: Any): String =
        (resolver ?: error("Text.init이 아직 불리지 않았어요")).plural(id, count, args)
}
