plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Compose compiler is a Kotlin compiler plugin; with AGP 9's built-in Kotlin the
    // ONLY thing we apply is this (same wiring as the MiuiBackGestureHook reference,
    // which is a miuix app on the same AGP/Kotlin line). Never apply
    // org.jetbrains.kotlin.android — it conflicts with built-in Kotlin.
    alias(libs.plugins.kotlin.compose) apply false
}
