import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.GradleException
import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
val hasReleaseSigning = keystorePropertiesFile.exists()
val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias")

if (hasReleaseSigning) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

fun propertyValue(key: String): String? {
    val directValue = keystoreProperties.getProperty(key)?.trim()
    if (!directValue.isNullOrEmpty()) return directValue

    return keystoreProperties.stringPropertyNames()
        .firstOrNull { it.equals(key, ignoreCase = true) }
        ?.let { keystoreProperties.getProperty(it)?.trim() }
        ?.takeIf { it.isNotEmpty() }
}

val missingSigningKeys = if (hasReleaseSigning) {
    requiredSigningKeys.filter { propertyValue(it) == null }
} else {
    emptyList()
}

if (isReleaseTaskRequested && !hasReleaseSigning) {
    throw GradleException(
        "Missing key.properties for release signing. " +
            "Create key.properties in the repo root (or provide it in CI) before running release builds."
    )
}

if (isReleaseTaskRequested && missingSigningKeys.isNotEmpty()) {
    throw GradleException(
        "Missing required key.properties entries: ${missingSigningKeys.joinToString(", ")}. " +
            "Required keys: ${requiredSigningKeys.joinToString(", ")}."
    )
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.beansys.breathing"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.beansys.breathing"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += "IconLauncherShape"
        disable += "MonochromeLauncherIcon"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning && missingSigningKeys.isEmpty()) {
                storeFile = rootProject.file(propertyValue("storeFile")!!)
                storePassword = propertyValue("storePassword")
                keyAlias = propertyValue("keyAlias")
                keyPassword = propertyValue("keyPassword") ?: propertyValue("storePassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning && missingSigningKeys.isEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.ui:ui:1.10.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.2")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.2")
}
