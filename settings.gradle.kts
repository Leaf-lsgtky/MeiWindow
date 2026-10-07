pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "bubbledrawer"
include(":app")
// Compile-only stubs for @hide platform classes (never packaged; see
// hiddenapi/README.md). Same shape as MiuiBackGestureHook's :hidden-api module.
include(":hiddenapi")
