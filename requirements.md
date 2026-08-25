# Android SpoofDPI Web Browser 요구사항

## 1. 문서 목적과 우선순위

이 문서는 새 빈 Android 프로젝트를 구현하는 에이전트에게 전달할 단일 요구사항 문서다.

제품은 **Android 네이티브 단일 창 WebView 브라우저**다. WebView의 HTTPS 트래픽을 앱 내부의 로컬 프록시로 보내 TLS `ClientHello`를 SpoofDPI와 유사한 방식으로 분할 전송한다.

요구사항 우선순위는 다음과 같다.

- **MUST**: 반드시 구현
- **SHOULD**: 특별한 이유가 없으면 구현
- **MAY**: 필요할 때만 구현

---

## 2. 핵심 목표

다음 흐름을 제공한다.

```text
사용자가 하단 주소창에 example.com/path 입력
        ↓ Enter / IME_ACTION_GO
앱이 https://example.com/path 로 정규화
        ↓
Android WebView
        ↓ AndroidX WebKit ProxyController
127.0.0.1:<ephemeral-port> 로컬 HTTP CONNECT 프록시
        ↓ TLS ClientHello 분할
원격 HTTPS 서버
```

이 앱은 일반 브라우저가 아니다. 페이지를 여는 데 필요한 최소 기능만 제공한다.

---

## 3. 사용자 인터페이스

### 3.1 화면 구성

화면에는 다음 두 요소만 있어야 한다.

1. 화면 대부분을 차지하는 `WebView`
2. 화면 하단에 고정된 주소 입력창 하나

추가 툴바, 메뉴, 버튼, 탭 바, 로고, 검색 엔진, 최근 방문 목록은 만들지 않는다. 주소창은 시스템 내비게이션 바와 겹치지 않도록 하단 inset을 반영한다.

### 3.2 시작 페이지와 재시작 동작

- 앱을 새로 실행할 때마다 시작 페이지를 표시해야 한다.
- 여기서 새 실행은 Activity의 저장 상태나 이전 WebView 탐색 상태를 복원하지 않고 새 브라우징 세션 화면을 구성하는 앱 프로세스 시작 또는 런처를 통한 새 루트 Activity 실행을 뜻한다.
- 시작 페이지는 네트워크 문서나 별도 HTML 콘텐츠가 아니라 아무 요소도 없는 완전한 검은 화면이어야 한다.
- WebView와 그 컨테이너의 배경색을 모두 `#000000`으로 지정하고, 초기 문서는 검은 배경의 내부 빈 문서(예: 안전하게 구성한 `about:blank`)로 처리한다.
- 시작 시 네트워크 페이지를 자동으로 열지 않는다.
- 이전에 열었던 URL이나 WebView 탐색 상태를 복원하지 않는다.
- 주소창은 비어 있어야 하며 포커스를 주는 것은 허용한다.
- 회전이나 일시적인 Activity 재생성과 같이 사용자가 앱을 종료하지 않은 구성 변경은 현재 페이지를 유지해도 된다. 다만 앱을 명시적으로 닫은 뒤 다시 실행했을 때는 반드시 검은 시작 페이지에서 시작한다.

### 3.3 주소창 동작

사용자는 스킴을 제외한 주소를 입력한다.

```text
example.com
example.com/path
example.com:8443/path?q=1
```

Enter 또는 키보드의 `Go` 액션 입력 시 다음 순서로 처리한다.

1. 앞뒤 공백을 제거한다.
2. 빈 문자열이면 아무 동작도 하지 않는다.
3. 스킴이 없으면 `https://`를 붙인다.
4. 최종 URL의 스킴이 `https`인지 검증한다.
5. 유효하면 현재 WebView에서 로드한다.
6. 유효하지 않거나 다른 스킴이면 로드하지 않는다.

정규화 규칙:

- `example.com` → `https://example.com`
- `example.com/a` → `https://example.com/a`
- `https://example.com`은 허용한다.
- `http://`, `file://`, `content://`, `intent://`, `javascript:`, `data:`, `mailto:`, `tel:` 등은 사용자 이동 대상으로 허용하지 않는다.
- 잘못된 주소는 영구적인 추가 UI 없이 간단한 인페이지 오류나 짧은 Toast로 알릴 수 있다.

사용자가 명시적으로 요청한 페이지 이동이 완료되면 주소창에 현재 HTTPS URL을 표시하되 `https://`는 생략한다. 사용자가 주소창을 직접 편집 중이거나 차단된 리다이렉트 목적지가 주소창에 제시된 상태에서는 페이지 이벤트가 입력 내용을 덮어쓰지 않아야 한다.

### 3.4 단일 창 정책

- 탭을 지원하지 않는다.
- 새 browsing context를 요구하지 않는 일반 링크는 현재 WebView에서 연다.
- `target="_blank"`, `window.open()`, JavaScript 팝업 등 새 browsing context를 요구하는 모든 팝업은 항상 차단한다.
- 팝업 목적지를 현재 WebView로 대신 열지 않는다.
- 팝업 차단 알림, Toast, 배지, 로그 UI 등 사용자 알림은 제공하지 않아도 된다.
- `WebSettings.setSupportMultipleWindows(true)`로 새 창 요청이 현재 WebView의 top-level navigation으로 바뀌지 않게 한 뒤 `WebChromeClient.onCreateWindow()`에서 항상 `false`를 반환한다. `setJavaScriptCanOpenWindowsAutomatically(false)`도 적용한다. 사이트별 팝업 예외 기능은 만들지 않는다.
- `setSupportMultipleWindows(false)`만으로 팝업을 막았다고 간주하지 않는다. Android WebView는 이 상태에서 `window.open()`과 `target="_blank"`를 현재 WebView의 top-level navigation으로 바꾸기 때문이다.
- 외부 기본 브라우저, 두 번째 WebView, 별도 Activity를 열지 않는다.
- 앞으로 가기 기능과 UI를 제공하지 않는다.

### 3.5 리다이렉트 차단과 수동 승인

- 앱이 관찰할 수 있는 **모든 main-frame 자동 리다이렉트**는 목적지의 스킴이나 도메인이 같더라도 차단한다.
- 차단 대상에는 HTTP `3xx` 리다이렉트, JavaScript의 `location` 변경, meta refresh 및 직접적인 사용자 탐색으로 볼 수 없는 기타 page-initiated main-frame 이동이 포함된다.
- 사용자가 주소창에서 Enter/`Go`로 요청한 최초 URL과 사용자가 현재 페이지에서 직접 누른 일반 링크 이동은 리다이렉트로 취급하지 않는다. 그 이동 이후 발생한 자동 목적지 변경부터 차단한다.
- 리다이렉트를 감지하면 WebView가 목적지를 로드하지 않도록 해당 navigation을 취소한다. 현재 표시 중인 문서는 가능한 범위에서 그대로 유지한다.
- 동시에 리다이렉트 목적지 URL로 주소창 내용을 **replace**하고 주소창에 포커스를 이동한다. 기존 텍스트를 이어 붙이지 않는다.
- 목적지는 사용자가 실제 이동 대상을 판단할 수 있도록 path, query 및 fragment를 포함해 표시한다. 기존 주소 표시 규칙에 따라 HTTPS의 `https://`는 생략할 수 있다.
- 목적지가 HTTP 또는 허용되지 않는 다른 스킴이어도 시도된 목적지를 주소창에 보여줄 수 있지만, 사용자가 Enter를 눌러도 기존 HTTPS 전용 검증 규칙에 따라 로드하지 않는다.
- 목적지 제시 외에 별도 경고, Toast, 팝업 또는 차단 안내 문구는 필요 없다. 주소창의 값 변경과 포커스 이동 자체가 유일한 안내다.
- 사용자가 제시된 HTTPS 목적지에서 Enter/`Go`를 누르면 이를 새로운 명시적 탐색으로 처리하고 해당 URL을 로드한다. 그 페이지가 다시 다른 곳으로 리다이렉트하면 같은 절차를 반복한다.
- 주소창에 제시된 목적지는 아직 로드된 현재 페이지 URL이 아니라 사용자가 승인할 수 있는 **pending navigation**이다. BrowserController는 현재 문서 URL과 pending URL을 별도 상태로 관리해야 한다.
- redirect 처리 직후의 `onPageFinished()`, `doUpdateVisitedHistory()` 등 페이지 이벤트가 pending URL을 현재 URL로 되돌리거나 포커스를 WebView로 빼앗지 않아야 한다.

구현 방향:

- main-frame `WebResourceRequest`에 대해 `shouldOverrideUrlLoading()`에서 `isRedirect`, `hasGesture` 및 앱이 발급한 명시적 navigation 상태를 함께 판정한다. 차단 시 `true`를 반환하고 같은 callback 안에서 목적지를 `loadUrl()`로 다시 호출하지 않는다.
- AndroidX WebKit의 `WEB_RESOURCE_REQUEST_IS_REDIRECT` 및 `SHOULD_OVERRIDE_WITH_REDIRECTS` 지원 여부를 확인하고 가능한 경우 redirect 식별 정보를 사용한다.
- `isRedirect`가 제공되지 않는 WebView에서는 명시적으로 허용한 주소창 탐색/직접 gesture 탐색 이외의 page-initiated main-frame navigation을 보수적으로 자동 이동으로 취급해 차단한다.
- JavaScript 및 meta refresh가 main-frame navigation callback으로 노출되는 경우 같은 정책을 적용한다. 이를 위해 페이지에 광범위하고 위험한 native JavaScript bridge를 노출하지 않는다.

기술적 한계:

- Android WebView의 navigation callback은 모든 형태의 이동에 호출된다고 보장되지 않으며 특히 POST navigation에는 `shouldOverrideUrlLoading()`이 호출되지 않는다.
- `shouldInterceptRequest()`는 redirect chain의 최초 resource URL만 제공하므로 iframe, 이미지, script, fetch, Service Worker 등 **하위 리소스의 서버 redirect를 전부 감지·차단하거나 주소창에 표시하는 것은 보장할 수 없다**.
- 현재 SpoofDPI 프록시는 TLS를 복호화하지 않으므로 HTTPS 응답의 `Location` header를 검사해 이 API 한계를 보완할 수 없다.
- 따라서 이 문서의 “리다이렉트 항상 차단”은 WebView가 navigation callback으로 노출하는 main-frame 자동 이동에 대한 요구사항이다. 모든 하위 리소스 redirect까지 막는 것은 범위 밖이다.

---

## 4. 다크 모드

- 앱은 시스템 테마와 관계없이 항상 다크 모드여야 한다.
- Activity 테마는 명시적인 다크 테마를 사용하고 시스템의 라이트/다크 전환을 따르지 않는다.
- 앱 자체의 모든 배경, 시작 페이지, WebView 초기 배경, 주소창 및 시스템 바 색상은 다크 모드에 맞춰야 하며 페이지 전환 중 흰색 플래시가 나타나지 않아야 한다.
- WebView가 지원하는 경우 `WebSettingsCompat.setAlgorithmicDarkeningAllowed()` 등 현재 AndroidX WebKit API를 사용해 자동/알고리즘 다크닝을 허용한다.
- 웹페이지가 `prefers-color-scheme`을 지원하면 `dark`가 선택되도록 WebView의 강제 다크/테마 관련 설정과 앱의 야간 모드를 구성해야 한다.
- 페이지가 자체 다크 테마 또는 `prefers-color-scheme`을 지원하면 그것을 우선하고, 앱이 임의 CSS로 페이지의 색을 강제 반전하지 않는다.
- WebView/Android 버전이나 사이트 구현 때문에 모든 페이지를 다크 색상으로 바꾸는 것은 보장하지 않는다. 사이트가 명시적으로 라이트 색상을 고정한 경우에도 웹 콘텐츠의 기능과 가독성을 깨뜨리는 강제 변환은 필수가 아니다.

---

## 5. 뒤로 가기

Android OS 뒤로 가기는 다음 우선순위를 따른다.

1. 전체화면 영상이 열려 있으면 전체화면 영상을 종료한다.
2. WebView에 뒤로 갈 히스토리가 있으면 `WebView.goBack()`을 실행한다.
3. 히스토리가 없으면 Android 기본 동작으로 Activity를 종료한다.

앞으로 가기, 히스토리 목록, 방문 기록 관리 UI는 구현하지 않는다.

---

## 6. 영상 재생

### 6.1 기본 동작

HTML5 영상이 재생되면 가능한 한 자동으로 다음 상태로 전환한다.

- 전체화면
- landscape 방향
- 상태 바와 내비게이션 바를 숨긴 immersive 모드
- 영상 이외 앱 UI 숨김

구현 요구사항:

- `WebChromeClient.onShowCustomView()` / `onHideCustomView()`를 구현한다.
- 전체화면 진입 시 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`에 해당하는 동작을 사용한다.
- 종료 시 이전 orientation과 system bar 상태를 복원한다.
- 사이트가 fullscreen을 요청하지 않는 경우 `<video>`의 `play` 이벤트에서 `requestFullscreen()`을 시도하는 가벼운 JavaScript 주입을 사용할 수 있다.
- 동적으로 생성되는 `<video>`도 가능한 범위에서 처리한다.
- cross-origin iframe, 사이트 정책, 사용자 활성화 요구, WebView 제약 때문에 자동 전체화면이 불가능하면 사이트가 요청한 전체화면만이라도 정상 지원한다.

### 6.2 미디어 정책

- 음성 출력은 정상 지원한다.
- 카메라와 마이크 권한은 기본적으로 거부한다.
- 사용자 동작 없는 임의 autoplay 허용은 필수가 아니다.
- Picture-in-Picture, Cast 및 유사 외부 전송 기능은 지원하지 않는다.

---

## 7. WebView 및 사이트 데이터

### 7.1 필수 설정

현대 웹사이트 호환성을 위해 다음을 활성화한다.

- JavaScript
- DOM storage
- 쿠키
- 필요 시 third-party cookie

다음은 비활성화하거나 제한한다.

- cleartext HTTP
- mixed content
- 임의 파일 접근
- 로컬 파일 URL의 광범위한 접근
- SSL 오류 무시
- 카메라/마이크/위치 권한 자동 승인

SSL 인증서 오류는 우회하지 않는다.

### 7.2 사이트 데이터 보존

- 앱은 쿠키, Web Storage(`localStorage`, `sessionStorage`의 해당 수명 범위), IndexedDB, Service Worker 저장소, HTTP 캐시 등 WebView가 관리하는 사이트 데이터를 정상적으로 영속 저장해야 한다.
- 앱 시작, 종료, 업데이트 또는 일반 탐색 과정에서 사이트 데이터를 자동 삭제하거나 WebView 데이터 삭제 API를 호출하지 않는다.
- 앱 자체의 만료 기간, 총량 제한, 사이트별 정리 정책 또는 '종료 시 삭제' 기능을 만들지 않는다.
- 쿠키는 사이트가 지정한 만료/삭제 의미를 존중한다. 세션 쿠키나 `sessionStorage`처럼 웹 표준상 세션 수명을 갖는 데이터까지 영구 데이터로 변조하지 않는다.
- WebView/Chromium이 제공하는 저장소 quota와 캐시 정책을 사용한다. 앱이 이를 임의로 축소하지 않는다.
- Android에서 문자 그대로 무제한·영구 저장은 보장할 수 없다. 사이트 자체의 삭제/만료, WebView 정책, 저장공간 부족, OS의 데이터 정리, 사용자의 OS 설정을 통한 앱 데이터 삭제, 앱 제거로 데이터가 사라질 수 있다. 앱은 이들 플랫폼 동작을 우회하지 않는다.
- 사이트 데이터를 비우는 앱 내부 UI는 제공하지 않는다. 사용자는 Android OS의 앱 저장공간/데이터 삭제 기능을 이용한다.

### 7.3 HTTPS 전용 이동

메인 프레임 이동은 HTTPS만 허용한다.

- 사용자가 주소창에서 명시적으로 요청한 HTTPS URL과 직접 누른 HTTPS 일반 링크: 허용
- HTTPS를 포함한 모든 자동 main-frame 리다이렉트: 3.5의 정책에 따라 차단하고 주소창에 목적지만 제시
- HTTP 및 기타 외부 스킴: 차단
- 내부적으로 필요한 `about:blank`, `blob:` 등은 WebView 내부 동작을 깨지 않는 범위에서 허용
- mixed content: 차단

### 7.4 지원하지 않는 웹 기능

다음 전용 UI나 외부 앱 연동은 구현하지 않는다.

- 파일 업로드 선택기
- 다운로드 관리자
- 공유, 인쇄, 번역
- 데스크톱 사이트 전환
- 사용자 에이전트 변경
- 비밀번호 저장 및 자동완성 관리 UI
- 개발자 도구 UI

파일 다운로드 요청은 앱이 충돌하지 않도록 무시하거나 간단히 실패 처리한다.

### 7.5 향후 콘텐츠 차단 확장성

광고 및 추적 콘텐츠 차단은 현재 MVP 범위가 아니며, 이번 구현에서 필터 목록을 번들하거나 내려받거나 실제 차단 기능을 활성화하지 않는다. 그러나 추후 uBlock Origin(uBO)과 유사한 콘텐츠 차단 기능을 추가할 수 있도록 아래의 최소 엔트리포인트를 보존하고 브라우저 코드를 설계해야 한다.

#### 조사 결과와 목표 범위

uBO의 차단 방식은 크게 다음 계층으로 나뉜다.

1. **정적 네트워크 필터링**: URL, first/third-party 관계, 문서 도메인, 요청 자원 종류 등의 문맥으로 요청을 차단·허용하거나 안전한 로컬 리소스로 redirect한다.
2. **cosmetic filtering**: 일반 CSS selector를 페이지에 적용해 네트워크 차단만으로 없어지지 않는 DOM 요소를 숨기거나 제거한다.
3. **procedural cosmetic filtering**: 일반 CSS로 표현할 수 없는 조건을 JavaScript DOM 관찰/탐색으로 처리한다.
4. **scriptlet injection**: 선별되고 신뢰된 JavaScript 조각을 문서 시작 시점에 주입하여 광고·추적·anti-adblock 코드의 동작을 제한한다.
5. **HTML/응답 헤더 필터링과 CSP 등**: 응답이 파싱되기 전에 본문이나 헤더를 바꾸는 기능이다. 이 기능은 브라우저별 지원 차이가 크며 Chromium에서도 일부는 지원되지 않는다.

uBO는 브라우저 확장의 `webRequest`, `webRequestBlocking`, 모든 URL 접근 권한과 `document_start`의 all-frame content script를 사용한다. Android WebView는 브라우저 확장 실행 환경이나 이와 동일한 API를 제공하지 않으므로 uBO 자체를 그대로 설치·실행하거나 완전한 기능 동등성을 보장할 수 없다. 향후 기능은 uBO/ABP 호환 필터 문법 중 WebView에서 신뢰성 있게 구현 가능한 부분부터 단계적으로 지원하는 별도 엔진이어야 한다.

#### MUST: 네트워크 요청 판정 엔트리포인트

- `WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)`의 처리를 한곳에 캡슐화하고, 현재는 항상 허용하는 no-op `ContentBlockingEngine` 또는 동등한 인터페이스에 위임할 수 있게 한다.
- 판정 입력 모델은 최소한 요청 URL, HTTP method, request headers, main-frame 여부, 사용자 gesture 여부, 현재 top-level document URL/hostname을 받을 수 있어야 한다.
- 요청 자원 종류와 first/third-party 여부는 향후 보강할 수 있는 선택적 문맥으로 모델링한다. `WebResourceRequest`가 정확한 브라우저 자원 종류나 request body를 제공한다고 가정하지 않는다.
- 판정 결과는 최소 `ALLOW`와 `BLOCK`을 표현하고, 추후 `REDIRECT` 또는 로컬 대체 리소스를 추가해도 기존 `WebViewClient`를 다시 작성하지 않도록 한다.
- Service Worker가 발생시키는 요청도 빠지지 않도록, 지원되는 WebView에서는 `ServiceWorkerClient.shouldInterceptRequest()`가 같은 판정 엔진에 위임할 수 있는 연결 지점을 둔다. 현재 MVP에서 실제 Service Worker 필터링을 활성화할 필요는 없다.
- top-level navigation과 subresource 요청의 문맥을 혼동하지 않도록 `BrowserController`가 현재 main-frame URL을 추적하고 차단 엔진에 읽기 전용으로 제공할 수 있어야 한다.
- URL 수준 콘텐츠 필터링을 TLS CONNECT 프록시에 억지로 넣지 않는다. 현재 프록시는 TLS를 복호화하지 않아 SNI 외의 전체 URL/path, headers, resource type을 볼 수 없기 때문이다.

#### MUST: 문서 주입 엔트리포인트

- WebView를 처음 로드하기 전에 `WebViewCompat.addDocumentStartJavaScript()`를 등록할 수 있는 단일 구성 지점을 `BrowserController` 또는 전용 주입 구성 요소에 둔다.
- `WebViewFeature.DOCUMENT_START_SCRIPT` 지원 여부를 런타임에 검사한다. 지원 시 main frame과 HTTPS iframe에서 문서의 page script보다 먼저 고정된 bootstrap을 실행할 수 있게 한다.
- 향후 bootstrap은 hostname별 cosmetic CSS, DOM observer/procedural filtering, 검증된 scriptlet을 적용하는 통로가 될 수 있어야 한다. `onPageFinished()`의 늦은 주입만을 유일한 통로로 삼지 않는다. 늦은 주입은 광고가 먼저 보이는 깜빡임과 anti-adblock 코드의 선실행을 막지 못한다.
- 문서 시작 시 실행하는 공통 bootstrap은 짧고 정적으로 패키징해야 한다. 네트워크에서 받은 임의 JavaScript를 검증 없이 실행하거나 범용 native bridge를 모든 origin에 노출하지 않는다.
- scriptlet이 페이지 JavaScript API를 가로채야 하는 경우 실행 world의 의미를 확인해야 한다. isolated world에서 실행하면 페이지의 JavaScript 동작을 바꾸지 못할 수 있으므로 단순히 격리 실행으로 대체하지 않는다.
- document-start API가 없는 WebView에서는 cosmetic filtering 일부만 늦게 적용 가능한 degraded mode로 취급하고, scriptlet의 동등한 동작을 보장한다고 주장하지 않는다.

#### SHOULD: 필터 엔진 경계와 운영 고려사항

- 필터 목록의 획득/저장, 문법 파싱·컴파일, 요청 판정, 문서 주입 payload 생성을 서로 분리한다. 필터 목록 업데이트가 WebView 또는 SpoofDPI 프록시 수명주기와 결합되어서는 안 된다.
- 필터 목록은 매 요청마다 원문 전체를 선형 탐색하지 않고, 향후 백그라운드에서 검증·컴파일한 immutable snapshot을 원자적으로 교체할 수 있게 한다.
- 차단 판정 콜백은 UI thread가 아닐 수 있으므로 thread-safe하고 빠르며 네트워크 I/O를 동기적으로 수행하지 않아야 한다.
- 향후 필터 업데이트를 도입할 경우 HTTPS, 크기 제한, timeout, 원자적 파일 교체, 마지막 정상 버전 fallback 및 출처/버전 기록을 요구한다.
- 사이트 오작동을 풀기 위한 hostname 단위 allowlist/비활성화 정책을 엔진 수준에서 추가할 수 있어야 하지만, 현재는 설정 UI를 만들 필요가 없다.
- uBO 코드와 uAssets는 GPL-3.0이고, EasyList를 포함한 제3자 필터 목록은 각기 다른 라이선스를 가질 수 있다. 코드를 이식하거나 목록을 번들·재배포하기 전에 앱 전체 배포 방식과 호환되는지 확인하고 저작권 고지·소스 제공 등 의무를 문서화해야 한다. 단순히 공개 URL이라는 이유로 필터 목록을 무조건 번들하지 않는다.

#### WebView에서 예상되는 기능 한계

- `shouldInterceptRequest()`는 `javascript:`, `blob:` 등 일부 요청에 호출되지 않고 서버 redirect 뒤의 각 URL을 모두 알려주지 않으므로 확장 프로그램의 `webRequestBlocking`과 동등하지 않다.
- `WebResourceRequest`만으로 POST body, 완전한 response headers/body, 정확한 Chromium resource type을 항상 얻을 수 없다.
- 현재 로컬 프록시는 HTTPS CONNECT 뒤의 TLS를 복호화하지 않으므로 HTML 본문 변형, response-header 필터, CSP 삽입, URL query 제거를 일반적으로 수행할 수 없다.
- 따라서 초기 광고 차단 도입 범위는 URL 기반 네트워크 차단, 기본 cosmetic CSS, 제한된 procedural filtering 및 선별된 document-start scriptlet 순으로 잡는다. HTML filtering이나 uBO 완전 호환을 초기 목표로 삼지 않는다.

#### 조사 근거

- [uBO static filter syntax](https://github.com/gorhill/uBlock/wiki/Static-filter-syntax)
- [uBO network filtering engine overview](https://github.com/gorhill/uBlock/wiki/Overview-of-uBlock%27s-network-filtering-engine%3A-details)
- [uBO가 DNS 수준 차단보다 더 세밀하게 처리하는 방식](https://github.com/gorhill/uBlock/wiki/About-%22Why-uBlock-Origin-works-so-much-better-than-Pi%E2%80%91hole-does%3F%22)
- [uBO Firefox manifest의 blocking 권한과 document-start content script](https://github.com/gorhill/uBlock/blob/master/platform/firefox/manifest.json)
- [Android WebViewClient.shouldInterceptRequest](https://developer.android.com/reference/android/webkit/WebViewClient#shouldInterceptRequest(android.webkit.WebView,%20android.webkit.WebResourceRequest))
- [Android ServiceWorkerClient.shouldInterceptRequest](https://developer.android.com/reference/android/webkit/ServiceWorkerClient#shouldInterceptRequest(android.webkit.WebResourceRequest))
- [AndroidX WebViewCompat.addDocumentStartJavaScript](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,%20java.lang.String,%20java.util.Set%3Cjava.lang.String%3E))
- [uBO 및 uAssets의 GPL-3.0 라이선스](https://github.com/gorhill/uBlock), [필터 목록별 라이선스 안내](https://github.com/gorhill/uBlock/wiki/Filter-list-licenses)

---

## 8. SpoofDPI 방식의 로컬 프록시

### 8.1 범위

MVP는 앱의 WebView 트래픽만 처리한다.

- `VpnService`를 사용하지 않는다.
- 다른 앱의 트래픽을 처리하지 않는다.
- root 권한을 요구하지 않는다.
- 사용자 CA 인증서를 설치하지 않는다.
- TLS를 복호화하거나 MITM하지 않는다.

### 8.2 프록시 구조

앱 시작 시 loopback에 로컬 HTTP 프록시를 실행한다.

- 주소: `127.0.0.1`
- 포트: OS가 선택한 ephemeral port
- 외부 인터페이스에 bind하지 않는다.
- 프록시 준비 후 WebView proxy override를 설정한다.
- 앱 종료 시 proxy override를 해제하고 서버 소켓을 종료한다.
- AndroidX WebKit의 proxy override 기능을 사용한다.
- 시작 시 기능 지원 여부를 확인한다. 미지원이면 직접 연결로 조용히 우회하지 말고 기능 미지원 오류를 명확히 표시한다.

### 8.3 지원 프로토콜

MVP 프록시는 다음만 지원하면 된다.

- HTTP `CONNECT host:port`
- CONNECT 이후 TCP 양방향 터널
- TLS `ClientHello` 첫 메시지 분할

일반 HTTP 프록시 요청, UDP, QUIC/HTTP3, SOCKS5, TUN, transparent proxy는 지원하지 않는다. HTTPS 비표준 포트는 허용한다.

### 8.4 TLS ClientHello 처리

CONNECT 터널 수립 후 브라우저가 보내는 첫 TLS 메시지를 읽고 다음을 수행한다.

1. TLS record가 여러 read로 나뉘어도 완전한 `ClientHello`까지 누적한다.
2. TLS `ClientHello` 구조를 파싱한다.
3. SNI extension과 hostname의 바이트 범위를 찾는다.
4. 원격 서버 소켓에는 `ClientHello`를 여러 작은 `write()`로 분할해 보낸다.
5. 이후 트래픽은 변경하지 않고 양방향으로 복사한다.

기본 분할 전략:

```text
[SNI 이전 데이터]
[SNI hostname의 각 바이트 또는 충분히 작은 조각]
[SNI 이후 데이터]
```

구현은 `xvzc/spoofdpi`의 SNI split 아이디어를 참고할 수 있다.

필수 안전장치:

- 원격 소켓에 `TCP_NODELAY`를 적용한다.
- 초기 TLS 메시지를 무한히 버퍼링하지 않는다.
- read/connect/idle timeout을 둔다.
- 파싱 실패 시 충돌하지 않는다.
- 파싱 실패 시 연결 종료 또는 원본 전달 중 하나를 일관되게 선택하고 테스트한다.
- 양방향 복사 중 한쪽이 종료되면 반대쪽도 정리한다.
- thread, executor, socket이 Activity 종료 후 누수되지 않아야 한다.

### 8.5 MVP 제외 기능

다음은 구현하지 않는다.

- TTL 조작, packet disorder, fake packet
- raw socket, packet sniffing, hop count 추정
- custom segment plan UI, per-domain rule UI
- DNS-over-HTTPS, 시스템 전체 프록시, UDP desync

분할 방식과 아주 짧은 inter-segment delay는 코드 상수나 내부 설정으로 둘 수 있지만 사용자 설정 UI는 만들지 않는다.

### 8.6 DNS

MVP는 Android/JVM 시스템 resolver를 사용할 수 있다. DNS 차단 우회와 DoH는 범위 밖이다.

---

## 9. 기술 선택과 구조

- 언어: Kotlin
- UI: Android Views
- 렌더러: Android `WebView`
- proxy override: AndroidX WebKit
- 빌드: Gradle Kotlin DSL
- Compose와 불필요한 Material Components 의존성은 사용하지 않는다.
- 표준 라이브러리, AndroidX Activity/WebKit, Java socket API를 우선한다.
- 소켓 프록시에 coroutine이 꼭 필요하지 않으면 표준 `ExecutorService`를 우선한다.
- `compileSdk`와 `targetSdk`는 Nix에서 고정한 최신 stable Android SDK 버전을 사용한다.
- `minSdk`는 현대 WebView와 proxy override를 전제로 정하고 README에 기록한다. 구형 단말 호환성은 목표가 아니다.

단일 `app` 모듈로 충분하다. 권장 패키지 구조:

```text
app/
  ui/
    MainActivity
    FullscreenVideoController
  browser/
    BrowserController
    UrlNormalizer
    ContentBlockingEngine (현재는 no-op 인터페이스/구현)
    DocumentScriptInjector (현재는 확장 지점만 제공 가능)
  proxy/
    LocalConnectProxy
    ConnectRequestParser
    TlsClientHelloParser
    TlsClientHelloSplitter
    BidirectionalTunnel
```

과도한 Clean Architecture, DI framework, repository/use-case 계층은 도입하지 않는다.

---

## 10. APK 산출물과 배포 정책

- 최종 설치용 APK 파일명은 정확히 `armin.apk`여야 한다.
- `nix build .#default` 결과에서 `result/apk/armin.apk`로 쉽게 찾을 수 있어야 한다.
- Play Store, 다른 앱 스토어 또는 공개 배포 파이프라인에 발매하지 않는다.
- production/release signing key를 만들거나 저장소·Nix store에 포함하지 않는다.
- 사용자가 Android 개발자 옵션과 USB 디버깅을 직접 활성화하고 `adb install armin.apk` 등으로 sideload한다.
- **Android는 설치 가능한 모든 APK에 서명을 요구하므로 문자 그대로 서명되지 않은 APK는 설치할 수 없다.** 개발자 모드는 이 검증을 해제하지 않는다.
- 따라서 설치 가능한 `armin.apk`는 Gradle/Android 빌드 도구가 제공하는 개발용 debug keystore로 자동 서명한 debug APK여야 한다. 이 서명은 Play Store 배포용 서명이나 사용자가 관리하는 production key가 아니다.
- 필요하면 별도로 `app-release-unsigned.apk`를 중간 산출물로 만들 수 있으나, 이는 설치 대상이나 기본 결과물이 아니다.
- README에 APK가 개발용 디버그 키로 서명되며 공개 배포용이 아니라는 점과 설치 명령을 명확히 기록한다.

---

## 11. Nix 개발 환경

### 11.1 필수 명령

깨끗한 clone에서 다음이 동작해야 한다.

```bash
nix develop --command nvim .
nix build .#default
nix fmt
nix flake check
```

### 11.2 Flake 구성

저장소에 다음을 포함한다.

```text
flake.nix
flake.lock
nix/
  gradle-deps.json
  필요 시 kotlin-lsp.nix
```

Nix에서 nixpkgs, Android SDK platform/build-tools/platform-tools, adb, JDK, Gradle, JetBrains 공식 Kotlin LSP, ktfmt, Nix formatter 및 Gradle/Maven dependency cache를 고정한다. Android SDK는 `android-nixpkgs` stable 채널 또는 동등하게 고정 가능한 구성을 사용한다.

### 11.3 개발 셸

`nix develop`의 PATH에 최소한 다음이 있어야 한다.

```text
java
gradle
adb
ktfmt
kotlin-lsp
```

다음 환경 변수를 제공한다.

```text
ANDROID_HOME
ANDROID_SDK_ROOT
JAVA_HOME
KOTLIN_LSP_DIR
```

dev shell에 Neovim 자체는 넣지 않는다. `nix develop --command nvim .`은 사용자의 기존 Home Manager/NixOS Neovim을 실행하면서 프로젝트 툴체인만 주입해야 한다.

### 11.4 Kotlin LSP

- JetBrains 공식 `Kotlin/kotlin-lsp`를 사용하고 Nix에서 pin한다.
- Mason에 의존하지 않는다.
- Neovim 0.11 이상 표준 LSP client로 연결 가능해야 한다.
- Android Gradle project import 실패 시에도 서버가 충돌하지 않아야 한다.
- README에 최소 Neovim 설정 예제를 제공한다.
- 필요하면 `kotlin.nvim` 예제를 추가할 수 있으나 필수 의존성으로 강제하지 않는다.

### 11.5 Nix build

`nix build .#default`는 다음을 수행한다.

1. 네트워크 없이 Gradle 의존성을 사용한다.
2. 포맷 검사, 정적 분석, Android Lint, 단위 테스트를 실행한다.
3. 개발용 디버그 키로 서명된 설치 가능 APK를 생성한다.
4. 결과물을 `result/apk/armin.apk`로 제공한다.

개발용 설치는 다음도 가능해야 한다.

```bash
nix develop --command ./gradlew :app:installDebug
```

### 11.6 Gradle 의존성 고정

순수 Nix build를 위해 `gradle.fetchDeps` 또는 동등한 dependency capture 방식을 사용한다. Gradle plugin/Maven dependency 변경 시 lock/cache를 갱신하는 flake app을 제공한다.

```bash
nix run .#update-deps
```

이 명령만 네트워크 접근이 필요하며 갱신 후 `nix build`는 네트워크 없이 동작해야 한다. NixOS에서 Maven `aapt2` 대신 Nix Android SDK의 패치된 `aapt2` override가 필요하면 flake에 반영한다.

---

## 12. 포매팅, 분석 및 테스트

### 12.1 포매팅

- Kotlin 포매터: `ktfmt`
- Gradle 연결: Spotless
- 스타일: Kotlin language style
- `.kt`, `.kts`를 검사하고 생성 디렉터리는 제외한다.

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```

Neovim 저장 시 LSP formatter보다 Nix dev shell의 `ktfmt` 사용을 권장한다.

### 12.2 정적 분석

- Kotlin: Detekt
- Android: Android Lint
- 스타일 검사는 Spotless에 맡기고 Detekt는 품질과 오류 탐지에 집중한다.
- baseline 파일은 초기부터 만들지 않는다.
- suppress에는 이유를 남긴다.

다음 통합 task를 제공한다.

```bash
./gradlew quality
```

`quality`는 최소 `spotlessCheck`, `detekt`, `lintDebug` 또는 `lintRelease`, `test`에 의존한다.

### 12.3 Nix 검사

- `nix fmt`: `.nix`는 nixfmt 계열, `.kt`/`.kts`는 ktfmt로 처리한다.
- `nix flake check`: 단위 테스트, Spotless, Detekt, Android Lint 및 가능하면 APK build를 실행한다.

### 12.4 필수 테스트 범위

WebView 또는 기기 의존 항목은 가능한 경우 계측 테스트로 구현한다. 자동화가 환경상 불안정한 항목은 핵심 판정 로직을 Android 비의존 클래스로 분리해 단위 테스트하고 README에 수동 검증 절차를 기록한다.

#### URL 정규화 단위 테스트

- `example.com` → `https://example.com`
- `example.com/a?q=1` 처리
- `https://example.com` 처리
- 앞뒤 공백 처리
- 빈 문자열 무시
- `http://example.com` 차단
- 기타 금지 스킴 차단
- 잘못된 host 차단
- 비표준 HTTPS port 처리

#### CONNECT 파서 단위 테스트

- 일반 hostname
- hostname과 비표준 port
- bracketed IPv6
- 잘못된 CONNECT request line
- 지나치게 긴 header
- header 일부가 여러 read로 나뉜 입력

#### TLS ClientHello 파서 단위 테스트

- 정상 SNI
- SNI가 없는 ClientHello
- TLS record와 ClientHello가 여러 read로 분할된 입력
- extension 길이 오류
- truncated input
- 설정한 최대 크기 초과
- 파싱 실패 fallback 정책

#### splitter 단위 테스트

- 출력 segment를 다시 이어 붙이면 원본과 정확히 같음
- SNI hostname 바이트가 의도한 여러 segment로 나뉨
- 빈 segment를 생성하지 않음
- SNI가 없거나 파싱할 수 없을 때 정한 fallback대로 동작

#### 프록시 통합 테스트

가능하면 로컬 TLS 서버를 사용해 다음을 검증한다.

- CONNECT handshake
- 분할된 ClientHello 이후 TLS handshake 성공
- 양방향 데이터 전달
- 원격 연결 실패
- client 조기 종료와 server 조기 종료
- timeout, half-close 및 resource cleanup
- proxy override 미지원 시 사용자에게 명확한 오류 표시

#### Android 및 브라우저 동작 테스트

- 주소창에 bare hostname을 입력해 HTTPS URL 로드
- 새 browsing context를 요구하지 않는 일반 HTTPS 링크가 현재 WebView에서 열림
- `target="_blank"`, `window.open()` 및 자동 JavaScript 팝업이 새 창·현재 창·외부 앱 어디에서도 열리지 않고 별도 알림도 표시되지 않음
- main-frame HTTP 3xx, JavaScript `location` 변경 및 meta refresh가 가능한 WebView callback 범위에서 차단됨
- 차단된 리다이렉트 목적지가 주소창 내용을 replace하고 주소창에 포커스가 이동하며 사용자가 Enter를 눌렀을 때만 명시적 탐색이 시작됨
- redirect pending URL이 늦게 도착한 이전 페이지 callback에 의해 덮어써지지 않음
- HTTP 링크와 기타 금지 스킴 차단
- OS 뒤로 가기가 WebView history로 동작하고 history가 없으면 Activity 종료
- fullscreen 영상 진입/종료와 landscape immersive 전환
- 영상 fullscreen에서 뒤로 가기 시 앱 대신 영상만 먼저 종료
- 앱 새 실행 시 이전 URL 대신 빈 `#000000` 시작 페이지 표시
- 시스템 라이트 모드에서도 앱 UI와 `prefers-color-scheme`가 다크로 동작
- 앱 재시작 후 persistent cookie와 `localStorage` 유지
- 앱 코드가 종료 시 사이트 데이터 삭제 API를 호출하지 않음
- no-op 콘텐츠 차단 엔진 사용 시 기존 요청 동작이 변하지 않음
- WebView와 Service Worker 요청 판정이 추후 동일한 엔진으로 연결 가능함
- document-start 주입 등록이 최초 탐색보다 먼저 수행됨
- 결과물이 `result/apk/armin.apk`이고 `adb install` 가능한 debug-signed APK임

---

## 13. 오류 처리와 관찰 가능성

### 13.1 사용자에게 영향을 주는 오류

다음 오류에서 앱이 비정상 종료되거나 무한 대기해서는 안 된다.

- 잘못된 URL
- DNS 실패
- connect/read/idle timeout
- TLS handshake 실패
- 로컬 proxy 시작 실패
- WebView proxy override 미지원
- SSL certificate 오류
- 페이지 로드 오류

영구적인 상태 패널은 만들지 않는다. 오류는 상황에 따라 다음 중 하나로 표현할 수 있다.

- 간단한 WebView 내부 오류 페이지
- 주소창의 일시적인 error 상태
- 짧은 Toast

리다이렉트와 팝업 차단은 3장의 정책을 따른다. 팝업 차단은 알리지 않으며, 리다이렉트 차단은 목적지를 넣은 주소창과 포커스 이동만으로 알린다.

### 13.2 로그

Debug build에서는 진단을 위해 다음 범주의 로그를 남길 수 있다.

- proxy start/stop
- CONNECT destination의 host와 port
- ClientHello parse 성공/실패
- split segment 개수
- tunnel 종료 원인
- WebView navigation 오류

다음은 로그에 남기지 않는다.

- 전체 URL query에 포함될 수 있는 민감정보
- 쿠키
- request/response body
- TLS plaintext
- 사용자 입력 전체의 지속적인 기록

Release 계열 build를 별도 생성하는 경우 verbose proxy 로그를 비활성화한다. 기본 산출물인 debug-signed `armin.apk`도 필요 이상으로 민감한 값을 기록해서는 안 된다.

---

## 14. 보안 요구사항

- 로컬 프록시는 반드시 loopback에만 bind한다.
- SSL 오류를 무시하지 않는다.
- cleartext traffic과 mixed content를 허용하지 않는다.
- WebView debugging은 debug build에서만 허용한다.
- 임의 JavaScript interface를 WebView에 노출하지 않는다.
- JavaScript bridge가 꼭 필요하면 최소 인터페이스만 제공하고 허용 origin, 입력 형식과 크기를 검증한다.
- fullscreen 영상과 향후 콘텐츠 차단을 위한 JavaScript는 개인정보나 페이지 내용을 수집하지 않는다.
- custom CA, TLS MITM, 인증서 재서명은 금지한다.
- 앱 runtime/manifest 권한은 가능한 한 `INTERNET`만 사용한다.
- storage, location, camera, microphone 권한을 manifest에 추가하지 않는다.
- 앱 외부에서 proxy lifecycle이나 내부 WebView navigation을 임의로 조작할 수 있는 exported component를 만들지 않는다.
- 향후 filter list나 scriptlet 업데이트를 구현하기 전까지 원격 코드를 내려받아 실행하지 않는다.

---

## 15. 성능과 안정성

- 앱 실행 후 proxy 준비가 불필요하게 오래 걸리지 않아야 한다.
- 메인 스레드에서 socket I/O, DNS lookup 또는 큰 filter compile 작업을 수행하지 않는다.
- WebView 렌더링과 주소 입력이 proxy 작업 때문에 멈추지 않아야 한다.
- 모든 socket에 합리적인 timeout을 설정한다.
- 연결마다 무제한 thread를 생성하지 않고 bounded executor 또는 동등한 자원 제한을 사용한다.
- Activity 재생성 및 종료 시 executor, socket, proxy override와 document script handler를 적절히 정리한다.
- orientation 전환 중 영상 custom view와 WebView가 누수되지 않아야 한다.
- 주소창 편집, page URL update, redirect pending URL 사이의 race condition을 방지한다.
- 콘텐츠 차단 no-op 경로는 탐색 요청에 의미 있는 지연을 추가하지 않아야 한다.
- 향후 콘텐츠 차단 엔진은 매 요청마다 disk/network I/O나 전체 filter list 선형 탐색을 수행하지 않아야 한다.

---

## 16. 명시적 범위 밖 항목

현재 앱에는 다음을 구현하지 않는다.

- 탭과 앞으로 가기
- 새 창 또는 팝업 허용 예외
- 북마크와 방문 기록 UI
- 홈 버튼과 새로고침 버튼
- 검색 엔진 통합
- 주소 자동완성
- 다운로드 및 업로드 UI
- 공유, 인쇄와 번역
- 일반 설정 화면과 테마 설정
- 현재 MVP에서의 광고 차단 활성화
- filter list 다운로드/번들 및 콘텐츠 차단 설정 UI
- 사용자 스크립트와 브라우저 확장 프로그램
- 데스크톱 모드
- VPN 및 다른 앱 트래픽 proxy
- 계정과 동기화
- Push notification
- analytics와 crash reporting SDK
- Play Store 또는 기타 스토어 출시 및 배포 자동화
- production signing key와 release signing 운영
- 문자 그대로 unsigned 상태인 APK의 기기 설치
- OS, WebView, 웹 표준의 데이터 만료·quota·삭제 정책을 우회하는 무제한 저장
- 모든 하위 리소스 redirect의 완전한 탐지와 차단

향후 콘텐츠 차단 가능성을 고려한 7.5의 no-op 엔트리포인트는 이 범위 밖 목록의 “현재 MVP에서 활성화하지 않는다”는 의미와 충돌하지 않는다.

---

## 17. 소스와 라이선스

`xvzc/spoofdpi`의 코드를 직접 복사하거나 실질적으로 포팅하는 경우:

- 원본 Apache-2.0 라이선스 조건을 준수한다.
- 필요한 저작권 고지와 라이선스 파일을 포함한다.
- 복사한 파일 또는 주요 로직의 출처를 문서화한다.

아이디어만 참고해 독립 구현하는 경우에도 README에서 SpoofDPI를 설계 참고 자료로 사용했음을 밝히는 것을 권장한다.

uBO 코드, uAssets 또는 제3자 filter list를 향후 도입할 경우 7.5의 라이선스 검토 요구사항을 따른다. 현재 MVP에는 해당 코드나 목록을 복사·번들하지 않는다.

---

## 18. 저장소 산출물과 README

완성 저장소에는 최소한 다음이 있어야 한다.

```text
requirements.md
README.md
flake.nix
flake.lock
nix/
  gradle-deps.json
settings.gradle.kts
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
gradle/wrapper/
app/
  build.gradle.kts
  src/main/
  src/test/
  src/androidTest/
```

`initial-requirements.md`는 최종 산출물이 아니며 이 문서가 유일한 요구사항 문서다.

README에는 다음을 포함한다.

- 제품 설명
- 지원 범위와 비지원 범위
- `minSdk`, `compileSdk`, `targetSdk`
- Nix 요구사항
- `nix develop --command nvim .`
- Kotlin LSP용 Neovim 최소 설정
- 포매팅, lint, test와 `quality` 명령
- Gradle dependency cache 갱신 방법
- `nix build .#default`
- APK 위치 `result/apk/armin.apk`
- debug key 서명이라는 사실
- `adb install result/apk/armin.apk` 또는 `installDebug`
- 현재 알려진 WebView, redirect 차단, video fullscreen 및 사이트 데이터 보존 제약
- SpoofDPI 참고와 필요한 라이선스 고지
- 광고 차단은 아직 활성화되지 않았고 확장 지점만 존재한다는 사실

---

## 19. 권장 구현 순서

1. 빈 Kotlin Android Views 프로젝트 생성
2. Nix flake와 Android SDK/JDK/Gradle dev shell 구성
3. Spotless, ktfmt, Detekt, Android Lint 구성
4. 하단 주소창, 검은 시작 페이지, 강제 다크 WebView UI 구현
5. URL 정규화와 HTTPS-only navigation 구현
6. 팝업 차단, redirect pending URL과 수동 승인 상태 구현
7. 단일 창 및 Android back 동작 구현
8. 로컬 CONNECT proxy 골격 구현
9. TLS ClientHello parser와 SNI splitter 구현
10. WebView proxy override 연결
11. 전체화면 landscape 영상 처리
12. 사이트 데이터 보존 동작 확인
13. no-op 콘텐츠 차단 및 document-start 확장 지점 연결
14. unit, integration, instrumentation test 추가
15. `nix build`, `nix fmt`, `nix flake check` 완성
16. `result/apk/armin.apk`, README와 라이선스 정리

각 단계에서 기존 동작을 깨지 않도록 작고 검증 가능한 변경으로 진행한다.

---

## 20. 최종 완료 기준

다음 조건을 모두 만족해야 완료로 본다.

- [ ] 앱을 새로 실행하면 `#000000` 빈 시작 페이지와 하단의 빈 주소창만 보인다.
- [ ] 시스템이 라이트 모드여도 앱과 이를 인식하는 웹 콘텐츠는 다크 모드를 선호한다.
- [ ] `example.com` 입력 후 Enter를 누르면 `https://example.com`이 열린다.
- [ ] HTTP 및 기타 금지 스킴은 열리지 않는다.
- [ ] 일반 링크는 현재 WebView에서 열린다.
- [ ] 팝업은 현재 WebView, 새 창 또는 외부 앱 어디에서도 열리지 않고 별도 알림도 없다.
- [ ] 관찰 가능한 main-frame 자동 redirect는 차단되고 목적지가 주소창에 표시되며 포커스가 이동한다.
- [ ] redirect 목적지는 사용자가 Enter를 눌러야만 열린다.
- [ ] 탭과 새 창이 존재하지 않는다.
- [ ] Android 뒤로 가기가 WebView history 뒤로 가기로 동작한다.
- [ ] 앞으로 가기 기능이 없다.
- [ ] 영상 fullscreen이 landscape immersive 모드로 동작한다.
- [ ] 영상 fullscreen에서 뒤로 가기를 누르면 먼저 영상이 닫힌다.
- [ ] persistent cookie와 localStorage가 앱 재시작 후에도 유지되고 앱이 사이트 데이터를 자동 삭제하지 않는다.
- [ ] WebView HTTPS 트래픽이 앱 내부 loopback CONNECT proxy를 통과한다.
- [ ] TLS ClientHello의 SNI 구간이 여러 write로 분할된다.
- [ ] TLS를 복호화하지 않으며 인증서 검증이 정상 유지된다.
- [ ] root, VPN, custom CA가 필요 없다.
- [ ] 콘텐츠 차단 기능은 비활성 상태이고 no-op 엔트리포인트가 기존 탐색을 바꾸지 않는다.
- [ ] `nix develop --command nvim .`이 동작한다.
- [ ] dev shell에서 JetBrains Kotlin LSP가 실행된다.
- [ ] `nix fmt`가 동작한다.
- [ ] `./gradlew quality`가 동작한다.
- [ ] `nix flake check`가 통과한다.
- [ ] `nix build .#default`가 설치 가능한 debug-signed `result/apk/armin.apk`를 생성한다.
- [ ] Gradle 의존성이 고정되어 Nix build 중 외부 네트워크가 필요 없다.
- [ ] README에 빌드, 설치, LSP, 품질 검사, 기술적 제약 및 라이선스가 기록되어 있다.

---

## 21. 해석 우선순위

구현 중 애매한 선택이 발생하면 다음 순서로 판단한다.

1. HTTPS 페이지를 최소 UI로 안전하고 안정적으로 보여주는가
2. 팝업과 관찰 가능한 자동 redirect를 사용자의 명시적 탐색과 구분해 차단하는가
3. WebView 트래픽이 로컬 SpoofDPI 방식 proxy를 통과하는가
4. 사용자에게 요구되지 않은 UI나 기능을 추가하지 않는가
5. 사이트 데이터와 앱 상태가 이 문서의 수명주기 정책을 따르는가
6. Nix에서 재현 가능한 개발과 빌드가 가능한가
7. 구현이 단순하고 테스트 가능한가
8. 향후 콘텐츠 차단 확장 지점을 보존하되 현재 동작과 복잡도를 늘리지 않는가

기능을 임의로 추가하기보다 범위를 좁고 명확하게 유지하는 것을 우선한다. 새 요구사항이 이전 요구사항과 충돌하면 이 문서에 더 구체적으로 적힌 최신 정책을 따른다.
