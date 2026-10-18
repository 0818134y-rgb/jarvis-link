plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zen.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zen.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
        // 現行のAndroidスマホ(64bit ARM)だけに絞ってAPKを小さくする
        ndk { abiFilters += "arm64-v8a" }
    }

    // CIのたびに署名が変わると上書きインストールできないので、署名鍵をリポジトリで固定する
    // (個人利用のデバッグ用鍵。ストア公開時は別の鍵を使うこと)
    signingConfigs {
        getByName("debug") {
            storeFile = file("jarvis-debug.keystore")
            storePassword = "jarvisdebug"
            keyAlias = "jarvis"
            keyPassword = "jarvisdebug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // UI
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 非同期
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")


    // 端末間連携: サーバー(Java-WebSocket) / クライアント(OkHttp)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ルールの単体テスト (Android の org.json はテスト時スタブなので本物を入れる)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
