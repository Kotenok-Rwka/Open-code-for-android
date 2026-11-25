plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.chaquo.python")
}

android {
    namespace = "com.pocketcode"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketcode"
        minSdk = 26
        targetSdk = 34
        // Версия X.Y.Z: CI передаёт готовую в APP_VERSION (патч считается по коммитам, см. version.properties).
        // Без CI (локальная сборка, тесты) берём мажор.минор из version.properties и патч 1.
        val baseFromFile = rootProject.file("version.properties").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("VERSION_BASE=") }?.substringAfter("=")?.trim().orEmpty()
        val appVersion = System.getenv("APP_VERSION")?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
            ?: "${baseFromFile.ifBlank { "4.0" }}.1"
        val vp = appVersion.split(".").map { it.toInt() }
        versionCode = vp[0] * 1_000_000 + vp[1] * 1_000 + vp[2]
        versionName = appVersion

        ndk {
            // 64-битные и 32-битные ARM-телефоны; x86 нужен только эмуляторам
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Фиксированный debug-ключ в репозитории: все сборки из GitHub Actions
    // подписаны одним ключом, и APK ставится поверх предыдущей версии.
    signingConfigs {
        getByName("debug") {
            // Свой ключ: если CI передал его через секреты, подписываем им; иначе общий debug-ключ
            val ownKs = System.getenv("PC_KEYSTORE")
            if (ownKs != null && File(ownKs).exists()) {
                storeFile = File(ownKs)
                storePassword = System.getenv("PC_STORE_PASS")
                keyAlias = System.getenv("PC_KEY_ALIAS")
                keyPassword = System.getenv("PC_KEY_PASS")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // В релизы GitHub уходит именно debug-APK. С включённой отладкой Android запускает приложение
        // в медленном режиме, и Compose теряет в разы кадров, поэтому отладку выключаем.
        debug {
            isDebuggable = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        // BuildConfig.VERSION_NAME нужен экрану диагностики
        buildConfig = true
        // AIDL-интерфейс помощника управления телефоном (src/main/aidl)
        aidl = true
    }
    packaging {
        // proot лежит в jniLibs и должен быть распакован на диск, чтобы его можно было запустить
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1",
                "plugin.properties", "about.html"
            )
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // QR-код для быстрой настройки синхронизации с ПК
    implementation("com.google.zxing:core:3.5.3")
    // Сканирование QR настройки синхронизации
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("org.apache.commons:commons-compress:1.26.2")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Свой TLS для сопряжения adb по коду на Android 11–13 (в системном нет экспорта ключевого материала)
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // org.json не замокан в локальных тестах — даём настоящую реализацию
    testImplementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Распознавание текста с экрана (барабаны выбора, самописные интерфейсы): модели работают на телефоне, как в Mika
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Git без внешних программ: чистая Java-реализация
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // Шифрованное хранилище для API-ключей
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
