plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.vipin.fitness.feature.onboarding"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        // Placeholders until the real pages exist; override with -Pfitness.privacyUrl=... etc.
        fun prop(name: String, default: String) = providers.gradleProperty(name).getOrElse(default)
        buildConfigField("String", "PRIVACY_URL", "\"${prop("fitness.privacyUrl", "https://example.com/fitness/privacy")}\"")
        buildConfigField("String", "TERMS_URL", "\"${prop("fitness.termsUrl", "https://example.com/fitness/terms")}\"")
        buildConfigField("String", "HELP_URL", "\"${prop("fitness.helpUrl", "https://example.com/fitness/help")}\"")
        buildConfigField("String", "FEEDBACK_EMAIL", "\"${prop("fitness.feedbackEmail", "feedback@example.com")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.common)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Release builds must not ship the example.com placeholders (debug builds may).
val checkReleaseLegalUrls by tasks.registering {
    val values = listOf("fitness.privacyUrl", "fitness.termsUrl", "fitness.helpUrl", "fitness.feedbackEmail")
        .associateWith { providers.gradleProperty(it).orNull }
    doLast {
        val missing = values.filterValues { it == null || it.contains("example.com") }.keys
        check(missing.isEmpty()) { "Release build needs real legal/help links; set -P${missing.joinToString(" -P") { "$it=..." }}" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseLegalUrls) }
