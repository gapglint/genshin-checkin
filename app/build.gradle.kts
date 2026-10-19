import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 화면 스냅숏 테스트(에뮬레이터 없이 레이아웃을 PNG로 그린다)
    id("app.cash.paparazzi")
}

// 릴리스 서명 정보: 로컬은 keystore.properties(커밋 금지), CI는 환경 변수에서 읽는다.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotEmpty() }
val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")

android {
    namespace = "kr.sumi.checkin"
    // 플레이 스토어 요구에 맞춰 대상은 최신(36)으로 두고, 컴파일은 35로 한다.
    // 화면 스냅숏 도구(Paparazzi 1.3.5)가 API 36의 android.jar를 아직 읽지 못하기 때문이다.
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.sumi.checkin"
        minSdk = 26
        targetSdk = 36
        versionCode = 57
        versionName = "0.13.12"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 용량 줄이기: 안 쓰는 코드·리소스를 빼고 이름을 줄인다(R8). 동작은 그대로다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // 앱은 한국어뿐이라 라이브러리에 딸린 다른 언어 번역은 넣지 않는다.
    androidResources {
        localeFilters += listOf("ko")
    }

    packaging {
        resources.excludes += listOf(
            "META-INF/*.version", "META-INF/**/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin",
            "META-INF/com/android/build/gradle/app-metadata.properties", "kotlin-tooling-metadata.json",
        )
    }

    // 의존성 목록 블록은 APK에서 뺀다(플레이 스토어용 번들에는 남긴다).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    testImplementation("junit:junit:4.13.2")
    // android.jar의 org.json은 JVM 테스트에서 동작하지 않아 실제 구현을 넣는다.
    testImplementation("org.json:json:20240303")
}
