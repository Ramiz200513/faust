plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization) // нужен для @Serializable в DeviceRepository
}

android {
    namespace = "com.jarvis.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
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
    implementation(project(":shared"))              // наш протокол
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // WebSocket client — OkHttp напрямую (надёжно на Android)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Хранение настроек (адрес релея, токен) между запусками
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // NotificationCompat/NotificationChannel — уведомление о завершении задачи агента
    implementation("androidx.core:core-ktx:1.13.1")

    // Wake-word + офлайн-распознавание речи (Vosk, Apache 2.0).
    // Ни ключей, ни регистрации, ни сети. Суффикс @aar обязателен для обеих
    // зависимостей: обе поставляются только как AAR с нативными .so внутри.
    // JNA — то, через что Java-обёртка Vosk вызывает нативную библиотеку.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // Сканер QR-кода для привязки ПК: готовый экран камеры + разбор кода,
    // не нужно писать свой CameraX preview.
    implementation(libs.zxing.android.embedded)

    // Compose UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
