plugins {
    id("com.android.application")
}

android {
    namespace = "com.leshao.v3"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.leshao.v3"
        minSdk = 24
        targetSdk = 35
        versionCode = 372
        versionName = "3.6.6-v172"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("LESHAO_STORE_PASSWORD") ?: "leshao2024"
            keyAlias = System.getenv("LESHAO_KEY_ALIAS") ?: "leshao"
            keyPassword = System.getenv("LESHAO_KEY_PASSWORD") ?: "leshao2024"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(files("libs/xposed-api-82.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.xxinPro:SilkDecoder:1.0")
}
