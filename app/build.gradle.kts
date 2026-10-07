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
        versionCode = 1
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
    // libxposed API 102 — versions pinned to the FlymeFreeform reference (gradle/libs.versions.toml:7-8)
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
