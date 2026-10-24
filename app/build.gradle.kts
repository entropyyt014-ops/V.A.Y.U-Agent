plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vayu.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vayu.agent"
        minSdk = 30
        targetSdk = 34
        versionCode = 15
        versionName = "11.1-controller"


    }

    signingConfigs {
        // Stable test certificate across local and CI builds. Never use for production.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Codemagic will inject CM_KEYSTORE_PATH, CM_KEY_PASSWORD, CM_KEY_ALIAS, CM_KEY_PASSWORD
            val ksPath = System.getenv("CM_KEYSTORE_PATH")
            val ksPassword = System.getenv("CM_KEYSTORE_PASSWORD") ?: "android"
            val ksAlias = System.getenv("CM_KEY_ALIAS") ?: "androiddebugkey"
            val ksKeyPassword = System.getenv("CM_KEY_PASSWORD") ?: "android"
            storeFile = if (!ksPath.isNullOrBlank()) file(ksPath) else file("debug.keystore")
            storePassword = ksPassword
            keyAlias = ksAlias
            keyPassword = ksKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
