# Windows용 앱 제작 프롬프트(코드베이스 분석 기반)

이 문서는 `c:\ATX_PIC\color_picker_tool` 코드베이스를 기준으로 **Windows용 Flutter 앱(EXE)** 을 “제대로 동작하는 수준”으로 만들기 위한 **복사-붙여넣기용 프롬프트(명령/요구사항)** 입니다.

---

### 1) 현재 코드 구조 요약(분석)

- **Flutter UI(공통)**: `color_picker_tool/lib/main.dart`
  - 시작 화면: `SystemOverlayPage`
  - Android 서비스/권한 제어를 `MethodChannel('system_color_picker')`로 호출
  - Windows에서는 동일 채널 구현이 없으면 버튼이 사실상 동작하지 않음(예외가 try/finally로 삼켜져 “무반응”처럼 보일 수 있음).

- **Android 네이티브(기능 대부분이 여기 있음)**:
  - `android/app/src/main/kotlin/.../ScreenCaptureService.kt`
    - 오버레이/마커/매크로 실행/이미지 탐지/저장(.jws) 핵심
    - 매크로 저장 포맷(중요): `saveMacroToFile()` 에서 JSON 저장
      - `{ ver, savedAtMs, markers:[...], screenSettings:{...} }`
  - `android/app/src/main/kotlin/.../AutoClickAccessibilityService.kt`
    - 접근성 툴바(재생/편집/객체보기 등)
  - `android/app/src/main/kotlin/.../MainActivity.kt`
    - Flutter↔Android 채널 `system_color_picker` 구현

- **Windows 네이티브(현재는 기본 러너만 존재)**:
  - `windows/runner/main.cpp`, `windows/runner/flutter_window.cpp`
  - 기본 Flutter Runner만 있고 **`system_color_picker` 채널 핸들러가 없음**
  - 따라서 Windows에서 “시작/권한/툴바” 관련 버튼은 Android 기능과 1:1 대응이 불가

---

### 1-1) 마커 종류(kind) 정리(실행/저장/루프 동작)

기준 파일: `android/app/src/main/kotlin/com/atx/pic/color_picker_tool/ScreenCaptureService.kt`

#### 마커 데이터 구조(중요 필드)

- **`xPx/yPx`**: `screen(Real)` 기준 중심 좌표(px) — 네비게이션바/컷아웃 포함 전체 화면
- **`delayMs`**: 루프 딜레이(마커별)
- **`pressMs`**: 현재 구현은 마커설정창에서 숨김 처리했고, 실행은 **전역 pressMs**(`pressMsForMarkerClick`)를 사용
- **`randomClickUse`**: “AI탐지방어(랜덤실행)” 체크 상태(확률 실행/스킵 대상)
- **`jitterPct`**: 화면설정의 전역 랜덤지연%가 모든 마커에 동기화됨(단, 실제 적용은 AI탐지방어 체크일 때만)

#### 마커 저장 JSON 키(Windows 포팅 시 “필수 호환 리스트”)

기준: `saveMarkersToPrefs()` / `loadMarkersFromPrefs()` (동일 파일)

- **공통(거의 모든 kind가 저장/복원)**  
  `index`, `kind`, `xPx`, `yPx`, `xPct`, `yPct`, `delayMs`, `jitterPct`, `pressMs`,  
  `parentIndex`, `toIndex`, `moveUpMs`, `swipeMode`,  
  `soloLabel`, `soloStartDelayMs`, `soloComboCount`, `soloExec`,  
  `moduleDir`, `moduleSoloExec`, `moduleLenPx`, `moduleMoveUpMs`, `moduleDirMode`, `modulePattern`, `modulePatternV2`,  
  `useColor`, `colorIndex`, `colorR`, `colorG`, `colorB`

- **`color_module` 전용**  
  `colorCheckXPx`, `colorCheckYPx`, `colorAccuracyPct`

- **`image_module` 전용**  
  `imageTemplateFile`, `imageStartXPx`, `imageStartYPx`, `imageEndXPx`, `imageEndYPx`,  
  `imageAccuracyPct`, `imageW`, `imageH`, `imageClickMode`,  
  `imageCropLeftXPx`, `imageCropTopYPx`  
  (디버그/상태 표시용 키도 추가됨: `imageFoundCenterXPx`, `imageFoundCenterYPx`, `imageLastScorePct`, `imageLastMinPct`, `imageLastOk` 등)

- **`solo_main` / `solo_item` 전용(실행확인/재개/goto 관련)**  
  `soloVerifyUse`, `soloVerifyTemplateFile`, `soloVerifyStartXPx`, `soloVerifyStartYPx`, `soloVerifyEndXPx`, `soloVerifyEndYPx`,  
  `soloVerifyAccuracyPct`, `soloVerifyW`, `soloVerifyH`, `soloVerifyCropLeftXPx`, `soloVerifyCropTopYPx`,  
  `soloVerifyGotoOnStopMissing`,  
  `soloPreClickUse`, `soloPreClickXPx`, `soloPreClickYPx`

- **AI탐지방어(랜덤실행) 저장 규칙**  
  - `randomClickUse`는 **일부 kind에서만 저장**됨(데이터 정리 목적)  
  - 저장 제외(kind): `solo_main`, `solo_item`, `color_module`, `image_module`, `swipe_to`, `color`

#### 랜덤 실행/랜덤 지연(Windows 포팅 시 동일 규칙 권장)

기준: `fireMarkerOnce()` / `jitterPctForMarkerDelay()` / `aiDefenseEligibleForRandomDelay()`

- **랜덤 실행(AI탐지방어=랜덤실행)**  
  - 조건: `randomClickUse == true`인 마커만 `execProbabilityPercent`에 의해 실행/스킵
  - 제외(kind): `color_module`, `solo_main`, `solo_item`, `swipe_to`, `color`
  - 참고: `image_module`은 “랜덤 실행” 제외 목록에 없으므로, 현재 코드는 확률 스킵 대상이 될 수 있음

- **랜덤 지연(전역 랜덤지연%)**  
  - 조건: “AI탐지방어 체크된 마커”이고, kind가 eligible일 때만 적용
  - 제외(kind): `swipe_to`, `solo_main`, `solo_item`, `solo`, `color_module`, `image_module`
  - 적용(kind): `click`, `independent`, `swipe(메인)`, `module` 등

#### 실행 대상/비대상(kind별)

`fireMarkerOnce()`에서 아래 kind는 **직접 실행 대상이 아님(링/체인 노드)**:

- **`color`**: 색상 조건부 실행에서 참조하는 링
- **`swipe_to`**: 스와이프 체인 노드(링)

#### kind 목록(실제 코드에 존재)

`markerCat(kind)` 기준(카테고리 1~7) + 링 kind 포함:

- **1) 순번 클릭**: `click`
- **2) 독립 클릭**: `independent`
- **3) 스와이프**: `swipe` (메인), `swipe_to`(서브/링)
- **4) 단독**: `solo_main`, `solo_item`, (legacy) `solo`
- **5) 방향모듈**: `module`
- **6) 색상모듈**: `color_module`
- **7) 이미지모듈**: `image_module`
- **링(조건/표시용)**: `color`, `swipe_to`

#### 실행 루프별 대상(kind 매핑)

- **순번실행(Ordered Loop)**: `startOrderedLoop()`
  - 대상:
    - `click` AND `index > 0`
    - `swipe` AND `index > 0` AND `swipeMode == 0`
    - `module` AND `index > 0` AND `moduleSoloExec == false`

- **독립실행(Independent Loops)**: `startIndependentLoops()`
  - 대상:
    - `independent`
    - `swipe` AND `swipeMode == 1`
    - `module` AND (`index < 0` OR `moduleSoloExec == true`)
    - `color_module`
    - `image_module`

- **단독실행(Solo Loop)**: `startSoloLoop()`
  - 대상: `solo_main`(또는 legacy `solo`) + `soloLabel != ""`
  - 특징:
    - `soloStartDelayMs` 타이머 기반 due 스케줄
    - due 시 `solo_item`(SUB)들을 라벨(A1,A2…) 순서로 실행
    - `soloVerifyUse`(이미지로 실행확인) / `soloPreClickUse`(실행 전 1회 클릭) / goto(재개) 포함

#### kind별 “실행 로직” 요약(Windows 포팅 사양)

- **`click` / `independent`**
  - 기본: 마커 중심 클릭
  - `useColor == true` & `colorIndex != 0`이면 `kind=="color"` 링 마커를 찾아 색상 조건 만족 시에만 클릭

- **`swipe` (메인)**
  - `toIndex != 0`이면 체인 스와이프:
    - 메인 + `swipe_to` 체인을 따라 points 구성(최대 24)
    - 마지막 `swipe_to.moveUpMs`를 hold(ms)로 사용
    - 실행: `swipePathPx(points, moveDurationMs=dur, holdMs=hold)`
  - 체인이 없으면 기본 스와이프(오른쪽 220dp)

- **`module` (방향모듈)**
  - `moduleLenPx`(없으면 220dp), `moduleMoveUpMs`(hold), `modulePattern`, `moduleDirMode` 기반으로 TAP/스와이프 수행
  - 실행 중 다른 마커 pause(명세): `withModuleRunning{...}`

- **`color_module`**
  - check 좌표에서 픽셀 색상 읽고 tolerance 비교 후 조건 만족 시에만 클릭
  - 실제 클릭 순간은 `withGlobalPause{...}`로 단독처럼 수행

- **`image_module`**
  - 템플릿 매칭 + 10회 다중 검증(과반) 후 성공 처리
  - `imageClickMode`: 0(마커중심), 1(찾은중앙), 2(소리), 3(진동)

- **`solo_main` / `solo_item`**
  - solo 루프에서만 실행(타이머 + 콤보)
  - 실행확인(soloVerify)은 캡처 권한 상태에 따라 폴백 로직이 있음(권한 해제 시 일반모드로 순서 실행)

#### 좌표계/회전 매핑(Windows 포팅 시 반드시 문서화할 것)

기준: `getScreenSize()` / `getUsableRectPx()` / `remapMarkersForRotationIfNeeded()` / `saveMarkersToPrefs(updateBase=...)`

- **screen(px)**: `maximumWindowMetrics.bounds` 기반 “Real 화면” 좌표(네비게이션바/컷아웃 포함)  
- **usable(px)**: 시스템바(insets)를 제외한 영역. `ScreenCaptureService`에서 `getUsableRectPx()`로 계산  
- **저장 규칙**: 마커 `xPx/yPx`는 **screen 기준**으로 저장(표시/클릭 정확도 목적)  
- **회전 드리프트 방지**: `flutter.markers_base` + `flutter.marker_base_rot/w/h`를 “기준(base)”로 저장해  
  회전/해상도 변경 시 항상 base->현재로 1회 변환(누적 변환 금지)

### 2) Windows EXE 빌드(현재 가능한 상태)

- 빌드 명령:

```bash
cd c:\ATX_PIC\color_picker_tool ; flutter build windows --release
```

- 산출물(EXE) 경로:
  - `c:\ATX_PIC\color_picker_tool\build\windows\x64\runner\Release\color_picker_tool.exe`

---

### 3) Windows에서 “Android처럼” 동작시키려면 필요한 것(핵심 갭)

Windows는 Android의 `AccessibilityService`, `MediaProjection`, `SYSTEM_ALERT_WINDOW` 같은 권한/서비스 모델이 없으므로 **동일 기능을 그대로 포팅할 수 없습니다.**

Windows 버전 제작은 아래 중 하나를 선택해야 합니다.

- **A안(최소 기능 / 무반응 방지 / 데스크톱 유틸)**:
  - Windows에서 `system_color_picker` 채널을 **최소 구현**해서 UI가 정상 반응하도록 만들기
  - Android 전용 기능 버튼은 “Windows에서는 지원하지 않음” 안내를 띄우고 종료
  - 버전 표시는 Windows에서도 표시(현재 `main.dart`에서 OS버전 + EXE 수정시간 기반 “컴파일 시간” 표시는 구현됨)

- **B안(Windows 자동클릭/스크린탐지 앱으로 실사용 수준 포팅)**:
  - 다음을 Windows에서 새로 구현해야 함
    - 화면 캡처(전체/영역)
    - 이미지 탐지(템플릿 매칭)
    - 전역 마우스 클릭/드래그/스와이프(마우스 이동+버튼 이벤트)
    - 오버레이 UI(항상 위) 또는 앱 내부 편집 UI
    - 매크로 저장/불러오기(`.jws`) **Android와 호환 유지**
  - 구현은 (1) Dart 패키지 기반 또는 (2) Windows 네이티브(C++/Win32) + 플랫폼 채널로 선택

---

### 4) “Windows용 앱 제작”을 위한 복사-붙여넣기 프롬프트(권장)

아래 프롬프트를 Cursor/ChatGPT 에 그대로 입력해서 작업을 진행하세요.

```text
너는 Cursor IDE의 코딩 에이전트다. 워크스페이스는 c:\ATX_PIC 이고 Flutter 프로젝트는 c:\ATX_PIC\color_picker_tool 이다.

목표:
1) Windows에서도 앱이 ‘무반응’이 아니라 정상 동작하게 만들 것.
2) Windows에서 Android 전용 기능(접근성/화면공유/오버레이 서비스)은 그대로 불가능하므로,
   - 최소 구현(A안): 각 버튼이 누르면 "Windows에서는 지원하지 않음" 또는 대체 동작을 하게 만들 것.
   - 가능하면 확장(B안): Windows에서 매크로(.jws) 로드/저장과 클릭 실행까지 지원할 것.
3) 버전 정보는 Windows에서도 Android처럼 보이게 할 것:
   - 앱 버전: pubspec.yaml version (예: 1.0.0+1)
   - 빌드(컴파일) 시각: 빌드할 때마다 자동 갱신되는 값(Windows에서는 exe 마지막 수정시간 사용 가능)
   - OS 버전: Platform.operatingSystemVersion

현재 코드 분석:
- Flutter UI: lib/main.dart 에서 MethodChannel('system_color_picker')로 Android 기능을 호출한다.
- Android 채널 구현: android/.../MainActivity.kt 에 있음.
- Windows runner는 windows/runner/*.cpp 로 기본만 있고 채널 구현이 없다.

구현 지시(최소 구현 A안):
- Windows에서 system_color_picker 채널을 구현해라.
  - 구현 위치: windows/runner/flutter_window.cpp 또는 별도 파일 추가(권장: flutter_window.cpp에서 엔진 생성 직후 채널 등록)
  - 메서드 목록(Flutter에서 호출):
    - getLanguage: "ko" 반환(또는 OS 언어 기반)
    - openAccessibilitySettings: Windows에서는 no-op 후 true 반환
    - ensurePermissionsAndShowToolbar: Windows에서는 true 반환(권한 모델 다르므로)
    - showAccessibilityToolbar / hideAccessibilityToolbar: Windows에서는 true 반환
    - startBasicPicker / startSystemPicker: Windows에서는 지원 안 함 -> {started:false, reason:"windows_not_supported"} 반환
    - stopSystemPicker: true 반환
  - Flutter 쪽(start 버튼 눌렀을 때) reason이 windows_not_supported면 SnackBar로 안내 메시지를 보여라.

확장 구현(B안, 선택):
- Windows에서 마우스 자동클릭/이미지탐지까지 제공하려면:
  - .jws 포맷은 Android의 ScreenCaptureService.saveMacroToFile()에 맞춰 호환 유지
  - 화면 캡처/탐지는 Dart 패키지(예: screen_retriever/ffi/win32 등) 또는 Win32 API로 구현
  - 클릭 실행은 Win32 SendInput 사용
  - 최소 기능으로 아래 kind 사양을 그대로 구현:
    - click / independent / swipe(swipe_to 체인 포함) / module / color_module / image_module / solo_main+solo_item
    - 링(kind=color, swipe_to)은 실행하지 않고 조건/체인용으로만 사용
    - Ordered / Independent / Solo 루프 대상 규칙은 Android와 동일하게 구현

빌드/검증:
- Windows 빌드: cd c:\ATX_PIC\color_picker_tool ; flutter build windows --release
- 산출물: build\windows\x64\runner\Release\color_picker_tool.exe
- Android 빌드도 깨지지 않아야 함: flutter build apk --release

주의:
- 명령어를 한 줄로 연속 실행 시 && 대신 ; 사용.
- 수정 후 lints/빌드 에러가 없게 마무리.
```

---

### 4-1) “다른 폴더에 Windows용 새 앱”을 만들기 위한 프롬프트(신규 프로젝트용)

아래는 **새 폴더에 Windows 전용 프로젝트**를 만들 때 쓰는 “프롬프트 명령어”입니다.  
기존 Android 코드는 포팅 대상이 아니고, **.jws 호환 + 마커(kind) 실행 엔진**을 Windows에서 새로 구현하는 시나리오(B안)입니다.

```text
너는 Cursor IDE의 코딩 에이전트다. 워크스페이스는 c:\ATX_PIC 이다.

목표:
- c:\ATX_PIC\autoclick_win (새 폴더) 에 Windows용 Flutter 앱을 새로 생성하고,
- 기존 Android 앱(color_picker_tool)의 매크로(.jws) 포맷과 마커(kind) 실행 규칙을 호환되게 구현한다.

새 프로젝트 생성:
- 명령(한 줄에서 연속 실행은 ; 사용):
  - cd c:\ATX_PIC ; flutter create autoclick_win ; cd c:\ATX_PIC\autoclick_win
- Windows 빌드:
  - cd c:\ATX_PIC\autoclick_win ; flutter build windows --release

필수 기능(최소 실사용):
1) .jws 읽기/쓰기(호환)
   - 저장 구조: { ver, savedAtMs, markers:[...], screenSettings:{...} }
   - markers 배열의 각 요소는 kind/xPx/yPx/delayMs/... 를 포함(기존 키 유지)
   - **markers 저장 키(중요)**: 본 문서의 “마커 저장 JSON 키” 섹션을 그대로 구현(키 누락 금지)
2) 마커(kind) 사양을 Android와 동일하게 구현
   - kind 목록:
     - 실행: click, independent, swipe, module, color_module, image_module, solo_main, solo_item
     - 비실행(링/노드): color, swipe_to
   - 루프:
     - Ordered: click(index>0) + swipe(index>0, swipeMode=0) + module(index>0, !moduleSoloExec)
     - Independent: independent + swipe(swipeMode=1) + module(index<0 or moduleSoloExec) + color_module + image_module
     - Solo: solo_main(라벨) 타이머 + solo_item(A1,A2...) 순서 실행
   - 랜덤 실행/랜덤 지연 규칙도 Android와 동일하게 반영(본 문서 “랜덤 실행/랜덤 지연” 섹션)
3) 입력(마우스) 실행
   - Win32 SendInput으로 좌표 클릭/드래그(스와이프) 구현
4) 화면 캡처 + 템플릿 매칭(image_module/soloVerify)
   - Windows API 또는 ffi/win32 기반으로 화면을 비트맵으로 가져오기
   - 템플릿 매칭은 최소 MAD/SSD 기반으로 구현하고, Android처럼 다중 검증(10회/과반) 지원
5) UI
   - 마커 편집 UI는 앱 내부에서 제공(오버레이 대신)
   - 마커 목록/종류 변경/좌표 입력/미리보기/테스트 실행 버튼 제공

참조(기존 코드에서 사양 추출):
- c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt
  - Marker 구조
  - fireMarkerOnce() : kind별 실행 규칙
  - startOrderedLoop()/startIndependentLoops()/startSoloLoop() : 루프 대상 규칙
  - saveMacroToFile()/loadMacroFromFile() : .jws 포맷

검증:
- Windows에서 .jws 파일 하나를 로드해서
  - Ordered/Independent/Solo가 각각 실행되는지 로그로 확인
- Android 쪽과 동일 .jws를 서로 교환해도 깨지지 않아야 함

주의:
- 명령어를 한 줄로 연속 실행 시 && 대신 ; 사용.
```

### 5) 참고(매크로 파일 포맷 / 호환 포인트)

- Android 저장 포맷은 `ScreenCaptureService.kt`의 `saveMacroToFile()` / `loadMacroFromFile()` 참고
  - root: `ver`, `savedAtMs`, `markers`, `screenSettings`
  - 향후 Windows 구현 시 **같은 JSON 구조**를 유지하면 Android↔Windows 매크로 공유가 가능해짐

---

### 6) 지금 상태에서의 “Windows 앱” 체크리스트

- [ ] Windows 빌드 성공: `flutter build windows --release`
- [ ] Windows에서 시작 버튼 누르면 “무반응”이 아니라 안내/대체동작 수행
- [ ] 버전/컴파일시간/OS버전 표시
- [ ] Android 빌드도 유지: `flutter build apk --release`

