import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets stay out of source/VCS: the HQ API token is read from local.properties
// (git-ignored) and injected as a BuildConfig field. Set `hqApiToken=…` there.
val localProps = Properties()
rootProject.file("local.properties").let { f ->
    if (f.exists()) FileInputStream(f).use { localProps.load(it) }
}
val hqApiToken: String = localProps.getProperty("hqApiToken") ?: ""

android {
    namespace = "com.hq.widget"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.hq.widget"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        buildConfigField("String", "HQ_TOKEN", "\"$hqApiToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // TWA: runs the real PWA fullscreen in Chrome's engine (own icon, push preserved)
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
}
