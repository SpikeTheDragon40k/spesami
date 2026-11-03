import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties in the project root (never committed), or SPESAMI_* environment
// variables. Without either, release builds fall back to the debug key so they still install for testing.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
val releaseKeystore = signingValue("storeFile", "SPESAMI_KEYSTORE_FILE")
    ?.let { file(it.replaceFirst("~", System.getProperty("user.home"))) }
    ?.takeIf { it.exists() }

android {
    namespace = "it.spesami.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.spesami.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "SPESAMI_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SPESAMI_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SPESAMI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric uses the merged manifest (Compose UI tests)
        // The full activity leaves Compose/looper state behind in Robolectric; a fresh JVM per test class isolates it.
        unitTests.all { it.forkEvery = 1 }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.play.services.auth) // Google Drive authorization (AuthorizationClient); tokens stay in Play services
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest) // test host activity, debug builds only
}
