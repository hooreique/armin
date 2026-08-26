# Armin

Armin은 Android `WebView`의 HTTPS 트래픽을 앱 내부 loopback HTTP CONNECT 프록시로
보낸 뒤, 첫 TLS `ClientHello`의 SNI hostname 바이트를 여러 TCP write로 나누는 최소
단일 창 브라우저입니다. TLS를 복호화하지 않으며 VPN, root 권한, 사용자 CA가 필요하지
않습니다.

```text
주소창에서 HTTPS 목적지 승인
          ↓
Android WebView
          ↓ AndroidX WebKit ProxyController
127.0.0.1:<임시 포트> CONNECT proxy
          ↓ ClientHello SNI write 분할
원격 HTTPS 서버
```

이 프로젝트의 목표는 일반 브라우저를 대체하는 것이 아니라, 작은 UI와 명시적인 탐색
정책으로 WebView 트래픽에 SpoofDPI 방식의 분할을 적용하는 것입니다.

## 주요 동작

- 화면에는 검은 WebView와 하단 주소창 하나만 표시합니다.
- `example.com/path`처럼 입력하면 `https://example.com/path`로 정규화합니다.
- 주소창 입력과 외부 네트워크 top-level 이동은 HTTPS만 허용하고 HTTP, 로컬 파일 및
  외부 앱 scheme은 차단합니다. 앱의 빈 시작 문서인 `about:blank`와 HTTPS 문서가 만든
  `blob:https://...` URL만 내부 문서로 제한해 허용합니다.
- 사용자가 주소창에서 승인한 URL과 직접 누른 일반 HTTPS 링크만 이동합니다.
- WebView가 관찰할 수 있는 자동 main-frame redirect는 목적지를 주소창에 제시하고
  사용자가 다시 Enter를 누를 때까지 차단합니다.
- `target="_blank"`, `window.open()` 등 팝업과 새 창을 열지 않습니다.
- 다크 앱 테마, HTML5 custom-view fullscreen, landscape immersive 모드를 지원합니다.
- 쿠키, Web Storage, IndexedDB, Service Worker 저장소와 캐시는 WebView의 정상 수명에
  따라 보존하며 앱이 임의로 지우지 않습니다.
- 콘텐츠 차단은 비활성 상태입니다. 향후 필터 엔진을 연결할 no-op 요청 판정 및
  document-start 주입 지점만 제공합니다.

탭, 앞으로 가기, 검색 엔진, 북마크, 다운로드·업로드 UI, 외부 앱 연동, VPN, DoH,
QUIC 프록시, TLS MITM, Picture-in-Picture, 광고 차단 목록 및 production 배포는
지원하지 않습니다.

## 요구 환경

- 실행 기기: Android 10(API 29) 이상
- System WebView: AndroidX WebKit `PROXY_OVERRIDE` 지원 필요
- 빌드 호스트: flake 기능을 사용할 수 있는 Nix, `x86_64-linux`
- SDK: `minSdk 29`, `compileSdk 37`, `targetSdk 37`

프록시 override를 지원하지 않거나 프록시 시작에 실패하면 직접 연결로 우회하지 않고
주소창을 비활성화한 채 오류를 표시합니다.

## 빌드와 설치

Nix가 JDK 17, Gradle, Android SDK, adb, ktfmt 및 Kotlin LSP를 고정합니다.

```bash
nix build .#default
nix develop --command adb install result/apk/armin.apk
```

`result/apk/armin.apk`는 설치 가능한 debug APK입니다. Android 빌드 도구의 개발용 키로
서명되며 Play Store나 공개 배포용 산출물이 아닙니다. 다른 빌드에서 생성한 APK와 서명이
달라 교체 설치가 실패하면, 기기에 남아 있는 개발용 앱의 데이터 보존 필요 여부를 먼저
확인한 뒤 기존 앱을 제거해야 합니다.

연결된 기기에 Gradle로 바로 설치할 수도 있습니다.

```bash
nix develop --command ./gradlew :app:installDebug
```

## 사용법

1. 앱을 열면 빈 검은 페이지와 주소창이 나타납니다.
2. 주소창에 `example.com`, `example.com/path` 또는 완전한 `https://` URL을 입력합니다.
3. 키보드의 Go 또는 Enter를 누릅니다.
4. 자동 redirect가 차단되면 주소창에 바뀐 목적지가 표시됩니다. 내용을 확인하고 다시
   Enter를 눌러야 이동합니다.
5. Android 뒤로 가기는 fullscreen 영상을 먼저 닫고, 그다음 WebView history를
   이동하며, history가 없으면 Activity를 종료합니다.

## 보안 경계

- 로컬 프록시는 `127.0.0.1`의 OS 지정 임시 포트에만 bind합니다.
- CONNECT 이후 TLS는 그대로 유지하며 인증서 오류를 우회하지 않습니다.
- 앱 manifest가 직접 요청하는 플랫폼 권한은 `INTERNET`뿐입니다.
- cleartext top-level 이동, mixed content, 임의 파일 접근, 위치·카메라·마이크 권한 및
  범용 JavaScript bridge를 허용하지 않습니다. 웹페이지 자체의 JavaScript와 별개로,
  앱이 원격 script를 내려받아 앱 로직이나 native code로 실행하는 기능도 없습니다.
- DNS와 socket 작업에는 timeout과 bounded worker를 적용하고, 마지막 Activity가 끝나면
  proxy override와 앱 소유 자원을 정리합니다.

SNI 바이트는 암호화하거나 제거하지 않고 write 경계만 나눕니다. 따라서 이 앱은
익명성·추적 방지 또는 모든 네트워크의 DPI 우회를 보장하지 않습니다.

## WebView 제약

- redirect 차단은 `shouldOverrideUrlLoading()` 등 WebView가 노출하는 main-frame callback
  범위에 한정됩니다. 특히 POST 이동이나 하위 리소스 redirect를 모두 관찰할 수 없습니다.
- 지원 WebView에서는 isolated document-start world가 실제 HTML anchor activation을
  검증해 현재 창에 다시 발행합니다. 필요한 WebView 기능 중 하나라도 없으면
  `hasGesture`를 사용하는 호환 fallback으로 일반 링크를 허용하므로 사용자 링크와
  page-initiated 이동의 구분이 덜 정밀할 수 있습니다.
- redirect metadata가 없는 WebView에서는 앱이 발급한 탐색 상태와 gesture를 바탕으로
  보수적으로 판정합니다.
- 자동 영상 fullscreen, algorithmic darkening 및 document-start 주입은 사이트 정책과
  WebView 기능 지원에 따라 축소될 수 있습니다.
- 사이트 데이터는 앱이 자동 삭제하지 않지만 사이트 만료, WebView quota, 저장공간 부족,
  OS·사용자의 데이터 삭제 및 앱 제거까지 우회하지 않습니다.
- WebView HTTPS를 복호화하지 않으므로 프록시는 URL path, query, response header/body를
  검사하거나 수정할 수 없습니다.

## 개발

개발 셸, 코드 품질 검사, 계측 테스트, Kotlin LSP 및 의존성 갱신 방법은
[`CONTRIBUTING.md`](CONTRIBUTING.md)를 참고하세요.

## 라이선스와 제3자 고지

Armin이 자체 작성한 소스와 문서는 `hooreique`가 저작권을 보유하며
[`LICENSE`](LICENSE)의 MIT License로 배포합니다. 포함된 의존성 및 도구 생성 코드는
각 원 저작물의 라이선스를 그대로 유지합니다. 정확한 런타임 목록과 고지는
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), 원문은 [`LICENSES/`](LICENSES/)에서
확인할 수 있습니다. 빌드는 이 파일들을 APK의 `assets/licenses/`에도 넣고 누락이나
의존성 목록 불일치를 `quality`에서 검사합니다.

ClientHello의 SNI 구간을 여러 write로 전송하는 발상은 Apache-2.0 프로젝트
[`xvzc/SpoofDPI`](https://github.com/xvzc/SpoofDPI)를 참고했습니다. 현재 proxy parser와
tunnel은 독립적으로 구현했으며 해당 저장소의 코드를 복사하지 않았습니다.

Nix 개발 환경이 내려받는 JetBrains Kotlin LSP standalone 배포물에는 JetBrains 조건이
적용되는 bundled component가 포함될 수 있습니다. 이는 개발 도구이며 APK에는 포함되지
않습니다. uBlock Origin 코드, uAssets 또는 제3자 filter list도 현재 포함하지 않습니다.
