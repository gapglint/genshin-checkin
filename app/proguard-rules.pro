# 이 앱은 리플렉션·직렬화 라이브러리를 쓰지 않는다(JSON은 org.json으로 직접 읽음).
# Worker·액티비티·리시버는 라이브러리와 매니페스트 규칙으로 자동 보존된다.

# 크래시 보고에서 줄 번호를 알 수 있게 남긴다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
