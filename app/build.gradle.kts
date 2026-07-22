plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.gifapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gifapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.1"

        buildConfigField("String", "WATERMARK_SIGNATURE", "\"最终之刃\"")
        buildConfigField("String", "WATERMARK_HOUSE", "\"万剑之主，千剑之家\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Custom signing config with our identity
    signingConfigs {
        create("release") {
            storeFile = file("keystore/final_blade.jks")
            storePassword = "finalblade"
            keyAlias = "final_blade"
            keyPassword = "finalblade"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // FFmpeg GIF encoding (min-gpl: lighter, only GIF-capable components)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.7")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
