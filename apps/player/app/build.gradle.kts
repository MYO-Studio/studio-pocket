// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release key. Without it the release APK is signed with the debug key, which
// is fine for a priv-app install (priv-app does not require the platform key) but must
// stay the same key across updates of the same package.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.studiopocket.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.studiopocket.player"
        minSdk = 19
        // targetSdk 19 keeps KitKat behaviour (no runtime permissions, no scoped storage).
        targetSdk = 19
        versionCode = 12
        versionName = "0.8.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV1Signing = true   // required: Android 4.4 only understands v1 (JAR) signatures
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = true
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

// Runtime dependencies (what the APK carries): only the Kotlin standard library, added by the Kotlin
// plugin, and org.jetbrains:annotations with it. Each one is listed, with its version and licence, in
// src/main/assets/licenses/THIRD-PARTY.txt and in scripts/check-apk-notices.sh's RUNTIME_DEPENDENCIES;
// all three change together (the release workflow runs the check on the signed APK).
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; the platform one in android.jar is a stub.
    testImplementation("org.json:json:20240303")
}

// A release build must be signed with the release key. The fallback above exists for local
// development; a release build without the key fails here unless STUDIO_POCKET_ALLOW_DEBUG_SIGNING=1.
tasks.register("requireReleaseSigning") {
    doLast {
        if (android.signingConfigs.findByName("release") == null && System.getenv("STUDIO_POCKET_ALLOW_DEBUG_SIGNING") != "1") {
            throw GradleException("release build needs keystore.properties (see scripts/make-dev-keystore.sh); set STUDIO_POCKET_ALLOW_DEBUG_SIGNING=1 to build a debug-signed release locally")
        }
    }
}
// Hooked on the tasks that write a release APK or bundle (packageRelease also serves installRelease;
// packageReleaseBundle and signReleaseBundle serve bundleRelease), so the guard fails before any
// release artefact is written, whichever task asked for it. Not on preReleaseBuild: the unit test
// and lint tasks (testReleaseUnitTest, lintRelease) run that too, and they need no release key.
val releaseOutputTasks = setOf("assembleRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle", "bundleRelease")
tasks.matching { it.name in releaseOutputTasks }.configureEach { dependsOn("requireReleaseSigning") }
