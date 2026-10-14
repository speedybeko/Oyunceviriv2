plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.berkay.oyunceviri"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.berkay.oyunceviri"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    // Sabit debug anahtarı: yeni sürümler eskisinin üstüne kurulabilsin (yalnızca kişisel kullanım)
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    // Android'den bağımsız çekirdek mantık (OCR karar mantığı, çeviri yönetimi, protokol); birim testleri orada
    implementation(project(":core"))
    // Coroutines + Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Activity Result API (registerForActivityResult) ve yaşam döngüsüne bağlı toplama
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // Cihaz üstünde OCR (Latin alfabesi: İngilizce altyazılar için yeterli)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Cihaz üstünde çeviri (İngilizce -> Türkçe, model ilk seferde indirilir)
    implementation("com.google.mlkit:translate:17.0.3")
    // TV'ye WebSocket ile altyazı göndermek için
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
