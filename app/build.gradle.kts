plugins {
    id("com.android.application")
}

android {
    namespace = "com.repl.bubbledrawer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.repl.bubbledrawer"
        minSdk = 33
        targetSdk = 35
        // versionCode from the git commit count, like the MiuiBackGestureHook reference
        // (its AGENTS.md: "versionCode is derived from the Git commit count"): with
        // autoHotReload the APK is reinstalled constantly, and a changing versionCode is what
        // lets LSPosed's module list (and the user) tell one build from the next. Falls back
        // to 1 outside a git checkout so a source tarball still builds.
        versionCode = runCatching {
            providers.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText.get().trim().toInt()
        }.getOrDefault(1)
        versionName = "0.1"
    }
    buildTypes {
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("de.hdodenhof:circleimageview:3.1.0")
    // hidden-API exemption (user-provided; Maven Central artifact, no runtime deps —
    // pom verified 2026-10-08). Used for android.view.InputMonitor reflection in
    // PilferGuard; the class is @hide and HyperOS may enforce restrictions even for
    // the system-UID SystemUI process. Same pin as E:\workspace\ios16\hypermirror
    // (gradle/libs.versions.toml:92-94, v6.1).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // libxposed API 102 — versions pinned to the FlymeFreeform reference (gradle/libs.versions.toml:7-8)
    compileOnly("io.github.libxposed:api:102.0.0")
    // @hide platform input classes (InputMonitor / InputEventReceiver / InputChannel /
    // InputManagerGlobal) for the real gesture-monitor transport. compileOnly: the stubs
    // are never packaged, the boot classpath provides the real classes at runtime.
    compileOnly(project(":hiddenapi"))
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
