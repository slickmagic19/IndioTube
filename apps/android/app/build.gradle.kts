plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.indiotube.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.indiotube.app"
        minSdk = 26
        targetSdk = 34
versionCode = 112
versionName = "0.1.12"
    }

    // The keystore and its passwords are never committed. Provide them in
    // ~/.gradle/gradle.properties or as -P properties to produce a signed
    // release; without them `assembleRelease` builds an unsigned APK so
    // anyone can compile the project from a fresh clone.
    val releaseKeystore = file("keystore/indiotube-release.keystore")
    val storePass = project.findProperty("indiotubeStorePassword") as String?
    val keyPass = project.findProperty("indiotubeKeyPassword") as String?
    val hasReleaseSigning = releaseKeystore.exists() && !storePass.isNullOrBlank() && !keyPass.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePass
                keyAlias = "indiotube"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off on purpose: the app leans on reflection and dynamic
            // JSON parsing, and a stripped release that crashes in the field is
            // worse than a slightly larger APK.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/LICENSE*"
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.media3:media3-test-utils:1.4.1")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.4.1")
}
