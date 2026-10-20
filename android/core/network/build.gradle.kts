plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vipin.fitness.core.network"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
        debug {
            // 10.0.2.2 is the dev machine as seen from the emulator (cleartext allowed in debug only).
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/api/v1/\"")
        }
        release {
            // HTTPS only. T17 passes the real host with -Pfitness.releaseApiBaseUrl=https://...;
            // until then the placeholder makes NetworkModule fail loudly at startup.
            val url = providers.gradleProperty("fitness.releaseApiBaseUrl")
                .getOrElse("https://unset.invalid/api/v1/")
            require(url.startsWith("https://")) { "fitness.releaseApiBaseUrl must be https, was $url" }
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
        }
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.kotlinx.serialization.json)
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
