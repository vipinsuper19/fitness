plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.vipin.fitness.core.testing"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(projects.core.data)
    api(projects.core.datastore)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
