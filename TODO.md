# Armin implementation TODO

This file is the resumable source of truth for implementation progress. Update it before and
after each material work unit. A checked item means that the implementation exists **and** the
listed verification has passed; items marked `[~]` are in progress.

## Resume here

- Current phase: project bootstrap and parallel implementation
- Next action: integrate the browser, proxy, and Nix/Gradle work streams; then run unit tests
- Last known good commit: `8528a2d state requirements`
- Active work streams:
  - Browser/UI/navigation sources and tests
  - CONNECT/TLS proxy sources and tests
  - Gradle/Android/Nix build scaffolding

## 1. Project and reproducible toolchain

- [~] Create the single-module Kotlin/Android Views Gradle project.
- [~] Pin Android SDK, JDK, Gradle, AndroidX, and quality-tool versions.
- [~] Add Spotless/ktfmt, Detekt, Android Lint, unit tests, and the `quality` task.
- [~] Add `flake.nix`, `flake.lock`, Android SDK composition, and the development shell.
- [~] Put `java`, `gradle`, `adb`, `ktfmt`, and JetBrains `kotlin-lsp` in the shell.
- [~] Add offline Gradle dependency capture plus `nix run .#update-deps`.
- [ ] Make `nix fmt` format Nix and Kotlin/Gradle Kotlin files.
- [ ] Make `nix flake check` run all required quality gates.
- [ ] Make `nix build .#default` produce `result/apk/armin.apk`.
- [ ] Verify that the APK is installable and debug-signed.

## 2. Browser UI and navigation

- [~] Build a black, single-window WebView UI with one bottom address field and insets.
- [~] Always start a new root session on a blank black document with an empty address field.
- [~] Implement strict HTTPS URL normalization and validation with unit tests.
- [~] Track committed document URL separately from a pending blocked navigation.
- [~] Allow explicit address submissions and direct HTTPS link gestures.
- [~] Block observable automatic main-frame redirects and present/focus their destination.
- [~] Block HTTP/external schemes, popups, new windows, and external-app handoff.
- [~] Configure dark mode, cookies/storage, mixed-content/file-access restrictions, and SSL policy.
- [~] Implement back priority: fullscreen video, WebView history, Activity finish.
- [~] Implement landscape immersive custom-view video handling and best-effort video script.
- [~] Add the no-op content-blocking request model/engine and WebView interception hook.
- [~] Add a feature-gated document-start bootstrap registration point.
- [ ] Exercise browser behavior with Android instrumentation/manual fixtures.

## 3. Loopback SpoofDPI proxy

- [~] Implement a loopback-only ephemeral HTTP CONNECT listener with bounded executors.
- [~] Implement bounded, incremental CONNECT parsing (hostname, port, bracketed IPv6).
- [~] Accumulate and parse a complete first TLS ClientHello across records/reads.
- [~] Locate the SNI hostname byte range with strict length/bounds validation.
- [~] Split SNI hostname bytes into non-empty writes that reconstruct the original exactly.
- [~] Apply `TCP_NODELAY`, connect/read/idle timeouts, and a documented parse-failure policy.
- [~] Implement bidirectional copying, half-close handling, cancellation, and socket cleanup.
- [~] Add parser/splitter/proxy lifecycle and local-server integration tests.
- [ ] Wire proxy readiness and AndroidX WebKit proxy override before the first navigation.
- [ ] Clear the override and stop all proxy resources during final Activity destruction.
- [ ] Show an explicit user-visible error when proxy override is unsupported or startup fails.

## 4. Integration, documentation, and release artifact

- [ ] Integrate source work streams and resolve API/lifecycle races.
- [ ] Confirm only `INTERNET` permission and no exported control surface/unsafe JS bridge.
- [ ] Confirm no site-data clearing, SSL bypass, remote code, analytics, or production key exists.
- [ ] Write README: scope, SDKs, commands, LSP setup, debug signing/install, limitations, licenses.
- [ ] Run `./gradlew spotlessApply` and commit formatting-only changes if needed.
- [ ] Run `./gradlew quality` successfully.
- [ ] Run focused proxy/browser unit tests successfully.
- [ ] Run `nix fmt` successfully.
- [ ] Run `nix flake check` successfully.
- [ ] Run `nix build .#default` successfully from tracked sources.
- [ ] Inspect `result/apk/armin.apk` name, manifest, signature, and contents.
- [ ] Update this file with final verification evidence and handoff notes.

## Verification log

Add commands, date, commit, and result here. Do not mark a gate complete without evidence.

- 2026-08-25: read all 874 lines of `requirements.md`; repository initially contained only the
  requirements document.

## Decisions and known constraints

- Package/application ID: `dev.armin`.
- ClientHello parse failure policy: to be fixed during proxy integration and documented here.
- Redirect enforcement is limited to main-frame navigation callbacks exposed by WebView, as the
  requirements acknowledge; the CONNECT proxy does not decrypt TLS.
- No content filter lists or active filtering are included in the MVP.
