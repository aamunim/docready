import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The one id of the app: on the phone, on Google Play, and the code's own package
val appId = "com.docready.aamunim"

// Release signing comes from the environment (CI writes the key to a temp file); none = unsigned
fun hasSigningVars(): Boolean =
    listOf("SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD", "SIGNING_STORE_FILE", "SIGNING_STORE_PASSWORD")
        .all { !providers.environmentVariable(it).orNull.isNullOrEmpty() }

android {
    namespace = appId
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = appId
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        setProperty("archivesBaseName", "docready-$versionName")
    }

    signingConfigs {
        if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: no release key: the release build is signed with the debug key (installable for testing, not for Google Play).")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // without the release key (forks, secrets not added yet) the debug key signs it:
            // Android refuses to install an unsigned APK
            signingConfig = signingConfigs.getByName(if (hasSigningVars()) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Google ML Kit through Google Play services: the models are delivered by Play services,
    // so the app stays small, and the document scanner needs no camera permission
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.subject.segmentation)
    // OCR, English (Latin script): the bundled model, so it works offline from the first start
    implementation(libs.mlkit.text.recognition)

    // plain JVM tests of the pure-Kotlin parts (./gradlew test); nothing of it goes in the app
    testImplementation(libs.junit)
}
