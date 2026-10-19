import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName) ?: keystoreProperties.getProperty(propertyName)

val releaseStoreFile = System.getenv("KEYSTORE_FILE")
    ?: keystoreProperties.getProperty("storeFile")
    ?: "release-keystore.jks"

val githubRepo = providers.gradleProperty("GITHUB_REPO").getOrElse("nairdam88/jbd-bms-karoo")

android {
    namespace = "com.jbdbms.karoo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jbdbms.karoo"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("APP_VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("APP_VERSION_NAME").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        manifestPlaceholders["karooManifestUrl"] =
            "https://github.com/$githubRepo/releases/latest/download/manifest.json"
    }

    signingConfigs {
        create("release") {
            storeFile = if (File(releaseStoreFile).isAbsolute) {
                File(releaseStoreFile)
            } else {
                rootProject.file(releaseStoreFile)
            }
            storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingValue("KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * Writes the Karoo App Manifest that Karoo System reads from
 * releases/latest/download/manifest.json to offer in-place updates.
 */
tasks.register("generateKarooManifest") {
    val templateFile = rootProject.file("manifest-karoo-template.json")
    val outputFile = layout.buildDirectory.file("outputs/apk/release/manifest.json")

    inputs.file(templateFile)
    outputs.file(outputFile)

    doLast {
        check(templateFile.exists()) { "manifest-karoo-template.json not found" }
        val versionName = providers.gradleProperty("APP_VERSION_NAME").get()
        val versionCode = providers.gradleProperty("APP_VERSION_CODE").get()
        val notes = System.getenv("RELEASE_NOTES")
            ?: providers.gradleProperty("releaseNotes").getOrElse("Bug fixes and improvements.")
        val apkUrl = "https://github.com/$githubRepo/releases/latest/download/app-release.apk"

        val json = templateFile.readText()
            .replace("__APK_URL__", jsonEscape(apkUrl))
            .replace("__VERSION_NAME__", jsonEscape(versionName))
            .replace("__VERSION_CODE__", versionCode)
            .replace("__RELEASE_NOTES__", jsonEscape(notes))

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(json)
        println("Karoo manifest generated: ${target.absolutePath}")
    }
}

fun jsonEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "")

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("generateKarooManifest")
    }
}

dependencies {
    implementation(project(":karoo-ext"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val compose = "1.7.6"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.ui:ui-tooling-preview:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling:$compose")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
