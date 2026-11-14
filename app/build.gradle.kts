plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Phase 1 starts at 0.1.0; increment the patch version for every Phase 1 build.
val appVersion = "0.1.5"
val buildDateUtc = "2026-11-13"

android {
    namespace = "com.deangi.androidpdp11"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deangi.android8088"
        minSdk = 26
        targetSdk = 35
        versionCode = 105
        versionName = appVersion

        buildConfigField("String", "APP_VERSION", "\"$appVersion\"")
        buildConfigField("String", "BUILD_DATE_UTC", "\"$buildDateUtc\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
