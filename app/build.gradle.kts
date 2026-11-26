import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is loaded from a gitignored signing.properties at the repo root (see
// that file). It points at the stable release keystore — the SAME key every public APK
// is signed with, so updates install in place. If the file is absent (e.g. a contributor
// just building locally), the release build falls back to the debug keystore.
val signingProps = Properties()
rootProject.file("signing.properties").let { f ->
    if (f.exists()) f.inputStream().use { signingProps.load(it) }
}
val releaseStoreFile = signingProps.getProperty("storeFile")?.let { path ->
    val f = File(path)
    if (f.isAbsolute) f else rootProject.file(path)
}
val hasReleaseSigning = releaseStoreFile?.exists() == true

android {
    namespace = "io.github.saltmango.navbeam"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.saltmango.navbeam"
        // API 29 (Android 10) is the real floor: the dash link is built on WifiNetworkSpecifier
        // + ConnectivityManager.requestNetwork(specifier), both added in API 29, and that IS the
        // app. Below 29 the core feature can't run and the unguarded calls would crash, so we
        // don't pretend to support it. Also makes the API-26 notification paths always safe.
        minSdk = 29
        targetSdk = 36
        // 1.3.3 = Google login button layout relocation to top card.
        // versionCode 16 > 15 so it installs in place as an update.
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 16
        versionName = project.findProperty("versionName")?.toString() ?: "1.3.4"

        // Ship arm64-v8a only: every modern OEM phone (OnePlus/Samsung/Xiaomi/Nothing…) is
        // 64-bit ARM; the other ABIs (armeabi-v7a/x86/x86_64) are old 32-bit or emulators. This
        // cuts the universal APK from ~107 MB to well under GitHub's 100 MB file limit so the
        // test build can live in the repo for direct download.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProps = Properties().apply {
            val f = project.rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val mapsApiKey = localProps.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")

        // Routing backend — driven by local.properties so you never need to touch this file.
        // Set ROUTING_BACKEND=directions  to use Google Directions API (default, no extra setup)
        // Set ROUTING_BACKEND=routes      to use Google Routes API    (enable routes.googleapis.com first)
        val routingBackend = localProps.getProperty("ROUTING_BACKEND", "directions").trim().lowercase()
        val useRoutesApi = routingBackend == "routes"
        buildConfigField("boolean", "USE_ROUTES_API", "$useRoutesApi")
        buildConfigField("String", "ROUTING_BACKEND", "\"$routingBackend\"")
    }

    signingConfigs {
        if (hasReleaseSigning && releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep",
            )
            // Release builds must use the real release keystore (from signing.properties) so
            // updates share one signature. Never silently fall back to the public debug keystore
            // unless explicitly requested via -PallowDebugReleaseSigning=true.
            signingConfig = when {
                hasReleaseSigning -> signingConfigs.getByName("release")
                project.findProperty("allowDebugReleaseSigning")?.toString()?.toBoolean() == true ->
                    signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.places)
    // implementation(libs.maplibre)
    // implementation(libs.maplibre.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}