plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ua.obdgps.nav"
    compileSdk = 36
    defaultConfig {
        applicationId = "ua.obdgps.nav"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0-alpha"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
