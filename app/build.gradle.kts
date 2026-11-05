plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.xmb.launcher"
    compileSdk = 34
    defaultConfig { applicationId = "com.xmb.launcher"; minSdk = 25; targetSdk = 28; versionCode = 4; versionName = "1.3" }
    // Signing key comes from private GitHub secrets (written to app/xmb-signing.keystore by CI).
    // Never commit the key: without it, local builds fall back to the normal debug key.
    val xmbPass = System.getenv("XMB_KEYSTORE_PASSWORD")
    val xmbKey = file("xmb-signing.keystore")
    if (xmbPass != null && xmbKey.exists()) {
        signingConfigs.create("xmb") {
            storeFile = xmbKey
            storePassword = xmbPass
            keyAlias = "xmb"
            keyPassword = xmbPass
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("xmb")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("xmb")
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-svg:2.6.0")
}
