# Third-party notices

Armin's original source code and documentation are licensed under the MIT
License in [`LICENSE`](LICENSE), Copyright (c) 2026 hooreique. That license does
not replace the licenses of the components described below. Copyright and
trademark rights in those components remain with their respective owners.

The same legal files are embedded in standalone APKs under `assets/licenses/`
and installed beside Nix package outputs under `share/licenses/armin/` and
`share/doc/armin/`.

## Software distributed in the APK

### Apache License 2.0 components

The resolved production artifacts below are primarily licensed under the
Apache License 2.0. The complete license is in
[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

```text
androidx.activity:activity-ktx:1.13.0
androidx.activity:activity:1.13.0
androidx.annotation:annotation-experimental:1.4.1
androidx.annotation:annotation-jvm:1.10.0
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.collection:collection-jvm:1.4.2
androidx.compose.runtime:runtime-annotation-android:1.9.0
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-ktx:1.19.0
androidx.core:core-viewtree:1.0.0
androidx.core:core:1.19.0
androidx.interpolator:interpolator:1.0.0
androidx.lifecycle:lifecycle-common:2.6.2
androidx.lifecycle:lifecycle-livedata-core:2.6.2
androidx.lifecycle:lifecycle-runtime-ktx:2.6.2
androidx.lifecycle:lifecycle-runtime:2.6.2
androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2
androidx.lifecycle:lifecycle-viewmodel:2.6.2
androidx.navigationevent:navigationevent-android:1.0.0
androidx.profileinstaller:profileinstaller:1.4.0
androidx.savedstate:savedstate-ktx:1.2.1
androidx.savedstate:savedstate:1.2.1
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing:1.2.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.webkit:webkit:1.17.0
com.google.guava:listenablefuture:1.0
org.jetbrains.kotlin:kotlin-stdlib:2.2.10
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
org.jetbrains:annotations:23.0.0
org.jspecify:jspecify:1.0.0
```

The upstream `kotlinx.coroutines` 1.9.0 attribution required by its NOTICE file
is reproduced in [`NOTICE`](NOTICE).

### Kotlin standard library third-party portions

Kotlin standard library 2.2.10 identifies these JVM portions separately:

- `kotlin.collections` contains code derived from GWT, Copyright 2007-2008
  Google Inc., under Apache-2.0.
- `kotlin.util.UnsignedJVM` contains code derived from Guava, Copyright 2011
  The Guava Authors, under Apache-2.0.
- `kotlin.time` contains code derived from ThreeTenBP, Copyright (c)
  2007-present Stephen Colebourne and Michael Nascimento Santos, under the
  BSD 3-Clause license in
  [`LICENSES/BSD-3-Clause-ThreeTenBP.txt`](LICENSES/BSD-3-Clause-ThreeTenBP.txt).
- `kotlin.util.MathJVM` contains code derived from Boost special math functions,
  Copyright Eric Ford and Hubert Holin 2001, under the Boost Software License
  1.0 in [`LICENSES/BSL-1.0-Kotlin.txt`](LICENSES/BSL-1.0-Kotlin.txt).

See the upstream Kotlin 2.2.10
[`license/README.md`](https://github.com/JetBrains/kotlin/blob/v2.2.10/license/README.md)
for the authoritative component mapping.

### Chromium WebView boundary interfaces

`androidx.webkit:webkit:1.17.0` contains `org.chromium.support_lib_boundary`
interfaces synchronized from Chromium into AndroidX. Those portions are
governed by Chromium's BSD 3-Clause license in
[`LICENSES/BSD-3-Clause-Chromium.txt`](LICENSES/BSD-3-Clause-Chromium.txt),
Copyright 2015 The Chromium Authors. The AndroidX WebKit contributor guide
documents how these interfaces are authored in Chromium and copied into the
client library.

### R8/D8-generated code

Android's D8 tool synthesizes `com.android.tools.r8.DesugarVarHandle` support
classes into this APK. Those classes are governed by the R8 project's BSD
3-Clause license in
[`LICENSES/BSD-3-Clause-R8.txt`](LICENSES/BSD-3-Clause-R8.txt), Copyright (c)
2016 the R8 project authors.

## Repository and development-only components

- The Gradle wrapper scripts and JAR distributed in this repository remain
  under Apache-2.0; their source headers and embedded license are preserved.
- JUnit 4.13.2 (EPL-1.0), Hamcrest 1.3 (BSD 3-Clause), AndroidX Test
  (Apache-2.0), build plugins, Android SDK, JDK, Gradle, ktfmt, Detekt and
  Spotless are used only to build or test Armin and are not included in the
  APK.
- The Nix development shell downloads JetBrains Kotlin LSP standalone. It is
  not part of the APK; its bundled `license/` inventory is preserved in the
  Nix package, and JetBrains or third-party terms apply to those components.

## Design reference, not bundled code

The SNI write-splitting idea was informed by the Apache-2.0 project
[`xvzc/SpoofDPI`](https://github.com/xvzc/SpoofDPI). Armin does not copy or
bundle SpoofDPI source code, so SpoofDPI is a design reference rather than a
distributed dependency.
