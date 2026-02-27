# callcolor.md — Windows(Flutter)용 “색상모듈 색상 가져오기(픽커)” 구현 프롬프트

아래 요구사항/데이터 모델/흐름을 **그대로** 구현해서, Android `ScreenCaptureService.showColorModulePickerOverlay()`의 “색상가져오기”와 동등한 동작을 Windows(Flutter Desktop)에서 재현하라.

---

## 1) 목표(사용자 기능)

마커 `kind == "color_module"`에서 사용자가 “색상 가져오기” 버튼을 누르면:

- (A) 화면(또는 캡처 프레임) 위에서 사용자가 찍는 좌표의 픽셀을 읽어
- (B) **HEX 색상(#RRGGBB) + 좌표(xU,yU usable px)** 를 얻고
- (C) 마커 설정 UI(HEX 입력칸/좌표 입력칸/스와치)에 즉시 반영되게 한다.

Android와 동일하게:

- 색상 선택 중에는 “설정창”은 **종료하지 않고 숨김(visible OFF)** 처리한다.
- 픽커에서 “보내기/닫기”를 누르면 설정창을 다시 **앞으로 복구**한다.

---

## 2) 저장해야 하는 마커 필드(최종 저장 구조)

Windows에서도 마커(JSON)는 아래 필드를 유지해야 한다(Android와 동일 키/의미).

- `colorR`, `colorG`, `colorB`: 0..255
- `colorCheckXPx`, `colorCheckYPx`: **usable 좌표(px)** (아래 좌표계 참고)
- `colorAccuracyPct`: 50..100 (색상 모듈 판정에 사용, 픽커 자체는 “가져오기”에서 주로 HEX/좌표만 제공)

픽커의 “보내기” 결과는 다음으로 연결된다:

- 픽커는 **HEX(#RRGGBB) + (xU,yU)** 를 설정 UI로 전달
- 설정 UI의 “저장” 시점에 HEX를 RGB로 파싱하여 `colorR/G/B`에 저장
- 좌표 입력칸 값이 `colorCheckXPx/YPx`로 저장

---

## 3) 좌표계 규칙(중요: usable vs screen)

Android 기준 개념을 Windows에도 그대로 둔다.

- **screen 좌표**: 캡처 프레임(또는 전체 화면)의 픽셀 좌표 (0,0 = 좌상단)
- **usable 좌표**: “시스템 UI(네비게이션바 등) 제외” 후 실제 UI가 배치되는 좌표

Windows 권장:

- 기본은 `usableLeft=0, usableTop=0, usableW=frameW, usableH=frameH` 로 두고,
- 앱 정책상 특정 시스템 영역(예: 작업표시줄)을 제외해야 하면 **usableRect** 를 정의한 뒤 아래 변환을 적용한다.

변환 공식(필수):

- `xU = clamp(xS - usableLeft, 0..usableW-1)`
- `yU = clamp(yS - usableTop,  0..usableH-1)`

픽커 내부에서 샘플링은 `xS,yS`(screen)을 기준으로 하고, 설정 UI에 전달할 때만 `xU,yU`로 변환한다.

---

## 4) UI/UX 요구사항(Android와 동일)

### 4-1. “색상 픽커(작은 이동창)” UI

다음 요소가 있는 작은 패널(오버레이/모달)을 띄운다.

- **스와치 사각형**: 현재 샘플 색상으로 배경색 변경
- **HEX 텍스트**: `#RRGGBB`
- **좌표 텍스트**: 마지막 샘플 좌표(가능하면 screen과 usable을 함께 표시해도 됨)
- 버튼 2개:
  - **보내기**: 현재 샘플(HEX + 좌표)을 설정 UI로 전달하고 종료
  - **닫기**: 전달 없이 종료(단, 설정 UI는 복구해야 함)
- 패널은 드래그 핸들로 이동 가능(화면 밖으로 안 나가게 clamp)

추가 UX(중요):

- 사용자가 다시 찍고 싶으면, 패널을 탭하면 “1회 선택 모드”를 다시 켜서 재선택 가능하게 한다.

### 4-2. “터치 레이어(전체 화면)”로 좌표/색상 선택

Android는 별도의 full-screen 터치 오버레이를 띄워서 사용자가 찍는 좌표를 가로채 `sampleAtScreen(x,y)`를 호출한다.

Windows도 동일한 구조로 구현한다:

- 픽커가 열리면 화면 위에 투명한 “터치 레이어”를 활성화한다.
- 터치 이벤트에서 좌표를 얻어 **픽셀 색상 샘플링**을 수행한다.

샘플링 정책(Android와 동일):

- `pickOnceArmed == true`인 상태에서는 **ACTION_DOWN 1회만** 샘플링
- 그 다음 `pickOnceArmed=false`가 되면, 원하면 DOWN/MOVE에서 계속 샘플링(커서 이동에 따라 실시간 변경)
- 픽커 모드에서는 기본적으로 “통과 OFF”로 두어(아래 앱에 전달하지 않음) 사용자 선택이 안정적이게 한다.

Windows에서 “통과 OFF” 구현:

- 터치 레이어가 포인터 이벤트를 **consume**(HitTest)하도록 만들어 아래 위젯에 전달하지 않는다.

---

## 5) 핵심: 픽셀 읽기(sampleAtScreen / readArgbAtScreen)

Android는 최신 캡처 프레임(`frameBytes`, RGBA_8888)을 가지고 있고, 샘플 좌표를 프레임 해상도에 맞게 스케일링한 후 RGBA를 읽는다.

Windows에서도 다음을 충족해야 한다.

### 5-1. 프레임(스크린샷) 확보

픽커가 열려있는 동안 “현재 화면 프레임”이 필요하다.

권장 2가지 중 택1:

- **정지 프레임 방식(권장)**: 픽커 진입 시 1회 스크린샷을 찍어 그 이미지를 기준으로 픽셀을 읽는다.  
  - 장점: 구현 단순, 오버레이 UI가 캡처에 섞일 리스크가 낮음.
- **라이브 프레임 방식**: 일정 주기로 스크린샷/프레임을 갱신하고, 픽커는 최신 프레임에서 읽는다.

주의:

- 픽커 UI(스와치/텍스트 등)가 캡처에 섞이면 픽셀값이 왜곡된다.  
  정지 프레임 방식이면 자동으로 해결된다.

### 5-2. 좌표 매핑(화면좌표 → 프레임좌표)

Android는 안전하게 스케일을 적용했다:

- `fx = round(xS * frameW / screenW)`
- `fy = round(yS * frameH / screenH)`

Windows도 “표시 중인 이미지 위젯(프리뷰)”의 렌더 스케일/레터박스가 있을 수 있으므로, 반드시 아래를 구현한다:

- **사용자가 클릭한 위치가 프리뷰 이미지의 어느 픽셀인지** 역산해 `fx,fy`를 구한다.
- 프리뷰가 화면 전체 1:1이면 `fx=xS, fy=yS`로 단순화 가능.

### 5-3. 픽셀 읽기 및 HEX 생성

필수:

- `argb = 0xAARRGGBB` 형태로 얻는다(알파는 0xFF로 고정해도 됨).
- `hex = String.format("#%02X%02X%02X", r, g, b)`

상태로 유지:

- `lastPickedArgb`
- `lastSampleScreenX`, `lastSampleScreenY`

샘플링 후 UI 업데이트:

- 패널(픽커)이 열려 있으면 스와치/HEX/좌표를 즉시 갱신한다.

---

## 6) “보내기/닫기” 동작(결과 전달)

Android 동작을 Windows로 번역해서 동일하게 구현한다.

### 6-1. 보내기

- 현재 `lastPickedArgb`, `lastSampleScreenX/Y`를 사용
- `usableRect`를 적용해 `xU,yU`를 계산
- 결과:
  - `hex` (예: `#12AB34`)
  - `xU,yU` (usable px)
  - `targetIndex` (마커 index)

Windows 구현 예시:

- `Navigator.pop(context, PickResult(hex, xU, yU, targetIndex))`
- 또는 상태관리(Provider/Riverpod/Bloc)에 `PickResult`를 publish해서 설정 화면이 즉시 반영

그리고 픽커/터치 레이어를 닫고, 숨겼던 설정 화면을 다시 visible로 복구한다.

### 6-2. 닫기

- 결과 전달 없음(혹은 `xU=yU=-1` 같은 취소 표식)
- 픽커/터치 레이어 종료
- 설정 화면 visible 복구

---

## 7) 설정 화면(UI) 반영 규칙

픽커 결과를 받으면 즉시 다음을 수행한다:

- `HEX 입력칸`에 `hex` 반영
- `스와치` 배경색 반영
- `체크 좌표 입력칸`에 `xU,yU` 반영

저장 시:

- HEX → RGB 파싱
- `colorR/G/B`, `colorCheckXPx/YPx`, `colorAccuracyPct`를 마커 JSON에 저장

---

## 8) 예외/가드(필수)

- 캡처(스크린샷)가 불가능한 상태면:
  - 픽커 진입을 막거나
  - “캡처가 중지됨/권한 필요” 같은 메시지를 표시하고 종료한다.
- 좌표가 화면 밖이면 clamp 한다.
- 픽셀 읽기 실패 시:
  - 이전 값 유지 또는 `#000000` 같은 기본값 처리
  - 사용자에게 실패 알림(스낵바/토스트)

---

## 9) 구현 산출물(Windows 쪽에 꼭 만들어야 하는 것)

- `ColorPickerOverlayPage` (또는 다이얼로그/모달)
  - 작은 패널 UI(스와치/HEX/좌표/보내기/닫기/드래그)
  - 전체 화면 터치 레이어(좌표 선택)
- `PickResult` 모델
  - `targetIndex`, `hex`, `xU`, `yU`
- `FrameProvider`
  - 정지/라이브 스크린샷 제공
  - `getPixelArgb(fx,fy)` 제공
- `usableRect` 정책
  - “모든 화면에서 시스템바(네비게이션바 등) 제외” 요구사항을 만족하도록, 앱이 사용하는 usable 영역을 일관되게 정의

