plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.vipin.fitness"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vipin.fitness"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing placeholder (no tester/Play build yet). When needed, uncomment and supply
    // values via env vars or android/local.properties (never commit keystores or passwords):
    // signingConfigs {
    //     create("release") {
    //         val props = java.util.Properties().apply {
    //             rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    //         }
    //         fun secret(key: String) = System.getenv(key) ?: props.getProperty(key)
    //         storeFile = secret("FITNESS_KEYSTORE_PATH")?.let { file(it) }
    //         storePassword = secret("FITNESS_KEYSTORE_PASSWORD")
    //         keyAlias = secret("FITNESS_KEY_ALIAS")
    //         keyPassword = secret("FITNESS_KEY_PASSWORD")
    //     }
    // }

    buildTypes {
        release {
            // signingConfig = signingConfigs.getByName("release")
            optimization { enable = false }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.network)
    implementation(projects.feature.auth)
    implementation(projects.feature.home)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.workout)
    implementation(projects.feature.nutrition)
    implementation(projects.feature.progress)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
