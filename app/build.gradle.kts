plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.family.audiobooks"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.family.audiobooks"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("publicRelease") {
            val signingPath = System.getenv("SPINEPLAY_KEYSTORE")
            if (!signingPath.isNullOrBlank()) {
                storeFile = file(signingPath)
                storePassword = System.getenv("SPINEPLAY_STORE_PASSWORD")
                keyAlias = System.getenv("SPINEPLAY_KEY_ALIAS")
                keyPassword = System.getenv("SPINEPLAY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("publicRelease") }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        require(listOf("SPINEPLAY_KEYSTORE", "SPINEPLAY_STORE_PASSWORD", "SPINEPLAY_KEY_ALIAS", "SPINEPLAY_KEY_PASSWORD")
            .all { !System.getenv(it).isNullOrBlank() }) { "Release signing is required. See docs/development.md; keep signing keys outside this repository." }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.42.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.42.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("roborazzi.test.record", "true")
}
