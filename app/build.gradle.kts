plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 署名の鍵は GitHub の Secrets から受け取る（リポジトリには置かない）
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.chill.run"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chill.run"
        minSdk = 34
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.5.$buildNumber"
    }

    signingConfigs {
        if (!keystorePath.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = signingPassword
                keyAlias = "chillrun"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // データベースの設計図を書き出す（引っ越しのテストで使う）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.0")
    implementation("androidx.room:room-ktx:2.8.0")
    ksp("androidx.room:room-compiler:2.8.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:guava:33.4.0-android")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
