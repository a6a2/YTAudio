plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (CI secrets) or from ~/.gradle/gradle.properties.
// Nothing secret lives in this repository. Without a key, the release build falls back to the
// debug key so contributors can still build and test (such APKs can't update a properly signed install).
fun secret(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: providers.gradleProperty(prop).orNull

val keystorePath = secret("KEYSTORE_PATH", "ytaudio.keystore")

android {
    namespace = "app.ytaudio"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.ytaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "2.3.0"
        // "owner/repo" whose GitHub Releases the in-app updater follows. GitHub Actions provides it automatically;
        // for a local build pass -Pytaudio.repo=owner/repo, or leave empty to switch the updater off.
        val updateRepo = providers.gradleProperty("ytaudio.repo").orNull ?: System.getenv("GITHUB_REPOSITORY") ?: ""
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = secret("KEYSTORE_PASSWORD", "ytaudio.storePassword")
                keyAlias = secret("KEY_ALIAS", "ytaudio.keyAlias")
                keyPassword = secret("KEY_PASSWORD", "ytaudio.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // R8 is opt-in until it has been tested on a device: ./gradlew assembleRelease -Pminify
            val minify = project.hasProperty("minify")
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real org.json for JVM tests (Android's is a stub there)
}
