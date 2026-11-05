import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Backend settings come from local.properties (git-ignored):  debunk.backendUrl=https://your-api.onrender.com
//                                                            debunk.backendKey=<same as BACKEND_API_KEY>
// Leave backendUrl empty to keep using the offline MockVerifier.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(name: String) = (localProps.getProperty(name) ?: "").trim()

android {
    namespace = "com.debunk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.debunk.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-p2"
        buildConfigField("String", "BACKEND_URL", "\"${prop("debunk.backendUrl").trimEnd('/')}\"")
        buildConfigField("String", "BACKEND_KEY", "\"${prop("debunk.backendKey")}\"")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
