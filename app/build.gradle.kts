import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String = ""): String =
    (project.findProperty(key) as String?) ?: localProps.getProperty(key) ?: default

val apiBaseUrl = prop("apiBaseUrl", "https://lanflash-api.example.com")
val licenseSecret = prop("licenseSecret", "")
val releaseStoreFile = prop("releaseStoreFile", "")
val releaseStorePassword = prop("releaseStorePassword", "")
val releaseKeyAlias = prop("releaseKeyAlias", "")
val releaseKeyPassword = prop("releaseKeyPassword", "")

android {
    namespace = "com.lanflash.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lanflash.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "LICENSE_SECRET", "\"$licenseSecret\"")
        buildConfigField("String", "CHANNEL", "\"github\"")
    }

    signingConfigs {
        if (releaseStoreFile.isNotBlank()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile.isNotBlank()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same applicationId as release so the device fingerprint used by
            // activation stays stable across builds.
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
