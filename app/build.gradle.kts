import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.remotely"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.remotely"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    // Release signing comes from keystore.properties (git-ignored, created by tools/make-keystore.sh).
    val ksProps = rootProject.file("keystore.properties")
    val hasKeystore = ksProps.exists()
    signingConfigs {
        if (hasKeystore) {
            val p = Properties().apply { ksProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably smoother on TV boxes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
