plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // For Jetpack Compose
}

android {
    namespace = "com.sikder.spentranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sikder.spentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Change to true for production releases with ProGuard
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true // For Jetpack Compose
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // Check compatibility with your Kotlin & Compose versions
        // Or use libs.versions.compose.compiler.get() if defined in version catalog
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// In your app/build.gradle.kts filea

dependencies {
    implementation("androidx.core:core-ktx:1.12.0") // Or latest
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    // Core Android & UI
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0") // Or latest
    implementation("androidx.appcompat:appcompat:1.6.1") // Or latest
    implementation("com.google.android.material:material:1.11.0") // Or latest
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // As you specified

    // Jetpack Compose BOM and Dependencies
    // Using the BOM helps manage versions of different Compose libraries
    val composeBomVersion = "2024.06.00" // Check for the latest BOM version
    implementation(platform("androidx.compose:compose-bom:$composeBomVersion"))
    androidTestImplementation(platform("androidx.compose:compose-bom:$composeBomVersion")) // For UI tests

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3") // For Material Design 3
    // If you were using Material 2: implementation("androidx.compose.material:material")

    // Integration with activities for Compose
    implementation("androidx.activity:activity-compose:1.9.0") // Or latest

    // Integration with ViewModels for Compose (optional, but common)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0") // Or latest

    // LocalBroadcastManager (if you choose to use it for MainActivity <-> Service communication)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // ML Kit On-device Translation
    implementation("com.google.mlkit:translate:17.0.2") // Or latest stable version

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // For Compose UI tests (optional)
    // androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Debug tools for Compose (optional)
    // debugImplementation("androidx.compose.ui:ui-tooling")
    // debugImplementation("androidx.compose.ui:ui-test-manifest")
}