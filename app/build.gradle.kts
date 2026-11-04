plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.ytaudio"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.ytaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "2.0.4"
    }
    signingConfigs {
        // Fixed key so every build installs as an update over the previous one.
        // Personal-use key: replace with your own (and keep it secret) if you distribute the app.
        create("shared") {
            storeFile = file("ytaudio.keystore")
            storePassword = "ytaudio"
            keyAlias = "ytaudio"
            keyPassword = "ytaudio"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // YouTube stream extraction. Bump to the latest tag when YouTube changes break playback.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
