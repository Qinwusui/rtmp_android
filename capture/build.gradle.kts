plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.wusui.rtmpcapture.capture"
    compileSdk { version = release(37) }
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    lint { abortOnError = true }
}
dependencies {
    implementation(libs.coroutines)
    implementation(libs.rtmp)
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
}
