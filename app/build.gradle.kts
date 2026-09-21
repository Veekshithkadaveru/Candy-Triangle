plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.krafted.candytriangle"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.krafted.candytriangle"
        // PRD: "Platform: Android (API 26+)".
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // The C2 JVM verification suite (§11) exercises engine/board/level code that
            // touches android.jar stubs (e.g. Log, Color). Returning defaults instead of
            // throwing keeps those pure-JVM tests runnable without Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // MVVM + StateFlow (§8): ViewModels hosted in Compose, lifecycle-aware collection.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Screen graph: Home / Map / Game / Results / Jar / Settings (D4).
    implementation(libs.androidx.navigation.compose)

    // Persistence (§10) and Gson parsing of assets/config.json + assets/levels.json (§6.3).
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.gson)

    // Physics/game-thread coroutines and engine SharedFlow bridge (B4).
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}