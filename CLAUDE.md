# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

WuwAgent ("iXpert AI Assistant") is an IntelliJ/Android Studio plugin that adds AI coding features (Explain, Review, Improve, Impact Analysis, Query Validation, Generate Analysis Doc) backed by an on-prem Ollama server. The Kotlin plugin embeds a React webview (JCEF) as its main UI.

Companion docs in repo root: `DEVELOPMENT_GUIDE.md` (architecture rules), `WORKING_GUIDE.md` (per-feature ownership + dev environment), `AI_DEVELOPMENT_RULES.md` (rules AI tooling must follow).

## Common commands

> **설치파일(배포용 zip) 생성 요청 시 반드시 `./build.sh build`를 사용한다.**
> 결과물은 항상 2벌: 기존용(since-build 243) + IntelliJ 2026.2용(`-253`, since-build 253, jcef depends).
> `./gradlew build`나 `buildPlugin` 단독 실행은 1벌만 나오므로 설치파일 생성 용도로 쓰지 않는다.
> 빌드 후 _release의 두 zip 파일명을 보고한다.
>
> Windows 등 `build.sh`를 못 쓰는 환경에서는 같은 순서로 실행한다 (먼저 `_release`의 기존 zip을 `_release\old\`로 이동):
> ```
> gradlew.bat clean build -x test
> gradlew.bat buildPlugin copyToRelease -Pplatform=253
> ```
> 실행 후 `_release`에 기존용 zip 1개 + `-253` zip 1개가 있는지 확인한다.

- `./gradlew runIde` — main dev loop. Launches a sandbox IDE with the plugin installed.
- `./gradlew buildPlugin` — produces the installable `.zip` under `build/distributions/`.
- `./gradlew build` — 개발용 1벌 빌드(기존용만); finalizes by copying the zip to `_release/` (`copyToRelease`). 설치파일 생성에는 쓰지 않는다.
- `./gradlew verifyPlugin` — runs JetBrains plugin verifier.
- `./build.sh build` — 설치파일 생성: 기존용 + 2026.2용(`-253`) 2벌을 `_release/`에 생성 (이전 zip은 `_release/old/`로 이동, 하나라도 누락되면 실패).
- `./build.sh [build|plugin|webview|clean|rebuild|buildrun|run|release|verify]` — convenience wrapper. No-arg default is `buildrun`. `buildrun`/`run`/`plugin`은 개발용이라 기존용 1벌만 빌드한다.

The Gradle build wires the webview into `processResources`, so any plugin task rebuilds the webview as needed:
`installWebviewDependencies` → `buildWebview` (Vite) → `copyWebviewToResources` → `processResources`. Node 20.11.1 is auto-downloaded by the `node` plugin (don't install Node manually). Webview-only dev server: `cd webview && npm run dev`.

There is **no test task wired in** — there are no JUnit tests in this repo. Don't claim a test command exists.

## Architecture: Action → Agent → Service → LLM → UI

This layering is strictly enforced (see `AI_DEVELOPMENT_RULES.md`). Putting logic in the wrong layer is the most common rule violation here.

- **`action/`** — IntelliJ `AnAction` entry points (`ExplainAction`, `ReviewAction`, `ImpactAnalysisAction`, `QueryValidationAction`, `ImproveAction`, `DocGenerateAction` — right-click menu items declared in `META-INF/plugin.xml`) plus `WebviewActionRouter` which dispatches JSQuery commands from the React webview (`/explain`, `/chat`, `/task`, `/doc`, `/ragdoc`, `/apply`, `/viewDiff`, `/undo`, `/cancel`, `/saveMarkdown`, `/testConnection`, `/fetchModels`, `/changeModel`, `/openTabs`, `/openSettings`, `/alert`, `/saveChat`, `/loadChat`, `/loadLastChat`, `/listChats`, `/deleteChat`). **No business logic in this layer.**
- **`agent/`** — Holds all LLM-calling logic. Inherits from `BaseAgent` / implements `WuwAgent`. **LLM (Ollama) calls live only here.**
- **`service/`** — "Tools": `EditorContextService`, `EditorApplyService`, `EditorDiffService`, `FileSearchService`, `BuildContextService`, `TypeContextService`, `MarkdownFileService`, `ChatHistoryService`, `WuwLlmService`, plus `service/analysis/` extractors. **Services must not call the LLM.**
- **`prompt/`** — `PromptManager.loadPrompt(fileName)` reads from `src/main/resources/prompt/`. Prompts are never hardcoded; new features add a new prompt file there.
- **`ui/`** — `WuwToolWindowFactory` mounts the JCEF browser; `ui/bridge/JcefBridge` is the IDE→JS message channel (`sendMessage`, `sendMessageChunk`); `JcefMessageHandler` is the JS→IDE side feeding `WebviewActionRouter`.
- **`client/OllamaClient`** — HTTP client for Ollama (streaming + blocking). All settings (baseUrl, model, temperature, timeoutSeconds, contextWindow) come from `setting/SettingsState` (persisted IntelliJ application service).

### TaskAgent + TaskPipeline (the orchestration core)

`agent/TaskPipeline.kt` defines named pipelines (`Improve`, `Review`, `Impact`, `ExplainTask`, `QueryValidation`, `DocGenerate`, `Chat`) as ordered lists of `AgentStep(label, promptFile, isApplyable, isImproveStep)`. `TaskAgent` runs them inside a single `Task.Backgroundable`:

1. `IntentAnalyzer.analyze()` first does keyword matching, then falls back to an LLM classifier (`intent_prompt.txt`), then to `Chat`.
2. Each step calls `AgentStep.executeSync()`, which:
   - Detects an explicit filename in the user payload → `FileSearchService` (no editor fallback if a file is named but not found — returns an error result).
   - Otherwise pulls code from the active editor via `EditorContextService.extractCodeWithScope` (selection vs. full file).
   - Loads the prompt, calls `OllamaClient.callChatApiStream`, and for `isImproveStep` runs `flexibleApplySearchReplace`: direct SEARCH/REPLACE → blank-line-normalized retry → signature-line partial match (`partialMatchApply`).
3. Each step's result is pushed back to the webview via `JcefBridge` with its own `messageId` so each step renders as an independent chat bubble. Step labels stream as `step_noti` events too.

**Do not edit `TaskPipeline.kt` for feature work** — it is the shared pipeline. Add new pipelines as new `object` entries only when adding a genuinely new orchestration shape.

### Cancellation

`agent/TaskCancellationToken` is a global singleton. `cancel()` sets an `AtomicBoolean`, interrupts the registered background thread, closes the active `InputStream`, and disconnects the active `HttpURLConnection`. `OllamaClient` registers its stream/connection on the token; agents check `isCancelled.get()` between steps. The `/cancel` route reads `activeMessageId` *before* calling `cancel()` (it gets nulled by `reset()`).

### Webview build/runtime path

React + Vite + TypeScript under `webview/`. `vite.config.ts` uses `vite-plugin-singlefile` to emit a single bundled `index.html`, which Gradle copies to `src/main/resources/webview/`. JCEF loads it as a resource. Talking to the IDE goes through a `JBCefJSQuery` registered in `JcefMessageHandler` — keep a strong reference (it's pinned via `JcefBridge.registerMessageHandler` to dodge GC).

## Project conventions you must respect

- **Korean commit messages** (`feat: …`, `fix: …`, `refactor: …`). Enforced by `DEVELOPMENT_GUIDE.md` §11.
- **Per-feature ownership boundaries** in `WORKING_GUIDE.md`: each developer owns one feature's `agent/` class + `prompt/` file. Do not modify other features' agents/prompts, anything in `action/` or `service/`, or `agent/TaskPipeline.kt`, unless explicitly asked. Cross-feature edits require explicit user direction.
- **Default LLM settings** are intentional: `Context Window = 32768` is required (the default 4096 fails on files >~250 lines). `Base URL = http://ollama.jodongik.cloud`, `Model = qwen3-coder:30b`, `Timeout = 300`, `Temperature = 0.1`. Don't change these without being asked.
- **Diff-based edits**: code modifications go through `EditorApplyService` (SEARCH/REPLACE blocks or extracted code blocks) and `EditorDiffService` (3-way diff). Agents never write to files directly.
- **Branching**: `main` (release), `develop` (integration), `feature/*` (work). Never push directly to `main`.

## Plugin compatibility

Targets IntelliJ Platform 2024.3.5 (IC). `build.gradle.kts` patches `sinceBuild=243`; no `untilBuild` is set (plugin runs on all later builds). JVM 17. Kotlin Gradle plugin 2.1.20, IntelliJ Platform Gradle plugin 2.11.0.
IntelliJ 2026.2(253)부터 JCEF가 별도 모듈로 분리되어 `-Pplatform=253` 빌드 옵션이 `<depends>com.intellij.modules.jcef</depends>`를 빌드 시점에만 주입하고 since-build 253, 버전 접미사 `-253`을 적용한다 (plugin.xml 원본은 수정하지 않음).
