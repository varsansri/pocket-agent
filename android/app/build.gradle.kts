import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives OUTSIDE the repo: ~/.pocket-agent-keys/keystore.properties
val keyProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".pocket-agent-keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Personal build only (gradlew -Ppersonal): the owner's Gemini key is baked in from a file
// OUTSIDE the repo. Public builds (GitHub) always have an empty key.
val builtInKey: String = if (project.hasProperty("personal")) {
    File(System.getProperty("user.home"), ".pocket-agent-keys/personal-gemini-key.txt").readText().trim()
} else ""

android {
    namespace = "com.hanubees.pocketagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hanubees.pocketagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.1"
        buildConfigField("String", "BUILT_IN_KEY", "\"$builtInKey\"")
        if (builtInKey.isNotEmpty()) versionNameSuffix = "-personal"
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = File(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keyProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
