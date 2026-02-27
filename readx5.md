### Windows 포팅용 프롬프트: “마커종류 6번(색상모듈 / color_module)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`fireMarkerOnce()` 내 **색상모듈(color_module)** 실행 로직과, 픽셀 샘플링 함수 `readArgbAtScreen()`를 기준으로
Windows 앱에서 동일하게 구현하기 위한 **복사-붙여넣기용 프롬프트**이다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “색상모듈(kind=color_module)” 동작을 Windows에서 동일하게 재현해야 한다.

색상모듈의 핵심 목적:
- 지정된 “체크 좌표(checkX, checkY)”의 화면 픽셀 RGB를 읽는다.
- 사용자가 저장한 목표 RGB(wantR,G,B)와 비교한다.
- 정확도(accuracyPct)에 따라 허용 오차(tolerance)를 계산한다.
- 조건을 만족하면, “체크좌표”가 아니라 “마커(객체) 위치”를 클릭한다.
- 실제 클릭 순간에는 다른 모든 마커 실행을 잠시 멈춘다(withGlobalPause).

========================
입력 데이터(필수)
========================

전역 상태:
- captureReady: bool
  - Android는 MediaProjection 캡처가 없으면 픽셀 읽기를 하지 않는다.
  - Windows에서는 화면 캡처 엔진이 준비됐는지 여부로 동일 개념을 구현한다.

전역 설정:
- clickPressMsGlobal (10..500): 전역 누름 시간(ms)
- withGlobalPause(action): action 수행 동안 다른 루프(순번/독립/단독)를 멈추게 하는 게이트

Marker 모델(color_module에 필요한 필드):
- index: Int
- kind: "color_module"
- xPx, yPx: Int         // screen 기준 중심 좌표(px) (실제 클릭은 여기로)
- colorR, colorG, colorB: Int  // 목표 RGB(0..255). 하나라도 -1이면 “비활성”
- colorCheckXPx, colorCheckYPx: Int
  - Android에서는 “usable 좌표(px)”로 저장됨 (네비바/컷아웃 제외)
  - 값이 -1이면 기본으로 “마커 위치(usable 기준)”를 체크좌표로 사용
- colorAccuracyPct: Int (50..100)
  - 100이면 완전 일치에 가깝게, 50이면 느슨하게

유틸/함수(Windows에 구현해야 함):
- getScreenSize(): (screenW, screenH)
- getUsableRect(): (left, top, right, bottom)
  - Android는 system bars inset을 제외한 usable 영역을 쓴다.
  - Windows는 “전체 화면(screen)”만 쓰는 정책으로 단순화할 수 있지만,
    Android 매크로(.jws) 호환을 위해 “usable 기반 저장값”을 어떻게 해석할지 명확히 해야 한다.
- readArgbAtScreen(screenX, screenY): Int?  // ARGB 32-bit
  - captureReady==false면 null 반환
  - 내부적으로 최신 프레임 버퍼(스크린샷)에서 픽셀 샘플링
- click(x,y, pressMs): bool

========================
1) 색상모듈이 실행되는 루프 위치
========================

Android 구현에서 color_module은 “독립실행(Independent Loops)” 대상이다.
- 순번실행(Ordered)에는 포함되지 않는다.
- 독립 워커가 주기적으로 fireMarkerOnce(color_module)을 호출한다.

Windows 포팅에서도 동일하게:
- readx2(독립실행) 대상 판정에 color_module을 포함시키고,
- 워커 주기에 따라 color_module을 평가/클릭한다.

========================
2) fireMarkerOnce에서 color_module 분기(핵심 로직)
========================

function fireColorModule(m):
  assert m.kind == "color_module"

  // 2-1) usable -> screen 변환(체크 좌표)
  usable = getUsableRect()
  wU = max(usable.width, 1)
  hU = max(usable.height, 1)

  // 체크좌표(usable px)
  cxU = (m.colorCheckXPx >= 0 ? m.colorCheckXPx : (m.xPx - usable.left))
  cyU = (m.colorCheckYPx >= 0 ? m.colorCheckYPx : (m.yPx - usable.top))
  cxU = clamp(cxU, 0..wU-1)
  cyU = clamp(cyU, 0..hU-1)

  // 체크좌표(screen px)
  checkX = clamp(usable.left + cxU, usable.left .. usable.right-1)
  checkY = clamp(usable.top  + cyU, usable.top  .. usable.bottom-1)

  // 2-2) 목표 RGB가 없으면 아무것도 안 함(안전)
  wantR = m.colorR; wantG = m.colorG; wantB = m.colorB
  if wantR not in 0..255 or wantG not in 0..255 or wantB not in 0..255:
    return SKIP

  // 2-3) 화면 픽셀 읽기(캡처 필요)
  argb = readArgbAtScreen(checkX, checkY)
  if argb == null:
    return SKIP
  r = (argb >> 16) & 0xFF
  g = (argb >>  8) & 0xFF
  b = (argb      ) & 0xFF

  // 2-4) 정확도 -> tolerance 변환(= Android와 동일)
  // acc=100 => tol=0 (거의 exact)
  // acc=50  => tol≈128 (느슨)
  acc = clamp(m.colorAccuracyPct, 50..100)
  tol = clamp( round(((100 - acc) / 100.0) * 255.0), 0..255 )

  okColor =
    abs(r - wantR) <= tol &&
    abs(g - wantG) <= tol &&
    abs(b - wantB) <= tol

  if not okColor:
    return SKIP

  // 2-5) 조건 만족 => “체크좌표”가 아니라 “마커 위치”를 클릭
  // Android는 오버레이 표시 위치와 저장값이 다를 수 있어,
  // markerScreenCenterPx(실제 표시 중심)을 우선 사용한다.
  // Windows는 오버레이가 없으면 보통 저장 xPx/yPx를 그대로 클릭하면 된다.
  (xClick, yClick) = (m.xPx, m.yPx)  // 정책: screen 기준

  // 2-6) 클릭 순간은 단독 실행(withGlobalPause)로 처리
  ok = withGlobalPause {
    click(xClick, yClick, pressMs=clickPressMsGlobal)
  }
  return ok

========================
3) readArgbAtScreen의 핵심 요구사항(Windows 포팅)
========================

Android readArgbAtScreen(screenX, screenY) 특징:
- captureReady가 false면 null
- 현재 프레임 버퍼(frameBytes)에서 픽셀을 읽는다
- 화면 회전/리사이즈 시 캡처 surface를 재구성(ensureCaptureConfiguredToScreen)
- 좌표는 screen 크기에 맞춰 clamp 후,
  캡처 프레임 크기(width/height)로 비율 변환하여 샘플링한다.

Windows 포팅 최소 요구:
- “최신 스크린샷(ARGB 또는 BGRA)”를 주기적으로 갱신하는 캡처 엔진이 있어야 한다.
- readArgbAtScreen(x,y)는:
  - 최근 프레임이 없으면 null
  - (x,y) clamp
  - 버퍼에서 해당 픽셀의 RGB를 반환
- DPI/스케일 이슈:
  - Windows 화면 좌표와 캡처 버퍼 픽셀 좌표가 다를 수 있으니,
    Android처럼 (screen -> frame) 비율 변환을 적용하는 것이 안전하다.

========================
4) 랜덤 실행/랜덤 지연과의 관계(중요)
========================

Android fireMarkerOnce에서:
- color_module은 “랜덤 실행(확률 스킵)” 제외 대상이다.
  즉 randomClickUse가 켜져도 execProbabilityPercent로 스킵하지 않는다.
- 랜덤 지연(jitter)도 color_module은 제외 대상이다.
  (aiDefenseEligibleForRandomDelay에서 color_module 제외)

Windows 포팅에서도 동일하게:
- 독립 워커 주기는 delayMs를 그대로 사용하되,
  color_module에는 jitterPct=0을 강제(랜덤지연 적용 X)
- 확률 스킵도 적용하지 않는다(조건 충족 시만 클릭하는 모듈이므로)

========================
5) 테스트 시나리오(Windows)
========================

- 준비:
  - 캡처 엔진이 screen 좌표 픽셀을 정확히 읽어야 한다.
  - 임의의 앱/창에서 특정 좌표 픽셀이 확실히 (wantR,wantG,wantB)로 나오도록 배경을 준비

- 케이스 A: acc=100 (tol=0)
  - 체크 좌표 픽셀이 정확히 일치할 때만 클릭되는지 확인

- 케이스 B: acc=80
  - tol=round(0.20*255)=51
  - r,g,b 각각 ±51 범위면 클릭되는지 확인

- 케이스 C: captureReady=false
  - readArgbAtScreen이 null -> 클릭이 절대 발생하지 않아야 함

- 케이스 D: withGlobalPause
  - color_module이 클릭하는 순간, 다른 워커(독립/순번)가 동시에 입력을 보내지 않는지 확인
```

---

## 1) Android 원본 코드 근거(요약)

- `fireMarkerOnce()`의 `if (m.kind == "color_module") { ... }` 블록
  - 체크좌표는 `usable` 기준 입력을 screen으로 변환
  - tolerance: `(((100-acc)/100)*255)` 기반
  - 클릭은 `withGlobalPause { click(markerCenter) }`
- `readArgbAtScreen(screenX, screenY)`
  - `captureReady` 없으면 null
  - screen 좌표를 frame 좌표로 비율 변환 후 `frameBytes`에서 샘플
- (참고) `isColorMatch()`는 “링(kind=color)” 조건용이며 exact match로 비교한다.

