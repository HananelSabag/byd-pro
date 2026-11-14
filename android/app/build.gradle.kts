import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional defaults baked into the build, so the app works without typing them in Settings.
// Put them in local.properties (never committed) or pass them with -P:
//   byd.serverUrl=https://your-server.onrender.com
//   byd.serverSecret=<the AUTH_SECRET set on the server>
// Testing against the local fake car: -PbydServerUrl=http://10.0.2.2:8765
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val serverUrl = (findProperty("bydServerUrl") as String?) ?: localProps.getProperty("byd.serverUrl", "")
val serverSecret = (findProperty("bydServerSecret") as String?) ?: localProps.getProperty("byd.serverSecret", "")

android {
    namespace = "com.example.byd"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.byd"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "2.8"

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "DEFAULT_SERVER_SECRET", "\"$serverSecret\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Release = R8-optimised Compose: much smoother and lighter than a debug build.
            // Signed with the local debug key so it installs straight from Android Studio.
            signingConfig = signingConfigs.getByName("debug")
            isShrinkResources = true
            // Whole-app R8. (A partial packageScope made R8 break class access at startup.)
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
        buildConfig = true
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
