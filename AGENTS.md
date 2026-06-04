# AGENTS.md

## Project Overview

IntelliJ IDEA / Android Studio plugin for i18n string resource management. Scans `strings.xml` files, provides a Compose Desktop UI in a tool window to edit/translate strings across locales, and writes them back.

## Build Commands

```bash
# Build plugin distribution (output: build/distributions/)
./gradlew buildPlugin

# Run build with shadow JAR (what the IDE run config does)
./gradlew build --stacktrace
```

There is no `runIde` task configured; the `.run/Run Plugin.run.xml` runs `build` only. To test the plugin, install the ZIP from `build/distributions/` or `plugin/` into an IDE manually.

## Architecture

- **Entry point**: `InsertStringsAction` — registered in `EditMenu` and `EditorPopupMenu`. Triggers `StringsScanner` to parse selected XML nodes, then opens the tool window.
- **Tool window**: `InsertStringsToolWindow` (Java) — factory that creates `InsertStringsUI`.
- **UI**: `InsertStringsUI` (Kotlin) — Compose Desktop via `ComposePanel` (Swing-embedded). All `@Composable` functions are `private` in this file. State managed via `mutableStateOf`/`mutableStateListOf`.
- **Business logic**: `InsertStringsManager` — coordinates scan, insert, copy, paste operations.
- **XML I/O**: `StringsScanner` (reads), `StringsWriter` (writes).
- **AI translation**: `AITranslator` + `AiSettingsService` (persisted via IntelliJ `applicationService`). Supports OpenAI-compatible and other protocols via `AiProtocol` enum.

## Key Conventions

- **Mixed Kotlin/Java**: `InsertStringsToolWindow` is Java; everything else is Kotlin. New code should be Kotlin.
- **Compose Desktop, not Android Compose**: UI uses `androidx.compose.desktop` + `ComposePanel` from `compose.desktop.currentOs`. Do not use Android-specific Compose APIs.
- **Shadow JAR**: `shadowJar` runs as part of `build`. It merges service files and strips signing metadata. The output JAR replaces the default one (`archiveClassifier = ""`).
- **IntelliJ platform**: Targets IC 2025.1.3, `sinceBuild = 251`, no `untilBuild`. Plugin depends on `com.intellij.modules.platform` and bundles `java` plugin.
- **JVM 17**: Both Java and Kotlin compile targets are JVM 17.

## Known Issues

- The IntelliJ Gradle plugin may fail to resolve the builtin `Kotlin` plugin from the IDE cache. See `README.MD` for the manual workaround (editing `builtinRegistry-1.xml` in the Gradle cache directory).

## No Tests

This project has no test suite. Verify changes by building (`./gradlew buildPlugin`) and testing the plugin manually in an IDE.
