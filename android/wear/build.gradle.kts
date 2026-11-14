plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.byd.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Same id and signing key as the phone app: the Wearable Data Layer only links an app
        // on the watch with the identical app on the phone.
        applicationId = "com.example.byd"
        minSdk = 30 // Wear OS 3+
        targetSdk = 34 // Wear OS 5; newer targets hide the watch's "reduce motion" setting from Compose
        versionCode = 11
        versionName = "2.8"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug") // must match the phone app's key
            isShrinkResources = true
            optimization {
                enable = true
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
}
