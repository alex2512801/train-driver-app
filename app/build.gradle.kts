plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.traindriver.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.traindriver.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Без этого debug-ключ генерируется автоматически и РАЗНЫЙ на каждой машине/запуске —
    // у каждого отдельного запуска GitHub Actions (свежий runner без ~/.android) и у Android
    // Studio на компьютере машиниста получался СВОЙ ключ. Android отказывается обновить
    // установленное приложение новым APK с другой подписью (тихо, без явной ошибки на
    // некоторых прошивках) — именно поэтому после установки "исправленного" APK с GitHub
    // ничего не менялось: старое приложение с другой подписью просто не обновлялось. Один
    // закоммиченный keystore (app/debug.keystore) делает подпись одинаковой везде.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}
