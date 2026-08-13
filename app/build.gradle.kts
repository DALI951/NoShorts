import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dali951.noshorts"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dali951.noshorts"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.2"
    }

    signingConfigs {
        create("release") {
            val props = rootProject.file("keystore.properties")
            if (props.exists()) {
                val kp = Properties()
                kp.load(props.inputStream())
                storeFile = file(kp["storeFile"] as String)
                storePassword = kp["storePassword"] as String
                keyAlias = kp["keyAlias"] as String
                keyPassword = kp["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same signature everywhere (local + CI) so updates always install.
            // Falls back to the debug key when keystore.properties is missing.
            val props = rootProject.file("keystore.properties")
            signingConfig = if (props.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
