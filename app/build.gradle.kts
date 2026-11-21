plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cn.darknight.miuiaodmod"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.darknight.miuiaodmod"
        minSdk = 35
        targetSdk = 37
        versionCode = 18
        versionName = "0.8.1"
    }

    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = keystorePassword
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: keystorePassword
        }
    }

    buildTypes {
        release {
            // CI 注入 KEYSTORE_PASSWORD 时使用正式签名；本地无密码时回退 debug 签名（仅测试用）。
            signingConfig = if (keystorePassword != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            merges += listOf("META-INF/xposed/*")
            excludes += "**"
        }
        dex {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
