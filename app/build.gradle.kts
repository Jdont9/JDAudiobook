plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signature de la version release : la clé n'est JAMAIS dans le dépôt. Elle est lue depuis des variables
// d'environnement (CI) ou depuis ~/.gradle/gradle.properties (build local). Voir RELEASING.md.
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
        versionCode = 1
        versionName = "1.0.0"
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
            // Sans clé fournie, l'APK release sort non signé (donc non installable) plutôt que signé avec une clé de debug.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
        }
    }
    lint { abortOnError = false }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.google.guava:guava:33.3.0-android")
}
