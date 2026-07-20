plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.android_explorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.android_explorer"
        minSdk = 31
        targetSdk = 35
        // Version is injected by CI from the release tag (`-PappVersionName` / `-PappVersionCode`, see
        // .github/workflows/release.yml) so the built-in version — shown on Home via BuildConfig — always
        // matches the GitHub release. The defaults track the current release for local/sideload builds.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 10_200
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.2.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Point the debug config at a keystore committed to the repo (app/debug.keystore) so every
        // build — local AND CI — signs with the SAME key. Without this, CI has no keystore and Gradle
        // auto-generates a random debug key each run, giving every release APK a different signature
        // ("App not installed" on upgrade). Standard debug credentials; safe to commit (no secret).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Keep release unminified for reliability (archive libs use reflection); signed with the
            // committed debug key so release APKs install/upgrade directly for sideloading.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // commons-compress / junrar pull in duplicate metadata files.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE*",
                "META-INF/LICENSE*",
                "META-INF/versions/**",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // Archive engine
    implementation(libs.commons.compress)
    implementation(libs.junrar)
    implementation(libs.xz)
    implementation(libs.zip4j)

    // Image thumbnails
    implementation(libs.coil.compose)

    // Google Drive: on-device OAuth (Authorization API) + Drive REST over OkHttp
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
