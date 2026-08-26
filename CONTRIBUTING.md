# 기여하기

Armin은 작은 노력으로 큰 효과를 내는 변경을 선호합니다. 지금의 문제를 직접 풀고, 새
개념·규칙·의존성이 늘리는 이해 비용은 이득이 분명할 때만 감수합니다. 문서에는 독자의
판단을 바꾸는 정보만 남깁니다.

## 설계 경계

- 외부 top-level 이동은 HTTPS로 제한하고, 관찰 가능한 자동 이동은 다시 승인받습니다.
  WebView가 사전에 노출하지 않는 top-level POST 제출은 자동·사용자 제출을 구분하지 않고
  재승인 예외로 둡니다.
- WebView는 loopback 프록시가 준비된 뒤에만 통신합니다. 직접 연결 fallback이나 TLS
  복호화를 만들지 않습니다.
- 앱이 직접 요청하는 플랫폼 권한은 `INTERNET`만 유지하고, analytics나 원격 코드를 앱
  기능으로 추가하지 않습니다.

이 경계를 바꾸는 PR은 의도와 위험을 밝히고 새 동작을 테스트로 고정합니다.

## 개발과 확인

Nix 개발 환경은 `x86_64-linux`를 지원합니다.

```bash
nix develop
```

debug APK는 재현 가능한 서명을 위해 `config/reproducible-debug.keystore`를 사용하고
`dev.armin.debug`로 설치됩니다. 이 keystore와 비밀번호는 의도적으로 공개되어 있으며
release 서명에 사용해서는 안 됩니다.

작업 중에는 가장 가까운 테스트를 실행하고, 제출 전에는 변경에 해당하는 것만 확인합니다.

- 문서: `git diff --check`
- Kotlin: `./gradlew ci`
- WebView/UI: `./gradlew ci`와 연결된 기기의 `./gradlew :app:connectedDebugAndroidTest`
- Nix·빌드 설정: `nix flake check`

Kotlin/KTS 포맷은 `./gradlew spotlessApply`, Nix 포맷은 `nix fmt -- flake.nix nix`로
맞춥니다. 계측 테스트가 관련 동작을 건너뛰면 직접 확인하고 PR에 밝힙니다.

## 의존성

Gradle/Maven 의존성을 바꾸면 `nix/gradle-deps.json`을 갱신하고 diff를 확인합니다.

```bash
nix run .#update-deps
nix flake check
```

APK 의존성이 바뀌면 `legal/runtime-dependencies.txt`와 `THIRD_PARTY_NOTICES.md`를 맞추고,
필요한 `NOTICE`와 `LICENSES/`를 갱신합니다.

## 라이선스

기여물을 제출하면 이를 제공할 권리가 있고 프로젝트가 같은 MIT License로 배포할 수
있음을 전제로 합니다. 가져온 코드나 자료에는 출처와 라이선스를 남깁니다.
