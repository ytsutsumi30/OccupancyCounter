import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.occupancycounter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.occupancycounter"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // --- キーストア情報を local.properties から読み込む ---
    val localProps = Properties().also { props ->
        val f = rootProject.file("local.properties")
        if (f.exists()) props.load(f.inputStream())
    }

    signingConfigs {
        create("release") {
            storeFile     = localProps.getProperty("KEYSTORE_FILE")?.let { file(it) }
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD") ?: ""
            keyAlias      = localProps.getProperty("KEY_ALIAS") ?: ""
            keyPassword   = localProps.getProperty("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}


dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // CameraX
    val cameraxVersion = "1.4.2" // IDE cache issue: actual version is 1.4.2, sync to refresh
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Face Detection (オフライン動作)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // OkHttp (サーバー送信)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
