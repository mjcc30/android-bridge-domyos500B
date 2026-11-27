plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.domyos500b.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.domyos500b.bridge"
        // minSdk 33 : les nouvelles signatures GATT (paramètre `value`) exigent l'API 33+.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Clé debug STABLE embarquée (keystore/debug.keystore.b64 décodé en
        // keystore/debug.keystore, restauré pareil en local et en CI) : sinon chaque
        // build CI aurait une signature différente et la mise à jour in-app échouerait
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE -> « App not installed »).
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
