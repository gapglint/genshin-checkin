import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 로그인 진단용 작은 앱. 본 앱과 패키지가 달라 같이 깔 수 있고, 계정 정보는 저장하지 않는다.
// 서명은 본 앱과 같은 키(keystore.properties 또는 CI 환경 변수)를 쓴다.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotEmpty() }
val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")

android {
    namespace = "kr.sumi.logintest"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.sumi.logintest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
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
