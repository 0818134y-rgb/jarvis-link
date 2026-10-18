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
        versionCode = 1
        versionName = "1.0.0"
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

    // 端末内LLM (Gemma等の .task モデルを実行)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // 端末間連携: サーバー(Java-WebSocket) / クライアント(OkHttp)
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ルールの単体テスト (Android の org.json はテスト時スタブなので本物を入れる)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
