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
    namespace = "com.lesumein.hoyocheckin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lesumein.hoyocheckin"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.4.4"
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar의 org.json은 JVM 테스트에서 동작하지 않아 실제 구현을 넣는다.
    testImplementation("org.json:json:20240303")
}
