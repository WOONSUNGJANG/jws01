# color_picker_tool (시스템 오버레이 기반 픽셀 컬러 피커)

Flutter 앱에서 **Android 시스템 오버레이(툴바)**를 띄우고, **MediaProjection(전체화면 공유)**로 얻은 프레임에서 **사용자가 터치한 스크린 좌표의 픽셀 색상(HEX/RGB)**을 읽어오는 도구입니다.

이 문서는 다른 프로젝트(다른 Flutter 앱/Android 앱)에서 **기능을 복사/재사용**하기 쉽게, 코드 구조와 공개 진입점(채널/서비스/핵심 함수)을 정리한 도움말입니다.

---

## 1) 동작 개요(아키텍처)

### 구성 요소
- **Flutter UI**
  - `lib/main.dart`
  - `MethodChannel('system_color_picker')`로 네이티브 호출
- **Android 네이티브**
  - `MainActivity.kt`: 권한 확인 + MediaProjection 권한 UI(시스템 “전체화면 공유”) 띄움 + 서비스 시작
  - `ScreenCaptureService.kt`: 포그라운드 서비스(FGS)로 캡처 유지 + 오버레이 윈도우 관리 + 픽셀 샘플링
- **오버레이 레이아웃**
  - `android/app/src/main/res/layout/overlay_color_picker.xml` : 상단 툴바(HEX/좌표/버튼)
  - `android/app/src/main/res/layout/overlay_touch_layer.xml` : 전체화면 투명 터치 레이어(색상 샘플링용)
  - `android/app/src/main/res/layout/overlay_coord_input.xml` : 좌표 입력 팝업(오버레이)

### 핵심 흐름
1. Flutter에서 `startSystemPicker` 호출
2. `MainActivity`가
   - 오버레이 권한(`SYSTEM_ALERT_WINDOW`) 확인
   - Android 13+ 알림 권한(`POST_NOTIFICATIONS`) 확인
   - MediaProjection 권한 화면(“전체화면 공유”) 띄움
3. 사용자가 허용하면 `ScreenCaptureService`(FGS) 시작
4. 서비스가
   - `MediaProjection` 확보 + 콜백 등록
   - `VirtualDisplay + ImageReader`로 프레임 수신
   - 오버레이 툴바 + (필요 시) 터치 레이어 표시
5. 사용자가 화면을 탭/드래그 → 서비스가 좌표에 해당하는 픽셀을 프레임에서 샘플링 → 툴바 HEX/색상/좌표 갱신

---

## 2) 외부(다른 앱)에서 재사용하는 방법

### A. Flutter 앱에서 재사용(권장)

#### 1) Android 쪽 파일 복사
아래 파일/폴더를 새 프로젝트의 동일한 위치에 복사합니다.
- `android/app/src/main/kotlin/.../MainActivity.kt`
- `android/app/src/main/kotlin/.../ScreenCaptureService.kt`
- `android/app/src/main/res/layout/overlay_color_picker.xml`
- `android/app/src/main/res/layout/overlay_touch_layer.xml`
- `android/app/src/main/res/layout/overlay_coord_input.xml`
- `android/app/src/main/AndroidManifest.xml`의 permission/service 설정(아래 참고)

#### 2) AndroidManifest 설정
`android/app/src/main/AndroidManifest.xml`에 아래가 필요합니다.
- **오버레이 권한**: `android.permission.SYSTEM_ALERT_WINDOW`
- **FGS**: `android.permission.FOREGROUND_SERVICE`
- **Android 13+ 알림**: `android.permission.POST_NOTIFICATIONS`
- **Android 14+ 미디어프로젝션 FGS 타입**: `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`
- 서비스 선언:
  - `.ScreenCaptureService`
  - `android:foregroundServiceType="mediaProjection"`

현재 예시는 다음 파일에 있습니다.
- `android/app/src/main/AndroidManifest.xml`

#### 3) Flutter ↔ 네이티브 채널 API
채널 이름(고정):
- `system_color_picker`

메서드:
- `startSystemPicker() -> Map { started: bool, reason?: string }`
  - reason 예시:
    - `needs_overlay_permission`
    - `needs_notification_permission`
    - `media_projection_denied`
- `stopSystemPicker() -> bool`
- `getLastColorArgb() -> int` (ARGB 32-bit, 예: `0xFFAABBCC`)

Flutter 호출 예시는 `lib/main.dart`의 `_startSystemPicker`, `_stopSystemPicker`, `_refreshLast`를 참고하세요.

---

## 3) 오버레이 툴바 UI/기능(사용자 관점)

툴바(`overlay_color_picker.xml`)에 있는 기능:
- **색상 박스 + HEX**
  - 마지막 샘플링한 색상 표시
- **좌표 표시**
  - 마지막 샘플링한 스크린 좌표(픽셀) `(x,y)`
- **통과 ON/OFF(체크박스)**
  - `통과 ON`: 터치 레이어 제거 → 아래 앱 터치 가능
  - `통과 OFF`: 터치 레이어 표시 → 탭/드래그로 색상 샘플링
- **좌표 입력(버튼)**
  - X/Y를 입력 → “검색” 누르면 팝업 닫히고 해당 좌표 색상 샘플링
- **재시작(▶ 버튼)**
  - 잠금/절전 등으로 MediaProjection이 중지된 경우, 앱을 앞으로 띄워 **다시 권한 요청 후 재시작**
- **원샷 픽업(돋보기 버튼)**
  - 통과 ON 상태에서도 1회만 터치 레이어를 띄워 “한 번 탭”으로 샘플링 후 자동 복귀
- **복사(연필 아이콘)**: HEX 클립보드 복사
- **종료(X)**: 서비스/오버레이 종료

---

## 4) 핵심 함수(개발자 관점)

### `MainActivity.kt`
- **`startSystemPicker(result)`**
  - 오버레이/알림 권한 체크 후 MediaProjection 인텐트 호출
- **`onActivityResult()`**
  - 허용되면 `ScreenCaptureService.startIntent(...)`로 서비스 시작
- **오버레이 재시작 경로**
  - 서비스가 앱을 띄울 때 `Intent extra: auto_start_picker=true`
  - `onResume()`에서 감지해 `startSystemPickerFromOverlay()` 실행

### `ScreenCaptureService.kt`
- **`startProjection(resultCode, data)`**
  - `MediaProjection` 확보 + `registerCallback`
  - `VirtualDisplay + ImageReader` 시작
- **`sampleAtScreen(screenX, screenY)`**
  - 최신 프레임 버퍼(`RGBA_8888`)에서 해당 좌표 픽셀 색상 읽기
  - `lastPickedArgb`, HEX, 스와치, `(x,y)` 갱신
- **회전(가로/세로) 대응**
  - `MediaProjection.Callback.onCapturedContentResize()`에서
    - `VirtualDisplay`를 “재생성”하지 않고
    - `setSurface + resize`로 재설정(최신 Android에서 createVirtualDisplay 반복 호출 제한 대응)
- **잠금/절전 대응**
  - `ACTION_SCREEN_OFF / SCREEN_ON / USER_PRESENT` 수신
  - 화면 OFF 시 입력용 오버레이 정리
  - 화면 ON/해제 시 툴바 복원 시도
  - `MediaProjection onStop()`에서는 **서비스를 죽이지 않고** 캡처만 정리 + “재시작 필요” 상태로 전환

---

## 5) 중요한 제약/주의사항(안드로이드 정책)

### 1) MediaProjection은 “영구 권한”이 아님
- 화면 꺼짐/잠금/정책/사용자 동작에 의해 언제든 `MediaProjection stopped`가 발생할 수 있습니다.
- 이 경우 앱은 **다시 권한을 요청**해야 합니다. (툴바의 ▶ 재시작 버튼 제공)

### 2) Secure 오버레이(FLAG_SECURE) 주의
- 투명 오버레이라도 `FLAG_SECURE`가 화면 위를 덮으면, MediaProjection 캡처에서 해당 영역이 **검정 마스킹**될 수 있습니다.
- 현재 구현은:
  - 툴바: `FLAG_SECURE` 사용(캡처에 툴바가 섞이지 않게)
  - 터치 레이어: `FLAG_SECURE` 사용 금지(아래 앱 픽셀을 읽기 위해)

### 3) 가로/세로 전환 시 z-order/레이아웃 이슈
- 일부 기기에서 투명 오버레이의 z-order가 예기치 않게 바뀔 수 있어, 툴바/터치 레이어 배치가 중요합니다.

---

## 6) 문제 해결(Troubleshooting)

### A) “탭해도 HEX가 안 변함”
- 대부분 아래 중 하나입니다.
  - **MediaProjection stopped** 상태(프레임 없음) → ▶ 재시작 필요
  - 터치 레이어가 비활성(통과 ON) → 통과 OFF 또는 원샷 픽업 사용

### B) “검정/투명처럼 색이 이상함”
- 터치 레이어/오버레이에 `FLAG_SECURE`가 잘못 들어가 캡처가 마스킹되는 경우가 많습니다.

### C) 로그 확인
필터:
```bash
adb logcat -v time AndroidRuntime:E ScreenCaptureService:I *:S
```
참고: `com.android.vending`(Play Store) 크래시는 이 앱과 무관하게 같이 찍힐 수 있습니다.

---

## 7) 빌드 산출물
- 릴리즈 APK: `c:\ATX_PIC\apk\app-release.apk`

# color_picker_tool

A new Flutter project.

## Getting Started

This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Lab: Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Cookbook: Useful Flutter samples](https://docs.flutter.dev/cookbook)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.


 설정/색상 패널( callimgsetup )


 adb logcat -c
adb logcat -v time AndroidRuntime:E ScreenCaptureService:I *:S