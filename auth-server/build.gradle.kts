plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); application }
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass = "com.wusui.auth.MainKt" }
dependencies {
    implementation(libs.ktor.server)
    implementation(libs.ktor.server.json)
    implementation(libs.ktor.server.status)
    implementation(libs.ktor.json)
    implementation(libs.serialization)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.ktor.client.json)
    testImplementation(libs.junit)
}
