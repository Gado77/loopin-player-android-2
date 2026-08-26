plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loopin.player2"
    buildFeatures { buildConfig = true }
    compileSdk = 36

    defaultConfig {
        applicationId = "com.loopin.player2"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0-phase7.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    implementation(project(":core:model"))
    implementation(project(":core:foundation"))
    implementation(project(":core:playback"))
    implementation(project(":core:media-cache"))
    implementation(project(":core:sync"))
    implementation(project(":core:operations"))
    implementation(project(":core:content"))
}
