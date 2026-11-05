plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.xmb.launcher"
    compileSdk = 34
    defaultConfig { applicationId = "com.xmb.launcher"; minSdk = 25; targetSdk = 28; versionCode = 2; versionName = "1.1" }
    signingConfigs {
        // Fixed key committed on purpose: every build (CI or any PC) is signed the same way,
        // so new versions always install over the old one without uninstalling.
        create("xmb") {
            storeFile = file("xmb-signing.keystore")
            storePassword = "xmblauncher"
            keyAlias = "xmb"
            keyPassword = "xmblauncher"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("xmb") }
        getByName("release") { signingConfig = signingConfigs.getByName("xmb") }
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
