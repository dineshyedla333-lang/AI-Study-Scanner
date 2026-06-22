plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.aistudyscanner.agent"
    compileSdk = 35

    val apiBaseUrl =
        (project.findProperty("API_BASE_URL")?.toString() ?: "http://10.0.2.2:8000").trimEnd('/')
    val sentryDsn = project.findProperty("SENTRY_DSN")?.toString() ?: ""
    val releaseVersionCode = (project.findProperty("RELEASE_VERSION_CODE")?.toString()
        ?: "1").toInt()
    val releaseVersionName = project.findProperty("RELEASE_VERSION_NAME")?.toString() ?: "1.0.0"

    defaultConfig {
        applicationId = "com.aistudyscanner.agent"
        minSdk = 24
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Runtime config. Override via gradle.properties, local.properties, or CI.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("RELEASE_STORE_FILE")?.toString()
            val storePassword = project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
            val keyAlias = project.findProperty("RELEASE_KEY_ALIAS")?.toString()
            val keyPassword = project.findProperty("RELEASE_KEY_PASSWORD")?.toString()

            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
            }
            if (!storePassword.isNullOrBlank()) {
                this.storePassword = storePassword
            }
            if (!keyAlias.isNullOrBlank()) {
                this.keyAlias = keyAlias
            }
            if (!keyPassword.isNullOrBlank()) {
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "APP_ENV", "\"debug\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "APP_ENV", "\"release\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.navigation.compose)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.concurrent.futures)
    implementation("com.google.guava:guava:33.0.0-android")

    // ML Kit OCR (optional but recommended for extracting question text from scan)
    implementation(libs.mlkit.text.recognition)

    // Networking (Retrofit + OkHttp) + Coroutines
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Firebase (for usage tracking / limits)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Sentry (error monitoring)
    implementation(libs.sentry.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
