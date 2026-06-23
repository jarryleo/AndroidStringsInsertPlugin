# AGENTS.md

## Project Overview

IntelliJ IDEA / Android Studio plugin (id `cn.jarryleo.insert_strings`, product name "Android Strings Insert") for i18n string resource management. Scans `strings.xml` files, opens a Compose Desktop UI in a tool window to edit/translate strings across locales, and writes them back. Also includes an AI chat feature (multi-protocol) and Google Sheets sync.

Version is set in `build.gradle.kts:13` (currently `3.9.0`).

## Build Commands

```bash
# Build plugin distribution -> build/distributions/<name>-<version>.zip
./gradlew buildPlugin

# IDE run config ".run/Run Plugin.run.xml" runs ./gradlew build --stacktrace
```

There is no `runIde` task. To test the plugin, install the ZIP from `build/distributions/` into an IDE manually. The `plugin/` directory at the repo root is empty — ignore it; the artifact comes from `build/`.

## Architecture

- **Entry point**: `InsertStringsAction` (`src/main/kotlin/.../InsertStringsAction.kt`) — registered in `EditMenu` and `EditorPopupMenu` with shortcut `shift F1` / `control shift F1`. `AskAiAction` is a sibling action with shortcut `shift F3`. Both are declared in `src/main/resources/META-INF/plugin.xml`.
- **Tool window**: `InsertStringsToolWindow.java` is a `ToolWindowFactory` that constructs `InsertStringsUI` (Kotlin) and adds its root panel to the tool window.
- **UI** (`src/main/kotlin/.../ui/`): Compose Desktop via `ComposePanel` (Swing-embedded). `InsertStringsUI.kt` is the state container/facade; real logic is split across controllers in the same package:
  - `InsertStringsActionsController` — Copy / Paste / Insert / single-key AI translate
  - `InsertStringsSettingsController` — AI + Sheets settings load/save
  - `InsertStringsStringsOpsController` — AI-driven `strings.xml` read/write
  - `InsertStringsSheetsOpsController` — AI-driven Google Sheets ops
  - `InsertStringsChatContextBuilder` — JSON context attached to each AI call
  - `InsertStringsChatDriver` — chat flow (tool loop, SSE, protocol fallback)
  - `ChatStateHolder` — shared state interface implemented by `InsertStringsUI`
- **Business logic**: `InsertStringsManager` (`getInstance(project)`) — coordinates scan/insert/copy/paste. Also exposes the static `updateUI(project, entries)` used by the action.
- **XML I/O** (`src/main/kotlin/.../xml/`): `StringsScanner` (reads, takes `AnActionEvent` or `VirtualFile`), `StringsWriter` (writes), `ContextManager` (caches `strings.xml` context per project, `getInstance(project)`), `AndroidStringEscaper`, `StringsXmlFormatter`.
- **AI** (`src/main/kotlin/.../ai/`): `AITranslator` + `AiSettingsService` (registered as `applicationService`). Protocol variants live in the `AiProtocol` enum (OpenAI-compatible + others). `SseStreamParser` handles streaming responses; `ToolDefinitions` declares the tool schema for the AI chat.
- **Sheets** (`src/main/kotlin/.../sheets/`): `SheetsManager` + `SheetsSettingsService` + `SheetsSettingsState` for Google Sheets sync. OAuth client secret lives at `src/main/resources/META-INF/client_secret.json`.
- **Phrases** (`src/main/kotlin/.../phrases/`): `QuickPhrase` / `QuickPhrasesService` (also `applicationService`) / `DefaultPhrases` — quick-phrase snippets.

## Key Conventions

- **Mixed Kotlin/Java**: `InsertStringsToolWindow.java` is the only Java file; everything else is Kotlin. New code should be Kotlin.
- **Compose Desktop, not Android Compose**: UI uses `androidx.compose.desktop` + `ComposePanel` from `compose.desktop.currentOs`. The `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.compose` plugins are applied. Do not use Android-specific Compose APIs.
- **JVM 17**: Java and Kotlin both target 17 (see `tasks { withType<JavaCompile> { ... } }` and `KotlinCompile` blocks in `build.gradle.kts`).
- **Shadow JAR**: `shadowJar` runs as part of `build` (see `tasks { build { dependsOn(shadowJar) } }` in `build.gradle.kts`). It uses `archiveClassifier = ""` so the shaded JAR replaces the default one, merges service files, and strips `META-INF/*.SF`/`*.DSA`/`*.RSA`. IntelliJ platform deps are excluded by being on the platform classpath, not by Gradle.
- **Gradle properties** (`gradle.properties`): `kotlin.stdlib.default.dependency=false` (IntelliJ platform provides stdlib), `org.gradle.configuration-cache=true`, `org.gradle.caching=true`.
- **IntelliJ platform**: Targets IC `2025.1.3`, `sinceBuild = 251`, `untilBuild` left null (no upper bound). Bundles the `java` plugin. Depends on `com.intellij.modules.platform`.
- **Signing/publishing env vars** (consumed by `signPlugin` / `publishPlugin` tasks): `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`. Only relevant when running `signPlugin` / `publishPlugin`; normal `build` / `buildPlugin` do not need them.

## Known Issues

- The IntelliJ Gradle plugin may fail with `Cannot find builtin plugin 'Kotlin' for IDE: ...\ideaIC-<ver>`. Fix: edit `plugins/builtinRegistry-1.xml` inside the Gradle-cached `ideaIC-<ver>` directory and add the `Kotlin` / `org.jetbrains.kotlin` entry shown in `README.MD`. The cached path differs between Gradle runs (e.g. `2025.3.1` vs `2025.1.3`); use the one in the error message.

## No Tests

This project has no test suite. Verify changes by running `./gradlew buildPlugin` and installing the resulting ZIP into an IDE.
