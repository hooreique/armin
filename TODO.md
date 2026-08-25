# Armin implementation TODO

This file is the resumable source of truth for implementation progress. Update it before and
after each material work unit. A checked item means that the implementation exists **and** the
listed verification has passed; items marked `[~]` are in progress.

## Resume here

- Current phase: final Nix source-filter checkpoint and rebuild
- Next action: commit the non-build documentation source filter, rerun Nix build/check once, then
  finalize this record without invalidating the Android derivation
- Last checkpoint commit: `fbe9c3e fix: make proxy DNS lifecycle cancellable`
- The API 29 application artifact passes, but the final verification record itself currently changes
  the broad package source. Exclude only non-build root documents before the final handoff commit.

## 1. Project and reproducible toolchain

- [x] Create the single-module Kotlin/Android Views Gradle project.
- [x] Pin Android SDK, JDK, Gradle, AndroidX, and quality-tool versions.
- [x] Add Spotless/ktfmt, Detekt, Android Lint, unit tests, and the `quality` task.
- [x] Add `flake.nix`, `flake.lock`, Android SDK composition, and the development shell.
- [x] Put `java`, `gradle`, `adb`, `ktfmt`, and JetBrains `kotlin-lsp` in the shell.
- [x] Add offline Gradle dependency capture plus `nix run .#update-deps`.
- [x] Make `nix fmt` format Nix and Kotlin/Gradle Kotlin files.
- [~] Make `nix flake check` run all required quality gates (source-filter rerun pending).
- [~] Make `nix build .#default` produce `result/apk/armin.apk` (source-filter rerun pending).
- [~] Verify that the APK is structurally installable and debug-signed (final rerun pending).

## 2. Browser UI and navigation

- [x] Build a black, single-window WebView UI with one bottom address field and insets.
- [x] Always start a new root session on a blank black document with an empty address field.
- [x] Implement strict HTTPS URL normalization and validation with unit tests.
- [x] Track committed document URL separately from a pending blocked navigation.
- [x] Allow explicit address submissions and direct HTTPS link gestures.
- [x] Block observable automatic main-frame redirects and present/focus their destination.
- [x] Block HTTP/external schemes, popups, new windows, and external-app handoff.
- [x] Configure dark mode, cookies/storage, mixed-content/file-access restrictions, and SSL policy.
- [x] Implement back priority: fullscreen video, WebView history, Activity finish.
- [x] Implement landscape immersive custom-view video handling and best-effort video script.
- [x] Add the no-op content-blocking request model/engine and WebView interception hook.
- [x] Add a feature-gated document-start bootstrap registration point.
- [~] Exercise browser behavior with Android instrumentation/manual fixtures (fourteen fixtures compile;
  execution needs a connected Android device or emulator).

## 3. Loopback SpoofDPI proxy

- [x] Implement a loopback-only ephemeral HTTP CONNECT listener with bounded executors.
- [x] Implement bounded, incremental CONNECT parsing (hostname, port, bracketed IPv6).
- [x] Accumulate and parse a complete first TLS ClientHello across records/reads.
- [x] Locate the SNI hostname byte range with strict length/bounds validation.
- [x] Split SNI hostname bytes into non-empty writes that reconstruct the original exactly.
- [x] Apply `TCP_NODELAY`, connect/read/idle timeouts, and a documented parse-failure policy.
- [x] Implement bidirectional copying, half-close handling, cancellation, and socket cleanup.
- [x] Ensure DNS work cannot survive or accumulate across Activity/proxy-session shutdown.
- [x] Add parser/splitter/proxy lifecycle and local-server integration tests.
- [x] Wire proxy readiness and AndroidX WebKit proxy override before the first navigation.
- [x] Clear the override and stop all proxy resources during final Activity destruction.
- [x] Show an explicit user-visible error when proxy override is unsupported or startup fails.

## 4. Integration, documentation, and release artifact

- [x] Integrate source work streams and resolve API/lifecycle races.
- [x] Confirm only `INTERNET` is directly requested and no exported control surface/unsafe JS
  bridge exists.
- [x] Confirm no site-data clearing, SSL bypass, remote code, analytics, or production key exists.
- [x] Write README: scope, SDKs, commands, LSP setup, debug signing/install, limitations, licenses.
- [x] Run `./gradlew spotlessApply` and commit formatting-only changes if needed.
- [x] Run `./gradlew quality` successfully.
- [x] Run focused proxy/browser unit tests successfully.
- [x] Run `nix fmt` successfully.
- [~] Run `nix flake check` successfully for the final source filter.
- [~] Run `nix build .#default` successfully from the final tracked sources.
- [~] Inspect the final `result/apk/armin.apk` name, manifest, signature, and contents.
- [~] Update this file with final verification evidence and handoff notes.

## Verification log

Add commands, date, commit, and result here. Do not mark a gate complete without evidence.

- 2026-08-25: read all 874 lines of `requirements.md`; repository initially contained only the
  requirements document.
- 2026-08-25 (`08d3470`): `nix flake show --no-write-lock-file` evaluated the tracked API 37
  Android SDK, package, dev shell, formatter, check, and dependency-update app outputs
  successfully. This was an evaluation check only; builds remain pending.
- 2026-08-25: the Nix dev shell exposed OpenJDK 17.0.20, Gradle 9.5.1, adb 1.0.41,
  ktfmt 0.64, the pinned Android SDK variables, and the patched Kotlin LSP wrapper. The LSP package
  now uses the pinned nixpkgs JDK 25 and declares the standalone distribution's mixed license.
- 2026-08-25: browser/UI focused verification passed Spotless, Kotlin compilation, Android Lint,
  38 JVM tests, and compilation of fourteen instrumentation fixtures. No emulator or device was
  connected, so the instrumentation suite was not executed.
- 2026-08-25: proxy focused verification passed Spotless, Detekt, and 29 JVM tests with zero
  failures/skips. The integration tests include a real local JSSE TLS handshake, SNI splitting,
  bidirectional data, raw fallback, DNS/connect/read/idle deadlines, half-close, bounded executors,
  and lifecycle cases.
- 2026-08-25: `nix fmt`, `git diff --check`, `spotlessApply`, and the complete `quality` gate passed.
  The gate ran 67 JVM tests with zero failures/skips, Detekt, Spotless, and Android Lint with zero
  issues; `:app:compileDebugAndroidTestKotlin` also succeeded.
- 2026-08-25 (`6afb42a`): integrated sources were committed so every Android source/resource/test is
  present in the flake's Git source. The first `nix run .#update-deps` exposed an upstream Gradle 9
  incompatibility in nixpkgs' default unqualified `configurations` task. An attempted qualified
  eager resolver then exposed an unresolvable AGP internal variant, so the update app now records
  the exact quality, APK, and instrumentation-compile task graph instead.
- 2026-08-25: the first exact-task capture reached APK/lint/test compilation but the Kotlin client
  looped while discovering daemon files under Nix's sanitized `user.home` (`?`). Nix now supplies a
  writable task-specific Java home path and Kotlin compilation is fixed to the in-process strategy.
- 2026-08-25: `nix run .#update-deps` completed the exact 69-task graph in 2m47s, including the
  quality gate, debug APK assembly, and instrumentation-test Kotlin compilation. The resulting
  72,285-byte lockfile records 390 Maven paths across the pinned repositories.
- 2026-08-25 (`f7f94c9`, pre-audit artifact): `nix build .#default` succeeded from the committed
  source and dependency
  lock, producing `result/apk/armin.apk` (7,507,814 bytes, SHA-256
  `2e0de280a9883f3b96b7ebf2f7f32f976023fc78dcb4b9444c5d2dc867219d69`). `nix flake check`
  then evaluated every output and passed the package-backed quality check.
- 2026-08-25: the dev shell exposed all required tools/environment variables; `kotlin-lsp --version`
  returned `LS-262.9593.0`, inherited Neovim launched headlessly, and a fresh dev-shell
  `quality :app:compileDebugAndroidTestKotlin` invocation passed.
- 2026-08-25: build-tools 37 `apksigner` verified one v2 signer (`CN=Android Debug`, RSA-2048), and
  `zipalign -c -P 16 -v 4` passed. The APK manifest reports package `dev.armin`, min/target/compile
  SDK 28/37/37, debug mode, no backup, no cleartext, and only direct `INTERNET` permission. AndroidX
  contributes its self-signature dynamic-receiver permission and a `DUMP`-protected profile
  receiver; neither exposes proxy or navigation control.
- 2026-08-25: that pre-audit 86-entry APK contains no test tree, key/certificate/keystore, or native
  library. `adb devices -l` found no device/emulator, so structural installability is verified but
  a live install and the fourteen compiled instrumentation fixtures were not executed.
- 2026-08-25: the independent final audit found that the previous per-session JVM DNS executor
  could retain an interrupt-ignoring resolver after proxy close. The implementation now requires
  Android 10/API 29 and uses `DnsResolver` with a query-specific `CancellationSignal`; numeric IPv4
  and IPv6 bypass DNS, and no application-owned DNS thread/executor exists.
- 2026-08-25: the corrected dev-shell quality gate passed 71 JVM tests with zero failures, errors,
  or skips; Detekt, Spotless, Android Lint (zero issues), and fourteen-fixture instrumentation Kotlin
  compilation also passed. New regressions cover idempotent cancellation, ignored late callbacks,
  timeout/close cleanup across three proxy sessions, and server-initiated early close.
- 2026-08-26 (`fbe9c3e`): `nix run .#update-deps` reran all 69 capture tasks successfully in 2m50s;
  `nix/gradle-deps.json` remained byte-identical (SHA-256
  `a9515faef4bfeb04f530c64b908ef86a17a17c8cc0b38940b06078bf2eb43e18`).
- 2026-08-26: `nix build .#default` rebuilt the committed source, executing all 60 Nix quality/APK
  tasks in 2m3s. `nix flake check --print-build-logs` then passed every flake output and the
  package-backed default check.
- 2026-08-26: the final `/nix/store/66r96wa12hi8q6gwhwzwsyjr5n65szr0-armin-0.1.0` output contains
  `apk/armin.apk` (7,497,706 bytes, SHA-256
  `b0b826a076f978d99f3b415b5be35f19dfdeb663951e23c2b7f5e1abc675e86b`). Build-tools 37 verified
  zip alignment and one v2 `CN=Android Debug` RSA-2048 signer.
- 2026-08-26: the final manifest reports package `dev.armin`, min/target/compile SDK 29/37/37,
  debug mode, no backup, no cleartext, direct `INTERNET` only, the documented AndroidX
  self-signature permission, and only its `DUMP`-protected exported profile receiver beyond the
  required launcher. All 86 APK entries again contain no test tree, key/keystore/certificate, or
  native library. `adb devices -l` remains empty, so live install/instrumentation is still deferred.

## Decisions and known constraints

- Package/application ID: `dev.armin`.
- Minimum Android version: API 29, selected because it is the first public cancellable system DNS
  API and lets Activity shutdown terminate every application-owned proxy worker.
- ClientHello parse/accumulation failure policy: forward every captured byte unchanged, then use a
  raw tunnel; EOF after a truncated input closes after forwarding the captured prefix.
- Redirect enforcement is limited to main-frame navigation callbacks exposed by WebView, as the
  requirements acknowledge; the CONNECT proxy does not decrypt TLS.
- No content filter lists or active filtering are included in the MVP.
