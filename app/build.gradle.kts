plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.repl.bubbledrawer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.repl.bubbledrawer"
        minSdk = 36
        targetSdk = 37
        // versionCode from the git commit count, like the MiuiBackGestureHook reference
        // (its AGENTS.md: "versionCode is derived from the Git commit count"): with
        // autoHotReload the APK gets reinstalled constantly, and a changing versionCode is what
        // lets LSPosed's module list (and the user) tell one build from the next. Falls back
        // to 1 outside a git checkout so a source tarball still builds; `-PversionCode=N`
        // forces a number when rebuilding an uncommitted tree.
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
            ?: runCatching {
                providers.exec {
                    commandLine("git", "rev-list", "--count", "HEAD")
                }.standardOutput.asText.get().trim().toInt()
            }.getOrDefault(1)
        versionName = providers.gradleProperty("versionName").orNull ?: "1.1.0"
    }

    buildFeatures {
        compose = true
    }

    val signingKeystoreFile = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }
    val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")

    signingConfigs {
        if (signingKeystoreFile != null && signingKeystoreFile.exists() && !signingStorePassword.isNullOrEmpty()) {
            create("release") {
                storeFile = signingKeystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Xposed module metadata must stay verbatim inside the APK (LSPosed reads it)
    // — same packaging rule as the FlymeFreeform reference (app/build.gradle.kts:42-46).
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // BOM pins the androidx.compose line to what miuix 0.9.3 was built against
    // (its POM: foundation 1.11.1 -> 2026.06.01 BOM gives 1.11.4).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)

    // CircleImageView stays: the fan (bubble/ + xposed/FanHost tile) is still a View
    // overlay, and it is the only remaining user. appcompat/material/recyclerview are
    // gone now that both pages are Compose.
    implementation(libs.circleimageview)

    // hidden-API exemption (user-provided; Maven Central artifact, no runtime deps —
    // pom verified 2026-10-08). Used for android.view.InputMonitor reflection in
    // PilferGuard; the class is @hide and HyperOS may enforce restrictions even for
    // the system-UID SystemUI process. Same pin as E:\workspace\ios16\hypermirror
    // (gradle/libs.versions.toml:92-94, v6.1).
    implementation(libs.hiddenapibypass)
    // libxposed API 102 — versions pinned to the FlymeFreeform reference (gradle/libs.versions.toml:7-8)
    compileOnly(libs.libxposed.api)
    // @hide platform input classes (InputMonitor / InputEventReceiver / InputChannel /
    // InputManagerGlobal) for the real gesture-monitor transport. compileOnly: the stubs
    // are never packaged, the boot classpath provides the real classes at runtime.
    compileOnly(project(":hiddenapi"))
    implementation(libs.libxposed.service)

    testImplementation(libs.junit)
}
