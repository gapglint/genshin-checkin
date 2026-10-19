package kr.sumi.checkin.logintest

/**
 * 시험해 볼 로그인 방식들. 하나씩 바꿔 보며 어느 쪽에서 로그인이 되는지 본다.
 * A가 본 앱(0.13.3)과 똑같은 방식이고, 나머지는 A에서 한 가지씩만 바꾼다(G만 둘을 같이 바꾼다).
 */
data class Strategy(
    val id: String,
    val title: String,
    val description: String,
    val start: Start = Start.CHECK_IN,
    /** 본 앱처럼 출석 페이지의 「로그인」 버튼을 스크립트로 대신 누른다. */
    val autoOpenLogin: Boolean = true,
    /** 웹뷰만 GPU 대신 소프트웨어로 그린다. */
    val softwareLayer: Boolean = false,
    /** 창 전체를 GPU 없이 그린다(매니페스트에서 하드웨어 가속을 끈 화면으로 연다). */
    val noGpuWindow: Boolean = false,
    /** 휴대폰 다크 모드가 웹 페이지를 억지로 어둡게 칠하지 못하게 한다. */
    val noForcedDark: Boolean = false,
    /** 브라우저 정보(UA)에서 웹뷰 표시(wv)를 빼지 않고 그대로 둔다. */
    val keepWebViewUa: Boolean = false,
) {
    enum class Start(val url: String) {
        CHECK_IN("https://act.hoyolab.com/ys/event/signin-sea-v3/index.html?act_id=e202102251931481&lang=ko-kr"),
        MOBILE("https://m.hoyolab.com/#/timeline"),
    }

    companion object {
        val ALL = listOf(
            Strategy("A", "A. 지금 앱 그대로", "본 앱과 똑같은 방식이에요. 먼저 이걸로 증상을 확인해요."),
            Strategy("B", "B. 소프트웨어로 그리기", "로그인 화면을 그래픽칩 대신 CPU로 그려요.", softwareLayer = true),
            Strategy("C", "C. 화면 전체 그래픽칩 끄기", "B보다 더 강하게, 화면 전체를 그래픽칩 없이 그려요.", noGpuWindow = true),
            Strategy("D", "D. 다크 모드 영향 끄기", "다크 모드가 웹 페이지 색을 바꾸지 못하게 해요.", noForcedDark = true),
            Strategy("E", "E. 브라우저 정보 그대로", "앱 안 웹 화면이라는 표시를 숨기지 않아요.", keepWebViewUa = true),
            Strategy("F", "F. 로그인 창 직접 누르기", "로그인 창을 자동으로 띄우지 않아요. 페이지의 「로그인」을 직접 눌러 주세요.", autoOpenLogin = false),
            Strategy("G", "G. 호요랩 모바일 사이트", "출석 페이지 대신 호요랩 사이트에서 로그인해요. 가운데 「로그인」을 눌러 주세요.", start = Start.MOBILE, autoOpenLogin = false),
            Strategy("H", "H. 모바일 사이트 + 소프트웨어", "G와 B를 같이 써요.", start = Start.MOBILE, autoOpenLogin = false, softwareLayer = true),
        )

        fun byId(id: String) = ALL.first { it.id == id }
    }
}
