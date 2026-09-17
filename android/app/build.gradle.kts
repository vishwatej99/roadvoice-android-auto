plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val uploadStoreFile = providers.environmentVariable("ROADVOICE_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("ROADVOICE_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("ROADVOICE_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("ROADVOICE_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
    .all { !it.isNullOrBlank() }

// Each self-hosted Play test needs its own globally unique application ID.
// Keep the source namespace stable so forks do not need to rename Kotlin packages.
val roadvoiceApplicationId = providers.gradleProperty("roadvoiceApplicationId")
    .orElse("dev.roadvoice").get()

android {
    namespace = "dev.roadvoice"
    compileSdk = 36
    defaultConfig {
        applicationId = roadvoiceApplicationId
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.4.1"
        testInstrumentationRunner = "dev.roadvoice.diagnostics.SavedConnectionDiagnosticInstrumentation"
        buildConfigField("boolean", "ALLOW_USB", "false")
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = file(checkNotNull(uploadStoreFile))
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_USB", "true")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("usbTestRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ALLOW_USB", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// A release must never silently fall back to a debug key or an unsigned artifact.
tasks.matching { it.name == "preReleaseBuild" || it.name == "preUsbTestReleaseBuild" }.configureEach {
    doFirst {
        check(hasUploadSigning) {
            "Release signing is not configured. Set all four ROADVOICE_UPLOAD_* environment variables."
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-telecom:1.0.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:137.7151.05")
}
