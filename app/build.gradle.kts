plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aykkdk.shop"
    compileSdk = 34
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.aykkdk.shop"
        minSdk = 24
        targetSdk = 34
        versionCode = 29
        versionName = "1.28"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures { compose = true }

    signingConfigs {
        providers.gradleProperty("debugKeystorePath").orNull?.takeIf { it.isNotBlank() }?.let { path ->
            getByName("debug").storeFile = file(path)
        }
        create("release") {
            val keyPath = providers.gradleProperty("releaseKeystorePath").orNull?.takeIf { it.isNotBlank() }
                ?: System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            if (keyPath != null) {
                storeFile = file(keyPath)
                storePassword = providers.gradleProperty("releaseKeystorePassword").orNull?.takeIf { it.isNotBlank() }
                    ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = providers.gradleProperty("releaseKeyAlias").orNull?.takeIf { it.isNotBlank() }
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = providers.gradleProperty("releaseKeyPassword").orNull?.takeIf { it.isNotBlank() }
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            // A release must not silently ship unshrunk resources.
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSign = signingConfigs.getByName("release")
            signingConfig = if (releaseSign.storeFile?.exists() == true) releaseSign else signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
