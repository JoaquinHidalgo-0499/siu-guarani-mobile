plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.gobley.cargo") version "0.2.0"
    id("dev.gobley.uniffi") version "0.2.0"
}

cargo {
    packageDirectory = layout.projectDirectory.dir("../../rust-core")
}

android {
    namespace = "ar.edu.unju.fi.guarani"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.edu.unju.fi.guarani"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Biometría nativa
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")

    // Almacenamiento seguro moderno
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.crypto.tink:tink-android:1.15.0")

    // Tareas periódicas
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // JNA para UniFFI
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
