# Armin

Armin은 Android `WebView`의 HTTPS 트래픽을 앱 내부 loopback HTTP CONNECT 프록시로
보내고, 첫 TLS `ClientHello`의 SNI hostname 구간을 작은 TCP write로 나누는 최소 단일 창
브라우저입니다. TLS를 복호화하지 않으며 VPN, root 권한, 사용자 CA가 필요하지 않습니다.

화면은 검은 WebView와 하단 주소 입력창 하나뿐입니다. 주소창에서 승인한 HTTPS 주소와
사용자가 직접 누른 HTTPS 링크만 탐색하고, WebView가 관찰 가능한 자동 main-frame
redirect는 주소창에 목적지만 제시한 채 차단합니다. 팝업과 새 창은 열지 않습니다.

## 지원 범위

- HTTPS `CONNECT host:port` 터널과 TLS ClientHello SNI 분할
- JavaScript, DOM storage, 쿠키, IndexedDB, Service Worker 및 WebView 캐시의 정상적인 수명
- WebView가 노출하는 main-frame 탐색에 대한 HTTPS-only/redirect 승인 정책
- HTML5 custom-view fullscreen, sensor landscape, immersive system bars
- 시스템 설정과 무관한 앱 다크 테마 및 지원 WebView의 algorithmic darkening
- 향후 콘텐츠 차단을 위한 no-op 네트워크 판정 및 document-start bootstrap 엔트리포인트

탭, 앞으로 가기, 검색, 다운로드/업로드 UI, 외부 앱 연동, 광고 차단 목록, VPN, DoH,
QUIC 프록시, TLS MITM, Picture-in-Picture 및 production 배포는 지원하지 않습니다. 앱이
사이트 데이터를 지우는 기능도 제공하지 않습니다.

## SDK와 도구

- `minSdk`: 28
- `compileSdk`: 37
- `targetSdk`: 37
- UI: Kotlin + Android Views + AndroidX Activity/WebKit
- 품질 검사: Spotless/ktfmt, Detekt, Android Lint, JUnit

Nix가 Android SDK, JDK, Gradle, adb, ktfmt 및 JetBrains 공식 Kotlin LSP를 고정합니다.
flake가 활성화된 Nix 2.18 이상이 필요하며 Android Studio나 Mason은 필요하지 않습니다.
현재 flake output은 `x86_64-linux`만 제공합니다.

```bash
nix develop
./gradlew quality
```

개발 셸은 `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `JAVA_HOME`, `KOTLIN_LSP_DIR`을 설정합니다.
Neovim은 셸에 포함하지 않으므로 사용자가 이미 설치한 Neovim을 그대로 실행합니다.

```bash
nix develop --command nvim .
```

Neovim 0.11과 최근 `nvim-lspconfig`에서는 다음 최소 설정을 사용할 수 있습니다.

```lua
vim.lsp.config('kotlin_lsp', {
  cmd = { 'kotlin-lsp', '--stdio' },
  single_file_support = false,
  root_markers = { 'settings.gradle.kts', 'build.gradle.kts', '.git' },
})
vim.lsp.enable('kotlin_lsp')
```

Android Gradle project import는 Kotlin LSP에서 아직 실험적입니다. import가 불완전해도
서버 자체가 종료될 필요는 없으며, 문제가 생기면 `:LspRestart kotlin_lsp` 후 Gradle
`quality` 결과를 최종 진단으로 사용하십시오.

## 포매팅과 검사

```bash
nix fmt
nix flake check

nix develop --command ./gradlew spotlessApply
nix develop --command ./gradlew spotlessCheck
nix develop --command ./gradlew detekt
nix develop --command ./gradlew lintDebug
nix develop --command ./gradlew test
nix develop --command ./gradlew quality
```

`nix fmt`는 Nix 파일과 Kotlin/Gradle Kotlin DSL 파일을 각각 고정된 nixfmt와 ktfmt로
처리합니다. `quality`는 포맷, Detekt, Android Lint와 JVM 단위 테스트를 모두 실행합니다.
기기 의존 동작은 `androidTest`와 아래 수동 점검 절차로 보완합니다.

Gradle plugin이나 Maven dependency를 바꾼 뒤에는 네트워크가 허용된 환경에서 cache
manifest를 갱신하고 변경된 `nix/gradle-deps.json`을 커밋합니다.

```bash
nix run .#update-deps
```

그 외의 `nix build`와 flake 검사는 해당 manifest를 사용해 외부 네트워크 없이 실행됩니다.

## 빌드와 설치

```bash
nix build .#default
adb install result/apk/armin.apk
```

`result/apk/armin.apk`는 Android 개발 도구의 개발용 debug key로 서명된 설치 가능한 APK
입니다. 이 키는 production key가 아니며 이 APK는 Play Store나 공개 배포용이 아닙니다.
Nix sandbox마다 생성되는 debug key의 신원은 고정하지 않으므로, 다른 derivation에서 만든 APK로
교체할 때 서명이 달라 `adb install -r`가 실패하면 기존 개발용 앱을 제거한 뒤 설치해야 합니다.

연결된 개발 기기에 바로 설치할 수도 있습니다.

```bash
nix develop --command ./gradlew :app:installDebug
```

## 동작과 보안 경계

프록시는 `127.0.0.1`의 OS 지정 임시 포트에만 bind합니다. 앱은 AndroidX WebKit
`ProxyController` 지원을 확인하고 override가 성공한 뒤 탐색을 허용합니다. 지원하지 않는
WebView나 프록시 시작 실패를 직접 연결로 조용히 우회하지 않습니다. CONNECT 뒤의 첫 TLS
메시지는 제한된 크기까지만 버퍼링하고 SNI를 찾으면 hostname 바이트를 분할합니다. 파싱할
수 없는 첫 메시지는 원본 그대로 전달하는 일관된 fallback을 사용합니다. 이후 바이트는
양방향으로 변경 없이 전달됩니다.

WebView proxy override는 process-global이므로 겹쳐 존재하는 Activity들이 하나의 ref-counted
session을 공유합니다. 마지막 lease가 끝난 뒤에만 override와 listener를 정리해 이전 Activity가
새 Activity의 설정을 지우는 race를 막습니다. DNS 조회는 별도의 bounded executor와 connect
deadline 안에서 실행하므로 resolver가 멈추더라도 CONNECT worker 전체가 무한히 잠기지 않습니다.

소스 manifest가 직접 요청하는 플랫폼 권한은 `INTERNET`뿐입니다. 최종 merged manifest에는
AndroidX가 동적 receiver 보호용 self-signature permission과 `DUMP` 권한으로 보호된 profile
installer receiver를 추가하지만 앱이 추가 위험 권한을 요청하지는 않습니다. cleartext
main-frame navigation, mixed content, 임의
파일 접근, 카메라, 마이크, 위치, SSL 오류 우회 및 범용 JavaScript bridge는 허용하지
않습니다. Debug build에서만 WebView debugging을 켭니다. OS backup/기기 이전에서는 WebView와
앱 데이터를 제외합니다. 정상 종료 시 proxy와 실행기는 정리하지만 쿠키나 WebView 저장소
삭제 API를 호출하지 않습니다.

## 알려진 제약

- `shouldOverrideUrlLoading()`은 모든 탐색, 특히 POST 탐색에 호출된다고 보장되지 않습니다.
  redirect 차단은 WebView callback으로 관찰되는 main-frame 자동 이동에 한정됩니다.
- 지원 WebView에서는 격리된 JavaScript world의 document-start listener가 실제 trusted anchor
  click을 먼저 취소하고 선언된 HTTPS href를 좁은 native message 경로로 재발행합니다. 이 기능이
  없으면 racy한 `hasGesture`/hit-test를 신뢰하지 않고 텍스트·이미지 링크 모두 pending 목적지로
  제시해 Enter 승인을 요구할 수 있습니다.
- 격리 listener는 pointer/key activation 때의 href·target·modifier 상태와 click 때의 값을
  비교합니다. 링크 gesture의 page event handler가 먼저 POST나 script navigation을 실행하지
  못하도록 anchor 관련 이벤트 전파를 차단하고 선언 href만 native로 재발행합니다. 따라서
  JavaScript click handler, client-side router 또는 원래 referrer에 의존하는 링크는 일반
  브라우저와 다르게 동작할 수 있습니다.
- native form/media control, SVG/MathML link 또는 확인 가능한 author Shadow DOM host를 거쳐
  활성화되는 링크는
  실제 browser activation target을 격리 listener가 확정할 수 없으므로 직접 승인하지 않습니다.
  닫힌 Shadow root를 표준 `span`/`div` 등에 붙이거나 CSS activation pseudo-element가 event
  target으로 retarget되는 경우에는 외부 listener가 내부 target을 볼 수 없어 바깥 HTML anchor의
  선언 href를 재발행할 수 있습니다. 이는 페이지가 구성한 링크 내부의 Web DOM 한계이며,
  일반적인 텍스트·이미지 링크 호환성을 보존하기 위해 해당 표준 wrapper를 일괄 차단하지
  않습니다.
- HTTPS를 복호화하지 않으므로 proxy는 응답의 `Location`, 전체 URL path, response header나
  body를 검사할 수 없고 subresource redirect를 완전히 차단할 수 없습니다.
- isolated-world/document-start API나 redirect 식별 feature가 없는 WebView는 보수적인 degraded
  policy를 사용합니다. 사이트 동작과 WebView 구현에 따라 일부 탐색은 구분이 불완전할 수 있습니다.
- 자동 영상 fullscreen은 사이트의 user-activation 정책과 cross-origin iframe에 의해 막힐
  수 있습니다. 사이트가 요청한 custom-view fullscreen은 정상 처리합니다.
- algorithmic darkening은 WebView와 사이트 지원에 좌우됩니다. 임의 CSS 반전으로 콘텐츠를
  훼손하지 않습니다.
- 영속 쿠키, localStorage, IndexedDB 등은 앱이 자동 삭제하지 않지만 사이트 만료 정책,
  WebView quota, 저장공간 부족, OS/사용자의 데이터 삭제 및 앱 제거까지 우회하지 않습니다.
- Service Worker request API는 요청을 시작한 WebView를 알려주지 않습니다. 여러 Activity가
  겹치는 동안 Service Worker 필터 문맥은 보수적으로 top-level URL/host 없이 전달됩니다.
- Android 계측 테스트에는 WebView가 포함된 emulator 또는 실제 기기가 필요합니다.

## 수동 검증 요약

1. 시스템을 light mode로 둔 뒤 새로 실행하여 검은 빈 문서와 빈 하단 주소창만 확인하고,
   테스트 페이지의 `matchMedia('(prefers-color-scheme: dark)').matches`가 `true`인지 봅니다.
2. `example.com`, path/query, 비표준 HTTPS port가 열리고 HTTP·외부 scheme이 막히는지 봅니다.
3. 일반 텍스트·이미지 링크는 현재 창에서 열리고 `target=_blank`와 `window.open()`은 아무 창도
   열지 않는지 확인합니다. 격리 실행 world 미지원 WebView에서는 링크가 주소창 승인으로
   전환되는지도 확인합니다.
4. 로컬 fixture의 HTTP 3xx, `location` 변경, meta refresh 목적지가 로드되지 않고 주소창을
   replace/focus하며 Enter를 눌러야만 열리는지 확인합니다.
5. fullscreen 영상을 재생해 landscape immersive 전환과 첫 뒤로 가기의 영상 종료를 봅니다.
6. 같은 창 링크를 두 번 이동한 뒤 OS 뒤로 가기가 WebView history를 역순으로 탐색하고,
   history가 소진된 다음 뒤로 가기에서 Activity가 종료되는지 확인합니다.
7. persistent cookie/localStorage를 기록한 뒤 앱을 다시 실행해 값은 유지되되 시작 화면은
   빈 검은 문서인지 확인합니다.
8. 만료되거나 신뢰되지 않는 인증서 페이지가 우회되지 않는지, proxy override를 제공하지 않는
   WebView에서는 오류 Toast 뒤 주소창이 비활성 상태로 남아 직접 연결하지 않는지 확인합니다.
9. proxy 통합 테스트와 필요시 loopback packet capture로 CONNECT, 분할 후 handshake, 데이터
   relay와 cleanup을 확인합니다. 앱이 query, cookie, body 또는 TLS plaintext를 로그로 남기지
   않는지도 확인합니다.

## 설계 참고와 라이선스

SNI를 포함한 ClientHello를 여러 write로 전송하는 발상은 Apache-2.0 프로젝트
[`xvzc/spoofdpi`](https://github.com/xvzc/SpoofDPI)를 참고했습니다. 현재 프록시 parser와
tunnel 구현은 요구사항에 맞춰 독립적으로 작성했으며 해당 프로젝트 코드를 복사하지
않았습니다.

Kotlin LSP 소스 저장소는 Apache-2.0이지만 flake가 내려받는 JetBrains 공식 standalone
배포물에는 별도 JetBrains 조건이 적용되는 bundled component도 포함됩니다. 이는 개발 도구일
뿐 APK에는 포함되지 않으며, 배포물을 재배포하기 전에는 해당 조건을 별도로 확인해야 합니다.

광고 차단은 현재 활성화되어 있지 않으며 uBlock Origin 코드, uAssets 또는 제3자 filter
list도 포함하지 않습니다. 향후 도입 전 각 GPL-3.0/목록 라이선스와 배포 의무를 별도로
검토해야 합니다.
