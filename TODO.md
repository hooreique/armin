# Armin implementation TODO

This file is the resumable source of truth for implementation progress. Update it before and
after each material work unit. A checked item means that the implementation exists **and** the
listed verification has passed; items marked `[~]` are in progress.

## Resume here

- Current phase: tracked integration checkpoint and reproducible dependency capture
- Next action: commit the quality-gated integrated sources, run `nix run .#update-deps`, commit the
  generated Gradle dependency manifest, then run the Nix checks and APK inspection
- Last checkpoint commit: `fe7a3f0 build: complete pinned Android toolchain`
- Integrated browser, proxy, Android, and Nix sources pass the complete Gradle quality gate. The
  remaining work is the tracked-source Nix build and release-artifact verification.

## 1. Project and reproducible toolchain

- [x] Create the single-module Kotlin/Android Views Gradle project.
- [x] Pin Android SDK, JDK, Gradle, AndroidX, and quality-tool versions.
- [x] Add Spotless/ktfmt, Detekt, Android Lint, unit tests, and the `quality` task.
- [x] Add `flake.nix`, `flake.lock`, Android SDK composition, and the development shell.
- [x] Put `java`, `gradle`, `adb`, `ktfmt`, and JetBrains `kotlin-lsp` in the shell.
- [~] Add offline Gradle dependency capture plus `nix run .#update-deps`.
- [x] Make `nix fmt` format Nix and Kotlin/Gradle Kotlin files.
- [ ] Make `nix flake check` run all required quality gates.
- [ ] Make `nix build .#default` produce `result/apk/armin.apk`.
- [ ] Verify that the APK is installable and debug-signed.

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
- [x] Add parser/splitter/proxy lifecycle and local-server integration tests.
- [x] Wire proxy readiness and AndroidX WebKit proxy override before the first navigation.
- [x] Clear the override and stop all proxy resources during final Activity destruction.
- [x] Show an explicit user-visible error when proxy override is unsupported or startup fails.

## 4. Integration, documentation, and release artifact

- [~] Integrate source work streams and resolve API/lifecycle races (final source audit running).
- [~] Confirm only `INTERNET` permission and no exported control surface/unsafe JS bridge (final
  manifest/APK audit pending).
- [~] Confirm no site-data clearing, SSL bypass, remote code, analytics, or production key exists
  (final APK audit pending).
- [x] Write README: scope, SDKs, commands, LSP setup, debug signing/install, limitations, licenses.
- [x] Run `./gradlew spotlessApply` and commit formatting-only changes if needed.
- [x] Run `./gradlew quality` successfully.
- [x] Run focused proxy/browser unit tests successfully.
- [x] Run `nix fmt` successfully.
- [ ] Run `nix flake check` successfully.
- [ ] Run `nix build .#default` successfully from tracked sources.
- [ ] Inspect `result/apk/armin.apk` name, manifest, signature, and contents.
- [ ] Update this file with final verification evidence and handoff notes.

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

## Decisions and known constraints

- Package/application ID: `dev.armin`.
- ClientHello parse/accumulation failure policy: forward every captured byte unchanged, then use a
  raw tunnel; EOF after a truncated input closes after forwarding the captured prefix.
- Redirect enforcement is limited to main-frame navigation callbacks exposed by WebView, as the
  requirements acknowledge; the CONNECT proxy does not decrypt TLS.
- No content filter lists or active filtering are included in the MVP.
