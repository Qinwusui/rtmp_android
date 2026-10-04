plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.wusui.rtmpcapture"
    ndkVersion = "26.1.10909125"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.wusui.rtmpcapture"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Review/test APK only. Supply a private production signing config for distribution.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":capture"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.core)
    implementation(libs.koin)
    implementation(libs.datastore)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.coroutines)
    implementation(libs.serialization)
    implementation(libs.ktor.client)
    implementation(libs.ktor.client.json)
    implementation(libs.ktor.json)
    implementation(libs.media3.player)
    implementation(libs.media3.ui)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    androidTestImplementation(libs.android.test)
    androidTestImplementation(libs.test.runner)
}
