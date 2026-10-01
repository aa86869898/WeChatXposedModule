import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
}

val moduleBuildTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
}.format(Date())

// 签名口令从 local.properties 或同名环境变量读取，源码内不再保留明文。
// local.properties 已被根 .gitignore 忽略，可用键：
//   LESHAO_STORE_PASSWORD / LESHAO_KEY_PASSWORD / LESHAO_KEY_ALIAS
val signingProps: Properties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

fun signingSecret(name: String): String =
    System.getenv(name)
        ?: signingProps.getProperty(name)
        ?: error("缺少签名口令属性 $name：请写入 local.properties 或设置同名环境变量")

fun signingValue(name: String, fallback: String): String =
    System.getenv(name) ?: signingProps.getProperty(name) ?: fallback

android {
    namespace = "com.leshao.v3"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.leshao.v3"
        minSdk = 24
        targetSdk = 36
        versionCode = 30087
        versionName = "3.0.87"
        buildConfigField("String", "BUILD_TIME", "\"$moduleBuildTime\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = signingSecret("LESHAO_STORE_PASSWORD")
            keyAlias = signingValue("LESHAO_KEY_ALIAS", "leshao")
            keyPassword = signingSecret("LESHAO_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "LeShaoWeChat-v$versionCode.apk"
        }
    }
}

dependencies {
    compileOnly(files("libs/xposed-api-82.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.xxinPro:SilkDecoder:1.0")
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("com.tencent:mmkv:1.3.5")
}
