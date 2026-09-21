import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.3.10"
}

// Параметры подписи читаются из файла вне репозитория (D:\KeysBackup\...),
// чтобы ключ и пароли физически не могли попасть в git.
// Путь можно переопределить: -PpersonalskyKeystore=<путь к .properties>.
// Если файла нет — release собирается без подписи, как обычный unsigned APK.
val keystorePropertiesFile: File =
    (project.findProperty("personalskyKeystore") as String?)
        ?.let { File(it) }
        ?: File("D:/KeysBackup/personal-sky-keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { stream -> load(stream) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.personalsky.app"
    // compileSdk 37: требуется библиотеками AndroidX 1.19 / Lifecycle 2.11.
    // targetSdk 36 (Android 16): требование Google Play для новых версий с 31.08.2026.
    // Параметры независимы: compileSdk задаёт доступные API, targetSdk — поведение рантайма.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.personalsky.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"https://www.dvorzhik.ru/personal-sky/privacy\""
        )
        buildConfigField(
            "String",
            "PROJECT_PAGE_URL",
            "\"https://www.dvorzhik.ru/personal-sky\""
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        // Схемы Room выгружаются в репозиторий: по ним видно в diff, что
        // изменилось в базе между версиями, и тест миграций опирается
        // на ту же схему, что собирает приложение.
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
