# 이 앱은 리플렉션·직렬화 라이브러리를 쓰지 않는다(JSON은 org.json으로 직접 읽음).
# 액티비티·서비스·리시버는 매니페스트 규칙으로 자동 보존된다.

# 코틀린이 넣는 null 인자 검사를 뺀다(코틀린 코드끼리는 컴파일할 때 이미 확인된다).
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
}

# 크래시 보고에서 줄 번호를 알 수 있게 남긴다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
