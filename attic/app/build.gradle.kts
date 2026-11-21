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

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
        }
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":hook"))

    testImplementation(libs.junit)
}
