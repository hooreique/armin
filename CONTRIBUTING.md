# Armin에 기여하기

Armin은 기능 수보다 작은 공격 표면과 예측 가능한 탐색 정책을 우선합니다. 변경 전에는
아래 불변 조건과 검증 절차를 확인해 주세요.

## 설계 불변 조건

- UI는 단일 WebView와 하단 주소창 하나로 유지합니다.
- top-level 사용자 이동은 HTTPS만 허용합니다.
- 관찰 가능한 자동 main-frame redirect는 사용자 승인 전까지 차단합니다.
- 팝업, 새 창, 외부 앱 handoff 및 직접 연결 fallback을 만들지 않습니다.
- WebView 트래픽은 proxy override가 준비된 뒤에만 시작합니다.
- 로컬 프록시는 loopback에만 bind하고 TLS를 복호화하거나 인증서를 재서명하지 않습니다.
- manifest 권한은 특별한 근거가 없는 한 `INTERNET`만 유지합니다.
- 사이트 데이터 삭제, analytics, 원격 코드, production signing key를 추가하지 않습니다.
- 콘텐츠 차단 확장 지점은 유지하되 실제 필터 목록이나 차단 기능은 별도 설계 없이
  활성화하지 않습니다.

## 개발 환경

flake output은 현재 `x86_64-linux`만 지원합니다. 저장소 루트에서 개발 셸을 시작하세요.

```bash
nix develop
```

셸은 `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `JAVA_HOME`, `KOTLIN_LSP_DIR`을 설정하고 다음
도구를 PATH에 제공합니다.

- OpenJDK 17
- Gradle과 Android SDK/adb
- ktfmt
- JetBrains Kotlin LSP

Android Studio는 필수가 아니며, Neovim 자체도 dev shell에 포함하지 않습니다.

## 저장소 구조

```text
app/src/main/java/dev/armin/
  ui/       Activity, fullscreen, process-wide proxy session
  browser/  URL·navigation 정책, WebView 설정, 콘텐츠 차단 확장점
  proxy/    CONNECT parser, DNS, ClientHello parser/splitter, tunnel
app/src/test/         JVM 단위·loopback 통합 테스트
app/src/androidTest/  실제 WebView/Activity 계측 테스트
config/detekt/        정적 분석 설정
legal/                검증 대상 production dependency inventory
LICENSES/             배포하는 제3자 라이선스 원문
nix/                  Kotlin LSP 패키지와 Gradle dependency capture
```

View와 Android callback에서 분리할 수 있는 판정은 JVM 테스트가 가능한 작은 Kotlin
클래스에 둡니다. 소켓 I/O와 DNS를 main thread에서 실행하지 말고, process-global WebView
설정에는 겹치는 Activity 수명주기를 고려합니다.

## 일상적인 작업 흐름

1. 변경 범위를 작게 잡고 관련 회귀 테스트를 먼저 정합니다.
2. 구현 후 포맷을 적용합니다.
3. 전체 JVM 품질 gate와 Android test 컴파일을 실행합니다.
4. WebView·Activity 동작을 바꿨다면 연결된 Android 기기에서 계측 테스트를 실행합니다.
5. Nix 파일이나 최종 산출물에 영향을 주는 변경은 flake 검사와 APK build까지 확인합니다.

```bash
nix develop --command ./gradlew spotlessApply
nix develop --command ./gradlew quality :app:compileDebugAndroidTestKotlin
nix fmt
nix flake check
nix build .#default
```

`quality`는 Spotless/ktfmt, Detekt, Android Lint, JVM 테스트, 런타임 의존성 inventory와
APK 법적 고지 검증에 의존합니다. 경고나 test skip을 단순히 baseline 또는 suppression으로
숨기지 말고 원인과 필요성을 기록하세요.

`.#...` 형태의 Git flake 입력은 Git이 아직 추적하지 않는 새 파일을 제외합니다. 새 파일을
만든 작업 중 그 파일까지 포함해 Nix를 검증하려면 `nix flake check 'path:.'`와
`nix build 'path:.#default'`를 사용하고, 제출 전에는 `git status --short`에서 모든 새
소스와 테스트가 추적 대상인지 확인하세요.

## 테스트

### JVM 테스트

- URL 정규화와 HTTPS-only 정책
- navigation allowance, redirect pending 상태와 late callback race
- CONNECT 요청의 bounded incremental parsing
- 여러 TLS record/read에 걸친 ClientHello와 SNI 범위 parsing
- lossless split plan과 fallback
- 실제 loopback TLS handshake, 양방향 relay, timeout, half-close와 cleanup
- DNS cancellation과 `LocalConnectProxy` 수명주기

특정 테스트만 반복할 때도 마지막에는 전체 `quality`를 실행하세요.

```bash
nix develop --command ./gradlew :app:testDebugUnitTest \
  --tests 'dev.armin.browser.UrlNormalizerTest'
```

### Android 계측 테스트

먼저 adb가 같은 기기를 여러 transport alias로 보여주는지 확인하고, serial을 명시해 한
대에서만 실행합니다.

```bash
nix develop --command adb devices -l

nix develop --command env ANDROID_SERIAL=<adb-serial> \
  ./gradlew :app:connectedDebugAndroidTest
```

보고서는 `app/build/reports/androidTests/connected/debug/index.html`에 생성됩니다. 성공
여부뿐 아니라 skip 수와 기기의 System WebView feature 지원 여부도 확인하세요. feature
미지원으로 건너뛴 테스트에는 별도의 fallback 검증이 있어야 합니다.

`BrowserAcceptanceTest`는 외부 인터넷 없이 local replacement fixture를 사용합니다.
반면 `MainActivityTest`의 링크 보안 fixture는 실제 proxy 경로로 `https://example.com/`을
연 뒤 DOM을 주입하므로 기기의 인터넷 연결이 필요합니다. 연결 실패는 assume/skip으로
표시될 수 있으므로 전체 성공과 함께 `skipped=0`도 확인하세요. 테스트는 다른 사이트의
계정·쿠키나 기기 전체 앱 데이터를 지우지 않아야 합니다. Activity recreation, fullscreen,
back 처리와 저장소 보존은 이 계측 suite에서 검증합니다.

### 수동 기기 점검

WebView 또는 OEM 동작상 안정적으로 자동화하기 어려운 변경은 아래 항목을 확인하고 기기,
Android 버전, System WebView 버전과 결과를 변경 설명에 남깁니다.

1. 시스템 light mode에서도 새 실행 화면이 검고 빈 주소창만 보이며 웹 콘텐츠의
   `prefers-color-scheme`가 dark인지 확인합니다.
2. bare hostname, path/query 및 비표준 HTTPS port는 열리고 HTTP·외부 scheme은 막히는지
   확인합니다.
3. 일반 링크는 현재 창에서 열리고 `target=_blank`와 `window.open()`은 어디에도 열리지
   않는지 확인합니다.
4. HTTP 3xx, JavaScript `location`과 meta refresh 목적지가 주소창에 replace/focus되고
   Enter 승인 전에는 열리지 않는지 확인합니다.
5. fullscreen 영상이 sensor landscape immersive로 전환되고 첫 뒤로 가기에서 영상만
   닫히는지 확인합니다.
6. WebView history를 소진할 때까지 뒤로 이동한 다음 Activity가 종료되는지 확인합니다.
7. persistent cookie/localStorage가 앱 재시작 뒤 유지되지만 시작 화면은 빈 문서인지
   확인합니다.
8. 인증서 오류를 우회하지 않고, proxy override 미지원 시 직접 연결하지 않는지 봅니다.
9. 필요하면 loopback packet capture로 CONNECT, ClientHello split, relay와 cleanup을
   확인하고 민감한 URL query, cookie, body, TLS plaintext가 로그에 남지 않는지 봅니다.

## Gradle 또는 Maven 의존성 변경

버전 변경은 Android SDK/AGP/Gradle 호환성과 Nix hash를 함께 바꾸므로 의도적으로
진행합니다. 네트워크가 허용된 환경에서 capture manifest를 갱신한 뒤 diff를 검토하세요.

```bash
nix run .#update-deps
git diff -- nix/gradle-deps.json
nix flake check
nix build .#default
```

`update-deps`는 quality, APK 및 androidTest 컴파일 task graph에 필요한 Maven artifact를
기록합니다. 이 cache는 Nix build derivation 내부의 Gradle 단계를 오프라인으로 실행할 때
사용됩니다. dev shell에서 직접 실행하는 `./gradlew`에는 자동으로 연결되지 않으며, 새 빌드
호스트도 flake input과 Nix store 산출물을 내려받아야 할 수 있습니다.

production 의존성을 바꾸면 `legal/runtime-dependencies.txt`를 실제
`debugRuntimeClasspath`와 맞추고 `THIRD_PARTY_NOTICES.md`, `NOTICE`, `LICENSES/`도 함께
검토하세요. `verifyDebugRuntimeLicenseInventory`는 좌표의 추가·삭제와 고지 누락을,
`verifyDebugLegalAssets`는 canonical 법적 파일이 APK `assets/licenses/`에 그대로
포함됐는지를 검사합니다. 테스트 전용 의존성도 산출물에 들어가지 않는지 확인하고 해당
라이선스 조건을 변경 설명에 기록하세요.

## Kotlin LSP와 Neovim

사용자가 설치한 Neovim을 dev shell 도구와 함께 실행합니다.

```bash
nix develop --command nvim .
```

Neovim 0.11과 최근 `nvim-lspconfig`의 최소 설정 예시는 다음과 같습니다.

```lua
vim.lsp.config('kotlin_lsp', {
  cmd = { 'kotlin-lsp', '--stdio' },
  single_file_support = false,
  root_markers = { 'settings.gradle.kts', 'build.gradle.kts', '.git' },
})
vim.lsp.enable('kotlin_lsp')
```

Android Gradle project import는 Kotlin LSP에서 완전하지 않을 수 있습니다. 문제가 생기면
`:LspRestart kotlin_lsp` 후 Gradle `quality` 결과를 최종 진단으로 사용하세요.

## 변경 제출 전 확인

- 요청 범위를 넘어서는 UI·권한·네트워크 기능을 추가하지 않았는가
- 새 navigation 경로가 HTTPS-only, popup, redirect 정책을 우회하지 않는가
- 실패 시 proxy를 건너뛰고 직접 연결하지 않는가
- socket, executor, listener, script handler가 종료 후 남지 않는가
- URL query, cookie, body 또는 TLS plaintext를 로그에 남기지 않는가
- 새 코드에 단위·통합·계측 테스트 중 적절한 회귀 검증이 있는가
- `spotlessApply`, `quality`, androidTest 컴파일과 관련 기기 검증이 통과했는가
- Nix 입력을 바꿨다면 dependency capture, flake check와 최종 APK를 다시 만들었는가
- production 의존성을 바꿨다면 inventory, 제3자 고지와 라이선스 원문을 갱신했는가
- 변경 이유, 검증 명령, 기기 의존 결과와 남은 제약을 설명했는가

## 라이선스와 기여

Armin의 자체 소스와 문서는 `hooreique` 명의의 MIT License로 배포합니다. 기여물을
제출하면 본인이 해당 기여물을 제공할 권리가 있고, 프로젝트가 이를 같은 MIT License로
배포할 수 있음을 전제로 합니다. 제3자 코드를 복사하거나 filter list·remote script를
추가할 때는 출처와 적용 라이선스를 명시하고 필요한 고지 및 원문을 함께 갱신하세요. MIT는
이미 포함된 제3자 구성 요소의 별도 조건을 대체하지 않습니다.
