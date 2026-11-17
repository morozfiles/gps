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
        versionCode = 19
        versionName = "0.9.7-alpha"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
