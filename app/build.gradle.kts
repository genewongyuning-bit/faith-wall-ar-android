plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.faithprinter.wallar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.faithprinter.wallar"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui:1.10.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.1")
    implementation("io.github.sceneview:arsceneview:4.25.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.10.1")
}
