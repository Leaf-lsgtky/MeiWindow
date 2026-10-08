# R8 rules for the Xposed module APK.
#
# Root cause (2026-10-08, "角落触发不了"): minified release stripped the whole module —
# LSPosed loads com.repl.bubbledrawer.xposed.ModuleMain by NAME from java_init.list
# (a resource R8 never reads), so the entry class and every class reachable only through
# the hooks were shrunken away → ClassNotFoundException in SystemUI ("Failed to load
# class com.repl.bubbledrawer.xposed.ModuleMain") → the fan and corner capture died
# while the settings Activity kept working.
#
# Keep the whole module package: the module code is tiny; the APK-size win of this
# build comes from shrinking Compose/miuix/androidx, not from shrinking our own code.
# Hook targets referenced via reflection/strings survive with it.
-keep class com.repl.bubbledrawer.** { *; }

# The overlay panel sets ComposeView's view-tree owners REFLECTIVELY (see
# OverlayComposeOwners.setViewTreeOwner — string-based Class.forName). Without keeps,
# R8 renames these androidx facade classes (nothing else references them by name) and
# the panel dies with "ViewTreeLifecycleOwner not found" on first attach.
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.lifecycle.ViewTreeViewModelStoreOwner { *; }
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }

# The Xposed metadata resources are merged verbatim (packaging block) and LSPosed reads
# them by path — nothing to keep for them here, noted to explain the java_init.list
# asymmetry above.
