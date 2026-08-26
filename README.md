# Armin

Armin은 Android WebView를 [xvzc/spoofdpi](https://github.com/xvzc/spoofdpi) 방식으로
래핑한 미니멀 브라우저입니다.

## Getting Started

### Prerequisites

- flakes를 활성화한 Nix가 설치된 `x86_64-linux` 시스템
- Wi-Fi 디버깅을 지원하는 Android 11 이상의 실기기
- 같은 Wi-Fi 네트워크에 연결된 개발 시스템과 Android 기기

### 1. APK 빌드

저장소 루트에서 다음 명령을 실행합니다.

```bash
nix build 'path:.#default'
```

빌드된 debug APK는 `result/apk/armin.apk`에 생성됩니다.

### 2. 기기 페어링 및 연결

Android 기기에서 **개발자 옵션 > 무선 디버깅**을 켭니다. 개발자 옵션이 보이지 않으면
**설정 > 휴대전화 정보 > 소프트웨어 정보**에서 **빌드 번호**를 7번 탭해 먼저 활성화합니다.

**무선 디버깅 > 페어링 코드로 기기 페어링**을 열고 표시된 IP 주소, 페어링 포트,
페어링 코드를 사용합니다.

```bash
nix develop
adb pair <DEVICE_IP>:<PAIRING_PORT>
```

요청이 나오면 기기에 표시된 페어링 코드를 입력합니다. 그다음 무선 디버깅 메인 화면의
**IP 주소 및 포트**에 표시된 연결 포트로 접속합니다.

```bash
adb connect <DEVICE_IP>:<DEBUG_PORT>
adb devices
```

페어링 포트와 연결 포트는 서로 다를 수 있습니다. `adb devices`에 기기가 `device` 상태로
표시되면 연결된 것입니다.

### 3. 설치 및 실행

```bash
adb install -r result/apk/armin.apk
adb shell am start -n dev.armin/.ui.MainActivity
```

이후에는 기기의 앱 목록에서 Armin을 직접 실행할 수 있습니다.
