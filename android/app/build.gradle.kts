plugins {
    id("com.android.application") version "9.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "io.github.gengar93.tally"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.gengar93.tally"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // The Play upload key lives outside the repo. Its path and passwords come from Gradle properties,
    // normally ~/.gradle/gradle.properties; without them, release builds fall back to the debug key.
    val uploadStoreFile = providers.gradleProperty("TALLY_UPLOAD_STORE_FILE").orNull
    val hasUploadKey = uploadStoreFile != null && listOf("TALLY_UPLOAD_STORE_PASSWORD", "TALLY_UPLOAD_KEY_ALIAS", "TALLY_UPLOAD_KEY_PASSWORD")
        .all { providers.gradleProperty(it).isPresent }

    signingConfigs {
        if (hasUploadKey) {
            create("release") {
                storeFile = file(uploadStoreFile!!)
                storePassword = providers.gradleProperty("TALLY_UPLOAD_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("TALLY_UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("TALLY_UPLOAD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking for a smaller app. The bundle carries the R8 mapping for Play's crash reports.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasUploadKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("Tally: TALLY_UPLOAD_* properties not set; signing the release build with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    // Glance 1.2.0 pulls in WorkManager 2.7.1 (with Room 2.2.5), whose R8 rules break under full mode; pin a current one.
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
