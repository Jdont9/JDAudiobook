plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: the key is NEVER in the repository. It is read from environment variables (CI)
// or from ~/.gradle/gradle.properties (local build). See RELEASING.md.
fun secret(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: (findProperty(prop) as String?)?.takeIf { it.isNotBlank() }

val keystorePath = secret("KEYSTORE_FILE", "jd.keystore.file")

android {
    namespace = "fr.jd.audiobooks"
    compileSdk = 34
    defaultConfig {
        applicationId = "fr.jd.audiobooks"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = secret("KEYSTORE_PASSWORD", "jd.keystore.password")
                keyAlias = secret("KEY_ALIAS", "jd.key.alias")
                keyPassword = secret("KEY_PASSWORD", "jd.key.password")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // With no key provided, the release APK is left unsigned (not installable) rather than signed with a debug key.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
        }
    }
    lint { abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.google.guava:guava:33.7.2-android")
    testImplementation("junit:junit:4.13.2")
}
