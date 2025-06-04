plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sikder.spentranslator"
    compileSdk = 34 // Or your current compileSdk

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // Or libs.versions.compose.compiler.get() if defined in version catalog
    }
    defaultConfig {
        applicationId = "com.sikder.spentranslator"
        minSdk = 26 // <--- CHANGE THIS TO 26 (or higher if desired)
        targetSdk = 34 // Or your current targetSdk
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled =true

        // ...
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // ...
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8" // <--- This tells Kotlin to target JVM 1.8
    }
}
// In your app/build.gradle.kts file

dependencies {
    implementation("androidx.core:core-ktx:1.9.0") // Example, you might have this already
//    implementation("androidx.appcompat:appcompat:1.6.1") // Example, you might have this already
    implementation("com.google.mlkit:translate:17.0.2")
    // Add this line for Material Components
    implementation("com.google.android.material:material:1.12.0") // Use the latest stable version
    implementation("androidx.multidex:multidex:2.0.1")
    // ... other dependencies
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Example
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Example

    // Add Jetpack Compose BOM and Dependencies
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00") // Use the latest stable BOM version
    implementation(composeBom)
    androidTestImplementation(composeBom) // For UI tests

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview") // For @Preview
    implementation("androidx.compose.material3:material3") // For Material Design 3 components
    // Or if you are using Material 2: implementation("androidx.compose.material:material")

    // Integration with activities
    implementation("androidx.activity:activity-compose:1.9.0") // Use latest stable version

    // Integration with ViewModels (optional, but common)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0") // Use latest stable version

    // Other existing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

}