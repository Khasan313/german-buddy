plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.germanbuddy"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.germanbuddy"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
