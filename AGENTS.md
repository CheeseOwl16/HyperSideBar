# Repository Guidelines

AI-assistant-oriented guide to `hyperSidebar`. Read this before editing anything.

## Project Overview

LSPosed/Xposed module that replaces the HyperOS/MIUI native sidebar with a **dual-ring semicircle fan joystick menu**: edge-hold / bottom-corner swipe gesture → fan overlay → app launched in freeform (小窗). Kotlin + Jetpack Compose on the **miuix KMP** UI kit. Hooks three Xiaomi system hosts — it does not run as a normal app except for its own settings process.

- Verified only on **HyperOS 3.300** (marble), Android 13+. OS2 = offline-adapted; OS4 = native spike in progress.
- Requires root + LSPosed (API 101+). Scopes: `com.miui.securitycenter`, `com.android.systemui`, `com.miui.home` (`app/src/main/resources/META-INF/xposed/scope.list`).
- **Identity split (easy to break):** `namespace = com.lsp.hypersidebar` (source tree, class paths, resources) vs `applicationId = io.github.mikudayoooooooo.hypersidebar` (APK/process identity, LSPosed-repo requirement). When addressing components cross-process: package part → applicationId, class part → namespace.
- HyperOS 3.300 only in README; adaptation matrix: `docs/adaptation/adaptation-checklist.md`.

## Architecture & Data Flow

Single entry point `XposedInit` (XposedModule), routing by **(package, process)**:

```
LSPosed framework
 └ XposedInit.kt ─ routes by (package, process)
    ├ com.miui.home           → EdgeGestureHook (portrait edge/dwell, NavStubView corner swipe)
    │                            └ FanMenuController + BroadcastLaunchStrategy → ordered broadcast
    ├ com.miui.securitycenter:ui → TurboLayout (landscape B-route trigger, passthrough flags,
    │                              layer hiding, **strip state machine `handleStripGestureCore`**),
    │                              RelayGestureSink, FreeformRelayHook
    │                              → FanMenuController + DirectLaunchStrategy (FreeformLauncher,
    │                                ShortcutLauncher, AllAppsActivity panel)
    ├ com.android.systemui    → SystemUiHook (QS tile data-layer direct click),
    │                             SystemUiInputSpy / BackGestureSignalProbe (OS4, log-only)
    └ module process          → MainActivity (settings), ShortcutRelayReceiver,
                                  UnfreezeRelayService, ConfigPullService
```

Data flow: hook detects gesture → `FanMenuController` assembles `FanConfig` → `ComposeFanHost` WindowManager overlay → selection → launch strategy → `FreeformLauncher`/`ShortcutLauncher` (root-relay fallback for non-exported targets). All cross-process glue is broadcast/Messenger IPC; keys/actions/extras live **only** in `prefs/PrefContract.kt`.

Hard-won architectural constraints — do not "fix" these:

- **`RemotePreferences` in hook processes is a dead snapshot** (no prefs push over AIDL; change listeners only fire in-process and are weak). Config reachability is solved by own channels: `ConfigSync` + `SyncedPrefs` + `ConfigPullBridge`, and **system properties** (`persist.hsb.*`) for anything native.
- **OS4 launcher is pure Rust/C++ with no dex, no ART.** LSPosed can only `dlopen` `libhypersidebar_native.so` (`native_init`); native gets no Java-side data → properties only (`CornerGeomBridge.push/pushBand`, read-back verified).
- **Hook targets are never hard-coded.** Obfuscated names drift; `anchor/` resolves them structurally at runtime: L0 disk hint → L1 reflection fingerprint verify → L2 DexKit structural scan, fail-closed per role, disk-cached per host version.
- **`:ui` silently fake-succeeds** `startActivityAsUser` on unlaunchable targets (no exception) → non-exported targets go through module-process root relay (`ShortcutRelayReceiver`).
- **Component class names must be absolute** (`ComponentName` two-arg ctor does not expand leading dot).

## Key Directories

| Path | Purpose |
|---|---|
| `app/src/main/java/com/lsp/hypersidebar/` | All app source (namespace `com.lsp.hypersidebar`) |
| `hook/` | Host hooks + probes/state (`TurboLayout`, `EdgeGestureHook`, `SystemUiHook`, `CornerTrigger`, `CircuitBreaker`) |
| `prefs/` | ⭐ `PrefContract.kt` wire contract; `SettingsRepository` |
| `ui/fan/` | Compose fan overlay: `ComposeFanHost`, `FanMenuController`, pure geometry in `FanGeometry` |
| `ui/settings/` | ~25 miuix settings screens (navigation3 `NavDisplay`/`NavKey`) |
| `ui/allapps/` | Exported freeform "all apps" panel activity |
| `util/` | `HLog`, `ConfigSync`, launch/caches/relay/native bridges, `MisclickCaliber` |
| `anchor/` | Structured hook-target resolution (hint + reflection + DexKit) |
| `theme/` | `HyperSidebarTheme` over `MiuixTheme` |
| `app/src/main/cpp/` | `hypersidebar_native.c` (OS4 native entry, `HSB_NATIVE_SPIKE` gated), `launcher_profiles.h` (generated), CMake + export map |
| `app/src/main/resources/META-INF/xposed/` | `module.prop` (minApiVersion 101, staticScope=false), `scope.list`, `java_init.list`, `native_init.list` |
| `hidden-api/` | Compile-only `@hide` stubs (`InputChannel`, `InputEventReceiver`) — **compileOnly, no keep rules** |
| `docs/`, `tools/`, `PROJECT_NOTES.md`, `.workbuddy/`, `.zcode/`, `.mimosa/`, `.ota-extract/`, `_apk/`, `_repos/` | **Git-ignored local research workspace.** Editing them never shows in `git status` |

State: **no ViewModel, no DI framework, no Flow/RxJava.** Compose snapshot state + `SharedPreferences` change listeners + `RemotePrefsBridge.prefs` as global recomposition signal; hook side uses `@Volatile` singletons. Coroutines only for IO (`Dispatchers.IO`/`withContext`) and ordered-broadcast replies (`suspendCancellableCoroutine` + `withTimeout`); hook processes also use `HandlerThread`/executors.

### Reading order for a new agent

`PROJECT_NOTES.md` (architecture ledger, hard conclusions §7, code discipline §7.4) → `docs/adaptation/os4-native-handoff.md` (current OS4 state, red lines §9) → `docs/adaptation/adaptation-checklist.md`. Current branch is `spike/os4-native` (HEAD `ca10415`). `PROJECT_NOTES.md` §2/§8/§9 were corrected 2026-10-14 (miuix 0.9.4, Java 21, versionName 2.2.0, 342 commits); its **维护日志 is a history ledger and can lag** — when a number matters, re-derive it from `app/build.gradle.kts` / `gradle/libs.versions.toml` / `wc -l` / `git rev-list`.

## Development Commands

Windows host (`gradlew.bat`), Gradle 9.3.1, daemon JVM 21.

```powershell
# Build
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain   # canonical full check

# Native syntax only (fast; invalidates nothing). Windows GBK-pitfall: set UTF-8 first
$env:PYTHONUTF8=1; python tools\check_native_syntax.py

# Install + restart hooks — restart BOTH hosts or you get "new native + old :ui"
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell "su -c 'am force-stop com.miui.securitycenter'"   # :ui = Kotlin half
adb shell "su -c 'am force-stop com.miui.home'"             # launcher = native half
# Open the module app once after install: it is the process that pushes persist.hsb.* props

# Forensics
adb logcat -d -s HSBNative:* TurboLayout:* SpySink:* SysInputSpy:* | Select-String 'gate:|hooks ready|hook HB|chanpublish|SUPPRESS|AUTH|band geom|arrow '

# OS4 "native went silent" four-way diagnosis (auto-verdict: not-injected / never-installed / WIPED / no-traffic)
pwsh -File tools\hsb_native_silence_probe.ps1 -Install -Inject -Watch 900
pwsh -File tools\hsb_native_silence_probe.ps1 -FromFile <saved-log>    # offline verdict only, no device

# On-device acceptance harness (real regression suite)
pwsh -File tools\hsb_device_test.ps1 -Stage A|B|C|L|M|N|O [-Install] [-Inject] [-Wait N]
```

### OS4 native (`app/src/main/cpp/`) red lines

Editing `hypersidebar_native.c` means touching a Xposed inline hook inside the HyperOS launcher. Non-negotiables (from `os4-native-handoff.md` §9):

1. **arrowfix has two halves** — `hsb_arrow_state_release()` and `if (fixed) g_arrow_due = 1;`. Deleting one compiles fine and fails silently (arrow residue); `NativeArrowfixInvariantTest` guards it.
2. **Only hook `handle_back_gesture`** (side-only, per-event boundary). Never hook shared outer entries (`on_input_event`) — that ANR'd/killed bottom Home+recents on-device.
3. **No global `dlopen`/`dlsym` interception**; caller-targeted PLT hooks only.
4. **Never overwrite a live-mapped `.so`**, never rewrite `.text` that another thread may be executing (tear → SIGILL) — this is why the patch-integrity patrol reports `patch=WIPED` and heals via the framework (`unhook_func` → verify original prologue → `hook_func`), never by writing bytes ourselves.
5. **The module never restarts a host process**; only prompt the user.
6. Keep the 16 KB page contract (`PT_LOAD.p_align ≥ 16384` + aligned zip entry); a violation aborts the whole module at `native_init`.
7. Prologue/prologue-mask matching is fail-closed: if a scan is ambiguous (≠1 hit), refuse to install rather than guess.
8. Prefer pure-JVM testable cores; native changes need `tools\check_native_syntax.py` **and** an on-device run before being called done.
9. **Known ROM behavior (2026-10-14, fixed):** HyperOS reclaims the launcher's APK-mapped `.text` under memory pressure (`LowMemDetector`/`ScenarioStub`), which silently reverts inline hooks. Symptoms: `patch=WIPED` / `ARCHIVE REMAP DETECTED`, then no `hook HIT`/`chanpublish`/`SUPPRESS`. The module now self-heals within 5 s (`hook HB patch RE-ARMED (G3)`); if you see repeated `ARCHIVE REMAP DETECTED` without `RE-ARMED`, the heal is failing or hit its 5-attempt cap — read `heal=N/M` in the heartbeat line.

Release builds are R8 + resource shrinking with `app/proguard-rules.pro` (`-keep` for `XposedInit`, `-keepnames` for `com.lsp.hypersidebar.**`); signing reads git-ignored `keystore.properties` — absent ⇒ unsigned release APK. Any build **rewrites `version.properties`** (`preBuild`, versionCode = `yyyyMMdd*100 + seq`); commit it as a standalone chore.

## Code Conventions & Common Patterns

- **Cross-process contract:** every key/action/extra/probe code goes in `prefs/PrefContract.kt` (`PrefKeys`, `HostPackages`, `PrefsFiles`, `LayoutDefaults`). Never scatter literals; add new keys there first.
- **No inline magic numbers in UI** for ranges/steps/defaults — same-source constants in `LayoutDefaults`, and every adjustable setting must survive `restoreAllDefaults()`.
- **Hooks:** extend `BaseHook` (`init()`/`name`/`isInit`), resolve targets through `anchor/`, register with EzXHelper `MethodFinder`/`HookFactory`. Wrapper hooks guard on per-role fail-closed. `CircuitBreaker` stops intruding after 5 mechanism failures (no self-heal); `DataDeadState` latches ROM incompatibility.
- **Error handling:** `runCatching { … }.onFailure { HLog.w/e(…, t) }` everywhere; degrade (S1 auto-degrade back to native sidebar) rather than crash a system host.
- **Logging:** `HLog` (1000-entry ring buffer, `verboseEnabled`, redaction). **Never log `RelayToken`** or put it in self-check exports. Per-incident `Trace` ids.
- **Exported IPC ACL:** every exported component verifies uid and/or `RelayToken`. `allowBackup=false` on purpose (token + circuit state must not migrate).
- **Naming:** classes `PascalCase`, members `camelCase`, constants `SCREAMING_SNAKE` in `object`s; hooks `*Hook`; strategies `*Strategy`/`*Bridge`; pure-logic helpers as Kotlin `object` singletons (`CornerTrigger`, `MisclickCaliber`, `AnchorSpec`); tests `*Test`.
- **Prefer pure-JVM testable cores** for logic (state machines, geometry, contracts) and keep the Xposed glue thin — that is the established split (`CornerTrigger`, `FanGeometry`, `QsAdapterContract`, `AnchorSpec` are pure; their glue classes are device-verified).
- **Kotlin↔C mirrors are enforced by source-text invariant tests** (`NativePrologueMirrorTest`, `NativeArrowfixInvariantTest`) — update both sides and their guard tests together. `launcher_profiles.h` is generated by `tools/gen_launcher_profiles.py`.
- **Comments are evidence-carrying:** findings cite source/lines/dates; speculation is marked `【待验证】`. Conclusions are only written after hard evidence (AOSP source → docs → local `android.jar` + `api-versions.xml`).

## Important Files

| Path | Why it matters |
|---|---|
| `app/src/main/java/com/lsp/hypersidebar/XposedInit.kt` | Module entry, host/process routing |
| `app/src/main/java/com/lsp/hypersidebar/prefs/PrefContract.kt` | All cross-process keys, host packages, defaults |
| `app/src/main/java/com/lsp/hypersidebar/hook/EdgeGestureHook.kt` | Launcher portrait edge/corner gesture |
| `app/src/main/java/com/lsp/hypersidebar/hook/TurboLayout.kt` | `:ui` landscape B-route trigger + layer hiding |
| `app/src/main/java/com/lsp/hypersidebar/hook/CornerTrigger.kt` | Pure corner-swipe state machine (24 tests) |
| `app/src/main/java/com/lsp/hypersidebar/ui/fan/ComposeFanHost.kt` | WindowManager Compose host, hysteresis, idle watchdog |
| `app/src/main/java/com/lsp/hypersidebar/ui/fan/FanGeometry.kt` | Pure fan geometry |
| `app/src/main/java/com/lsp/hypersidebar/anchor/AnchorResolver.kt` / `AnchorSpec.kt` | Structured anchor resolution (rule engine is pure) |
| `app/src/main/java/com/lsp/hypersidebar/util/ShortcutLauncher.kt` | Launch strategies (Default/System/ROOT) + `FailureReason` |
| `app/src/main/java/com/lsp/hypersidebar/util/ConfigSync.kt` | Prefs replication into hook processes |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | SDK levels, versionCode logic, NDK/packaging, deps |
| `app/src/main/cpp/CMakeLists.txt`, `exports-lsposed.map` | Native build: version script, `max-page-size=16384`, `-fvisibility=hidden` |
| `PROJECT_NOTES.md` | Architecture/hard-conclusion ledger (stale in §2/§8) |
| `docs/adaptation/os4-native-handoff.md` | Current OS4 state, open issues, red lines |

## Runtime/Tooling Preferences

- **JDK 21** (daemon toolchain via foojay, `gradle/gradle-daemon-jvm.properties`); source/target Java 21. Android SDK path in git-ignored `local.properties`; `.idea` uses temurin-21.
- **AGP 9.1.1 / Kotlin 2.4.0 / Gradle 9.3.1.** All deps via the version catalog `gradle/libs.versions.toml` (AGP plugin aliases: `android-application`, `android-library`, `kotlin-compose`; there is no `kotlin-android` alias).
- `compileSdk 37 / minSdk 33 (miuix-blur RuntimeShader) / targetSdk 36`; no product flavors (debug + release only).
- NDK `27.2.12479018`, CMake `4.1.2`, **arm64-v8a only**, `jniLibs.useLegacyPackaging=false` (16 KB page alignment is a hard requirement on marble OS3/OS4) — keep `-Wl,-z,max-page-size=16384`.
- **No CI** (no `.github/`), **no ktlint/detekt/spotless/`.editorconfig`/`lint.xml`** — do not add lint tooling or expect CI gates. No emulator; device-only verification.
- Host-side scripts: Python 3 (set `PYTHONUTF8=1` on Windows) and PowerShell. `tools/` is research tooling — never wire it into Gradle.

## Testing & QA

- **Frameworks:** JUnit 4.13.2 only (JUnit 5, Robolectric, MockK, Turbine, Compose-UI-Test: none). `androidTest` is the AS template and has never been run; **no coverage tooling and no thresholds**.
- Unit tests: `app/src/test/java/com/lsp/hypersidebar/` — pure-JVM logic only (79 `@Test` methods across 10 classes): `anchor/AnchorEngineTest`, `hook/CornerTriggerTest`, `hook/QsAdapterContractTest`, `ui/fan/FanGeometryCornerTest`, `ui/fan/FanBoardShapeTest`, `util/MisclickCaliberTest`, `util/CornerGeomBridgeBandTest`, `util/NativePrologueMirrorTest`, `util/NativeArrowfixInvariantTest`.
- **What to run:** `.\gradlew.bat :app:testDebugUnitTest`. Then `.\gradlew.bat :app:assembleDebug`, then on-device via `tools\hsb_device_test.ps1` / `docs/adaptation/*checklist*`. For OS4 native regressions, `tools\hsb_native_silence_probe.ps1 -Watch` is the first step (its verdict decides G2/G3 vs a config fix — do not guess).
- **Checklist hygiene:** `docs/adaptation/os4-native-hold-route-test-checklist.md` §0.5 lists which stages are still valid — stages D/E/R/G and O/P/Q describe switches that were deleted on 2026-10-13 (`cancel`/`evoke`/`delegate`/`recover`/`judge`, aux anchors). Read §0.5 before running a stage.
- **Untested-by-design layers** (device-verified only): `ui/settings/**`, interactive parts of `ui/fan/**`, all hook glue, `anchor` glue, native C, `prefs/`. When adding logic here, extract a pure core and unit-test that core; skip gracefully (`Assume`) when real ROM fixtures are unavailable — never weaken an assertion to make it pass.
- **Do-done criteria per the repo's own plans:** pure logic + new unit tests + full `testDebugUnitTest` pass count recorded; visual/behavioral changes require on-device acceptance before being called done.

## Git Conventions

- Conventional-commit style with Chinese subjects: `type(scope): 中文描述——理由/实锤` (`feat`, `fix`, `refactor`, `chore`, `docs`, `probe`; scopes `os4`/`os2`/`os3`, `native`, `trigger`, `fan`, `anchor`, `diag`, `settings`). Multi-line bodies via `git commit -F -` citing symptom/root cause/fix/guard/verification.
- Stage files **explicitly** — never `git add -A`. `version.properties` goes in its own chore commit. Verify with `git diff --cached --stat` (watch line-ending pollution).
- `docs/`, `tools/`, `PROJECT_NOTES.md` are ignored locally; don't rely on them as tracked deliverables.
