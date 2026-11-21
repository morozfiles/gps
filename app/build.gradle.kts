plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Use ONE permanent signing identity for all CI debug APK updates.
// Keep the PKCS12 keystore and its password outside the repository.
// Without credentials Gradle retains its normal ephemeral debug signing,
// which is NOT compatible across independent GitHub Actions runners.
val persistentStoreFile = System.getenv("OBDGPS_SIGNING_STORE_FILE")
    ?.takeIf { it.isNotBlank() }
val persistentPassword = System.getenv("OBDGPS_SIGNING_PASSWORD")
    ?.takeIf { it.isNotBlank() }
if ((persistentStoreFile == null) != (persistentPassword == null)) {
    throw GradleException("Incomplete permanent signing credentials")
}

// A separately installable migration build. It never overwrites
// ua.obdgps.nav or its downloaded offline maps.
val parallelInstall = providers.gradleProperty("parallelInstall").orNull == "true"

android {
    signingConfigs {
        if (persistentStoreFile != null && persistentPassword != null) {
            create("persistentDebug") {
                storeFile = file(persistentStoreFile)
                storePassword = persistentPassword
                keyAlias = "obdgps"
                keyPassword = persistentPassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (persistentStoreFile != null && persistentPassword != null) {
                signingConfig = signingConfigs.getByName("persistentDebug")
            }
        }
    }
    namespace = "ua.obdgps.nav"
    buildFeatures { buildConfig = true }
    compileSdk = 36
    defaultConfig {
        applicationId = if (parallelInstall) "ua.obdgps.nav.fyt7870.parallel"
            else "ua.obdgps.nav.fyt7870"
        manifestPlaceholders["appDisplayName"] = if (parallelInstall)
            "FYT 7870 Google Nav 2" else "FYT 7870 Google Nav"
        minSdk = 31
        targetSdk = 35
        versionCode = 63
        versionName = "0.13.11-fyt-3d-buildings"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Dedicated FYT keys take precedence. Phone keys serve only as a build
        // fallback and MUST be separately allowlisted for this package/signature.
        val mapsKey=(System.getenv("GOOGLE_MAPS_FYT7870_KEY") ?: "")
            .ifBlank { System.getenv("GOOGLE_MAPS_ANDROID_KEY") ?: "" }
        val routesKey=(System.getenv("GOOGLE_ROUTES_FYT7870_KEY") ?: "")
            .ifBlank { System.getenv("GOOGLE_ROUTES_API_KEY") ?: "" }
        manifestPlaceholders["googleMapsApiKey"] = mapsKey
        buildConfigField("boolean", "GOOGLE_MAPS_CONFIGURED",
            mapsKey.isNotBlank().toString())
        buildConfigField("String", "GOOGLE_ROUTES_KEY", "\""+routesKey+"\"")
        buildConfigField("boolean", "FYT7870_HEAD_UNIT", "true")
        buildConfigField("boolean", "FYT_GOOGLE_KEY_FALLBACK",
            (System.getenv("GOOGLE_MAPS_FYT7870_KEY").isNullOrBlank() ||
                System.getenv("GOOGLE_ROUTES_FYT7870_KEY").isNullOrBlank()).toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
