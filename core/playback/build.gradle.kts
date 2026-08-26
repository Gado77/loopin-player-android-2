plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loopin.player2.core.playback"
    compileSdk = 36

    defaultConfig { minSdk = 21 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:content"))
    //noinspection GradleDependency -- 1.9+ requires API 23; MXQ compatibility requires API 21.
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    //noinspection GradleDependency -- 1.9+ requires API 23; MXQ compatibility requires API 21.
    implementation("androidx.media3:media3-ui:1.8.1")
    testImplementation(kotlin("test"))
}
