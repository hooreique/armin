# Armin

Armin은 Android `WebView`의 HTTPS 트래픽을 앱 안의 loopback HTTP CONNECT 프록시로
보내고, 인식 가능한 첫 TLS `ClientHello`의 SNI를 여러 TCP write로 나누는 작은 단일 창
브라우저입니다.

```text
WebView → 127.0.0.1 CONNECT proxy → SNI write 분할 → HTTPS 서버
```

TLS를 복호화하거나 인증서 오류를 우회하지 않으며 VPN, root 권한, 사용자 CA가 필요하지
않습니다.

## 동작

주소창에 `example.com/path` 또는 `https://example.com/path`를 입력하고 Go나 Enter를
누릅니다. 직접 누른 HTTPS 링크는 열고, WebView가 감지한 자동 main-frame 이동은 새
목적지를 주소창에서 다시 승인할 때까지 막습니다.

## 요구 환경

- Android 10(API 29) 이상
- AndroidX WebKit `PROXY_OVERRIDE`를 지원하는 System WebView
- 빌드 시 Nix flakes를 사용할 수 있는 `x86_64-linux`

프록시를 시작하거나 WebView에 적용하지 못하면 직접 연결로 우회하지 않습니다.

## 빌드와 설치

```bash
nix build 'path:.#default'
nix develop --command adb install result/apk/armin.apk
```

`result/apk/armin.apk`는 debug APK입니다.

## 보안 경계

프록시는 `127.0.0.1`의 임시 포트에만 bind하며 앱 manifest가 직접 요청하는 플랫폼
권한은 `INTERNET`뿐입니다.

Armin은 SNI 바이트를 바꾸거나 숨기지 않고 여러 write로 나눕니다. 익명성이나 모든
네트워크에서의 DPI 우회를 보장하지 않습니다.

## 개발과 라이선스

개발 절차는 [`CONTRIBUTING.md`](CONTRIBUTING.md)에 있습니다. Armin은
[`LICENSE`](LICENSE)의 MIT License로 배포하며, 제3자 고지는
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)에 있습니다.
