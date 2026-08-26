# Armin에 기여하기

Armin은 기능 수보다 작은 공격 표면과 예측 가능한 탐색 정책을 우선합니다. 변경 전에는
아래 불변 조건과 검증 절차를 확인해 주세요.

## 설계 불변 조건

사용자 관점의 상세 동작과 제약은 README의 [주요 동작](README.md#주요-동작),
[보안 경계](README.md#보안-경계), [WebView 제약](README.md#webview-제약)을 기준으로 합니다.
변경할 때는 다음 보안 경계를 지킵니다.

- top-level 이동은 HTTPS와 사용자 승인을 요구하며 팝업·외부 앱으로 우회하지 않습니다.
- WebView는 loopback proxy가 준비된 뒤에만 통신하며 TLS 복호화나 직접 연결 fallback을
  만들지 않습니다.
- manifest 권한은 `INTERNET`만 유지하고 사이트 데이터를 임의로 삭제하지 않습니다.
- analytics, 원격 코드·필터 목록, production signing key 등 새 공격 표면은 별도 설계와
  검토 없이 추가하지 않습니다.

## 개발 환경

flake output은 현재 `x86_64-linux`만 지원합니다. 저장소 루트에서 개발 셸을 시작하세요.

```bash
nix develop
```

셸은 `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `JAVA_HOME`을 설정하고 다음 도구를 PATH에
제공합니다.

- OpenJDK 17
- Gradle과 Android SDK/adb
- ktfmt

SDK, JDK, Gradle과 지원 플랫폼은 꼭 필요한 경우가 아니면 현재 고정 버전을 유지합니다.
변경이 필요해지면 그때 영향 범위와 관련 문서 갱신을 함께 결정합니다.

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
nix/                  Nix 패키지 정의와 Gradle dependency capture
```

View와 Android callback에서 분리할 수 있는 판정은 JVM 테스트가 가능한 작은 Kotlin
클래스에 둡니다. 소켓 I/O와 DNS를 main thread에서 실행하지 말고, process-global WebView
설정에는 겹치는 Activity 수명주기를 고려합니다.

## 일상적인 작업 흐름

규칙은 간단합니다. 작업 중에는 가장 가까운 검사로 빠르게 반복하고, 제출 전에는 아래 표에서
변경에 해당하는 행만 확인합니다. 여러 유형에 걸친 변경은 해당 행을 함께 적용합니다.

| 변경 유형 | 빠른 반복 | 제출 전 최소 확인 |
| --- | --- | --- |
| 문서만 | 렌더링과 링크 확인 | `git diff --check` |
| Kotlin 로직 | 관련 JVM 테스트 하나 | `nix develop --command ./gradlew ci` |
| WebView/UI | 관련 JVM 또는 계측 테스트 | `ci`와 연결된 Android 기기의 관련 계측 테스트 |
| Nix·빌드 설정 | 바꾼 formatter 또는 Gradle task | `nix flake check 'path:.'` |
| Gradle/Maven 의존성 | 관련 빌드나 테스트 | 아래 갱신 절차와 `nix flake check 'path:.'` |

포맷은 Kotlin/Kotlin DSL에 `nix develop --command ./gradlew spotlessApply`를, Nix 파일에
`nix fmt -- flake.nix nix`를 사용합니다. 두 formatter는 Kotlin에 같은 ktfmt 버전과 스타일을
사용하므로 Kotlin 파일에 연달아 실행하지 않습니다.

빠른 반복 검사는 개발 중 피드백을 줄이기 위한 것이며 제출 전 확인을 대신하지 않습니다. 반대로
관련 없는 행의 명령까지 모두 실행할 필요는 없습니다. 릴리스 준비나 여러 유형에 걸친 변경은
해당하는 모든 행을 확인하는 것을 전체 검증으로 봅니다.

`quality`는 Spotless/ktfmt, Detekt, Android Lint, JVM 테스트, 런타임 의존성 inventory와
APK 법적 고지 검증에 의존합니다. 경고나 test skip을 단순히 baseline 또는 suppression으로
숨기지 말고 원인과 필요성을 기록하세요.

`.#...` 형태의 Git flake 입력은 Git이 아직 추적하지 않는 새 파일을 제외합니다. 검증에는
새 파일도 포함하는 `nix flake check 'path:.'`를 사용하세요. 이 명령은 기본 패키지도 빌드하므로
`nix build 'path:.#default'`는 결과 APK 링크가 직접 필요할 때만 사용합니다.

## CI 정책

CI의 검사 본체는 특정 호스팅 서비스의 workflow가 아니라 저장소 안의 Gradle task로
유지합니다. GitHub Actions는 실행 환경만 준비하고 다음과 같이 로컬에서도 실행할 수 있는
명령을 그대로 호출해야 합니다.

```bash
nix develop --command ./gradlew ci
```

`ci`는 PR마다 `spotlessCheck`, Detekt, Android Lint와 JVM 단위 테스트를 실행합니다. 검사
항목을 바꿀 때는 `ci` task를 기준으로 수정해 로컬과 GitHub의 동작이 달라지지 않게 합니다.
이 CI는 에뮬레이터나 실제 기기를 사용하지 않으므로, WebView·Activity 동작을 바꾼 제출자는
아래의 Android 계측 테스트와 필요한 수동 기기 점검을 별도로 수행하고 결과를 변경 설명에
남겨야 합니다.

## 테스트

작업 중에는 관련 테스트 하나만 빠르게 반복할 수 있습니다.

```bash
nix develop --command ./gradlew :app:testDebugUnitTest \
  --tests 'dev.armin.browser.UrlNormalizerTest'
```

WebView·Activity 동작을 바꿨다면 연결된 Android 기기 한 대를 지정해 계측 테스트를
실행합니다.

```bash
nix develop --command adb devices -l

nix develop --command env ANDROID_SERIAL=<adb-serial> \
  ./gradlew :app:connectedDebugAndroidTest
```

성공 표시와 함께 `skipped=0`인지 확인합니다. 기기나 System WebView 제약으로 관련 검사가
건너뛰어졌다면 그 변경만 수동으로 확인합니다. 수동 점검은 자동화하기 어려운 화면·키보드·
fullscreen·OEM WebView 동작과, proxy 실패 시 직접 연결로 우회하지 않는 보안 경계에
집중하고 기기, Android, System WebView 버전과 결과를 기록합니다. 개별 테스트와 fixture의
정확한 범위는 테스트 코드와 생성된 보고서를 기준으로 합니다.

## Gradle 또는 Maven 의존성 변경

버전 변경은 Android SDK/AGP/Gradle 호환성과 Nix hash를 함께 바꾸므로 의도적으로
진행합니다. 네트워크가 허용된 환경에서 capture manifest를 갱신한 뒤 diff를 검토하세요.

```bash
nix run .#update-deps
git diff -- nix/gradle-deps.json
nix flake check 'path:.'
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

## 변경 제출 전 확인

- 위의 보안 경계를 유지했는가
- 변경 유형별 검증표의 자동 검사와 필요한 기기 검증이 통과했는가
- production 의존성을 바꿨다면 inventory, 제3자 고지와 라이선스 원문을 갱신했는가

## 라이선스와 기여

기여를 환영합니다. 이 저장소에서 바꾸고 싶은 것이 있다면 정해진 형식에 구애받지 말고
issue를 열거나 PR을 보내 주세요.

Armin의 자체 소스와 문서는 `hooreique` 명의의 MIT License로 배포합니다. 기여물을
제출하면 본인이 해당 기여물을 제공할 권리가 있고, 프로젝트가 이를 같은 MIT License로
배포할 수 있음을 전제로 합니다. 제3자 코드를 복사하거나 filter list·remote script를
추가할 때는 출처와 적용 라이선스를 명시하고 필요한 고지 및 원문을 함께 갱신하세요. MIT는
이미 포함된 제3자 구성 요소의 별도 조건을 대체하지 않습니다.
