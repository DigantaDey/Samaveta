import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.samaveta.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.samaveta.android"
        // minSdk 28: on-device inference runtimes, Keystore-backed crypto and the thermal APIs
        // SAMAVETA relies on for device health are all reliable from here. Below this the product
        // cannot honestly promise local AI.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-SANTOOR-rc1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Only 64-bit devices can host the model runtimes; 32-bit is not a supported target.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Release signing is supplied out-of-band through environment variables so no keystore
        // material (and no password) ever enters the repository.
        //
        // The `if` that used to wrap these assignments is gone: `storeFile` is nullable and the
        // block is only *evaluated* for a release build, so an unset variable now fails loudly in a
        // release build instead of silently producing an unsigned artifact. A debug build never
        // looks at this config.
        create("release") {
            storeFile = System.getenv("SAMAVETA_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("SAMAVETA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SAMAVETA_KEY_ALIAS")
            keyPassword = System.getenv("SAMAVETA_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // Debug builds may use the LAYA mock; release builds must not (enforced below).
            buildConfigField("boolean", "ALLOW_LAYA_MOCK", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_LAYA_MOCK", "false")
            // Always attach the release signing config: if the material is absent, storeFile is
            // null and Gradle refuses to sign, rather than emitting an `-unsigned.apk` that is not
            // installable and calling the job a release.
            //
            // The missing-material check is a task, not a `throw` here. This block is executed for
            // *every* invocation, including `:core:test` and `lintDebug`, so a configuration-time
            // throw failed the debug jobs too — which is exactly what happened on the first run of
            // this file.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        // Warnings are errors. This is deliberate: the 35 findings that had accumulated were two
        // real bugs (unreferenced-but-believed-live resources) and three policy violations, and none
        // of them would have been looked at as a warning.
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // Dependency *freshness* is a maintenance decision made deliberately in
            // docs/DEPENDENCIES.md and gradle/libs.versions.toml, not something a build should fail
            // over. A vulnerable version is still reported by the separate Dependabot path.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            // OldTargetApi: targetSdk is 35, the platform this build has actually been exercised
            // against. Raising it to satisfy lint would assert an untested target, which is the
            // exact claim this project refuses to make (docs/KNOWN_LIMITATIONS.md).
            "OldTargetApi",
            // ChromeOsAbiSupport wants an x86_64 ABI for ChromeOS. The app ships arm64-v8a only
            // because the on-device model runtimes have no validated x86_64 backend; shipping an
            // ABI that cannot run inference would be a false promise of support.
            "ChromeOsAbiSupport",
        )
    }
}

// Release builds must carry signing material. Checked as a task (execution time), so it can only
// fail a job that actually builds a release; nothing about debug, lint or unit tests touches it.
tasks.matching {
    // `validateSigningRelease` is the task AGP would otherwise fail with its own terser message.
    it.name in setOf("assembleRelease", "bundleRelease", "validateSigningRelease")
}.configureEach {
    doFirst {
        val missing = listOf(
            "SAMAVETA_KEYSTORE_PATH",
            "SAMAVETA_KEYSTORE_PASSWORD",
            "SAMAVETA_KEY_ALIAS",
            "SAMAVETA_KEY_PASSWORD",
        ).filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing material is missing: ${missing.joinToString()}. An unsigned APK " +
                    "is not a release — see README.md#release-signing, or build the debug variant.",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.webkit)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.mockk.android)
}
