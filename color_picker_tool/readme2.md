# 마커 편집모드 + 마커 설정창(동일 기능 이식용 프롬프트)

이 문서는 `ATX2` 프로젝트의 “마커 편집모드”와 “마커 설정창(네이티브 Activity/다이얼로그)” 동작을 **다른 앱에 그대로 복제**하기 위해, AI 코딩 모델에 등록할 **정밀 프롬프트**를 제공합니다.  
아래 “복붙용 프롬프트” 블록을 그대로 사용하고, 패키지명/파일 경로만 대상 프로젝트에 맞게 치환하세요.

---

## 핵심 개념 요약(이 프로젝트 기준)

- **편집모드(이동모드)**: 오버레이 마커가 “드래그 가능”해지는 모드. OFF일 때는 클릭-스루(터치 통과).
- **마커 탭 → 설정창**: 편집모드 ON에서 마커를 탭하면 `MarkerDelayActivity`(네이티브) 설정창을 열어 마커 속성(딜레이/지터/타입 등)을 수정.
- **저장소**: Android `SharedPreferences("FlutterSharedPreferences")`의 `flutter.markers`(JSON 배열 문자열).
- **좌표계 절대 조건**: 모든 UI/좌표는 **Android 네비게이션바 길이를 정확히 제외한 usable 영역** 기준으로 계산/표시/저장/복원.
- **모달(설정창) 중 안전장치**:
  - 설정창이 뜨면 마커/오브젝트를 잠시 숨김(`pushMarkersHidden`)
  - 설정창이 뜨면 편집모드가 켜져 있더라도 **강제로 OFF**(`pushMarkerMoveModeOffByModal`) 후, 닫히면 원복(`pop...`)
  - (키보드/IME 감지/제어 관련 코드는 제거됨)

---

## 복붙용 프롬프트(다른 앱에 “동일 기능” 이식)

아래 내용을 “다른 앱”의 코딩 프롬프트로 등록하고, 모델이 **전체 코드**를 생성/수정하도록 지시하세요.

---

### [프롬프트 시작]

너는 Android(Kotlin) + Flutter(선택) 혼합 앱에서 **오버레이 마커 편집모드 + 마커 설정창**을 기존 앱과 “완전히 동일하게” 구현하는 시니어 모바일 엔지니어다.

#### 0) 목표
1) 편집모드(이동모드)를 켜면 오버레이 마커가 드래그 가능해지고, 끄면 클릭-스루(터치 통과) 상태로 돌아간다.  
2) 편집모드 ON에서 마커를 “탭”하면 네이티브 설정창(`MarkerDelayActivity` 유사)을 열어 마커 설정을 수정/삭제할 수 있다.  
3) 설정 저장/삭제 후 즉시 오버레이가 갱신되고, 앱 재실행 후에도 동일하게 복원된다.  
4) 모든 좌표 계산/표시는 **네비게이션바를 정확히 제외한 usable 영역** 기준이다.

#### 1) 저장 스키마(필수)
Android `SharedPreferences("FlutterSharedPreferences")`:
- 키: `flutter.markers`
- 값: JSON Array 문자열

각 마커 JSONObject는 최소한 아래 필드를 지원해야 한다(기존 데이터 호환 포함).
- **공통**
  - `index` (Int)  
    - 순번 마커: `index > 0` (1..N)
    - 멀티/독립/스와이프 서브 등: `index < 0` (표시는 “0”일 수 있음)
  - `kind` (String) 예: `"click"`, `"swipe_to"`, `"solo_main"`, `"solo_item"`, `"module"`, `"color"` …
  - 좌표: `xPx`,`yPx` 또는 `xPct`,`yPct`(둘 다 유지 권장)
- **클릭/순번 기본값**
  - `delayMs` (Int)
  - `jitterPct` (Int 0..100)
  - (옵션) `randomClickUse` (Bool) — “index>0 AND kind==click”에만 유지
- **스와이프**
  - 메인: `toIndex` (Int !=0) 로 체인 참조
  - 서브: `kind=="swipe_to"` 또는 “index<0 + parentIndex>0” 등 예외 규칙을 고려
  - `pressMs`는 스와이프 duration으로 쓰일 수 있어 **스와이프 메인**에서는 제거하지 말 것
- **색상 링(멀티색상)**
  - 서브 색상 객체: `kind=="color"`, `parentIndex`, `colorR/G/B`
  - 부모 객체는 `useColor`, `colorIndex` 등을 가질 수 있음

#### 2) usable 영역(네비게이션바 제외) — 절대 조건
앱 전역에서 “usable rect”를 한 기준으로 통일하라.

- Android 11+(R):
  - `navigationBars()` 인셋은 **가시성 반영(getInsets)** 으로 계산해서, 풀스크린 게임 등에서 좌표계 불일치(클릭 위치가 안쪽으로 밀리는 문제)를 방지한다.
  - `displayCutout()`은 안전영역이므로 **IgnoringVisibility**로 보강한다.
  - 결과 usable rect:  
    - `left/top/right/bottom` inset을 적용한 bounds 기반 너비/높이
- Android R 미만:
  - `getRealMetrics` + `navigation_bar_height/width` 리소스로 보정(landscape 분기 포함)

좌표 변환 함수는 최소 2개를 제공하라.
- `pctFromAbsPx(xPx,yPx) -> (xPct,yPct)` : usable rect 기준
- `absPxFromPct(xPct,yPct) -> (xPx,yPx)` : usable rect 기준

#### 3) 편집모드(이동모드) 구현(필수)
오버레이 마커 윈도우(WindowManager로 띄운 View들)에 대해:

- **편집모드 OFF**
  - 마커가 **완전 클릭-스루**가 되도록 `FLAG_NOT_TOUCHABLE` 등을 적용한다.
- **편집모드 ON**
  - 마커는 자기 영역만 터치 가능, 나머지 영역은 뒤 앱으로 통과(`FLAG_NOT_TOUCH_MODAL`, `FLAG_NOT_FOCUSABLE` 등)
  - 드래그로 위치 변경 가능(드래그 중 WindowManager `updateViewLayout`)
  - 드래그 중 일정 주기(throttle)로 이동 이벤트를 통지(선택)
  - 드래그 종료 시 `(xPct,yPct)`를 usable 기준으로 계산해 저장/통지

편집모드 토글은 툴바 아이콘(예: “이동 모드”)에서 수행하고,
토글 시 모든 마커의 LayoutParams 플래그를 일괄 갱신하며, 필요 시 연결선(스와이프 링크)은 **편집모드에서만 표시**하도록 한다.

또한 Activity(설정창) 등이 떠 있을 때는 아래 “모달 안전장치”를 반드시 제공하라.
- `pushMarkerMoveModeOffByModal()` : 편집모드 ON이었다면 OFF로 내리고, 이전 상태를 기억
- `popMarkerMoveModeOffByModal()` : 모달 카운트가 0이 되면 이전 ON 상태를 복구

#### 4) 마커 탭 → 설정창 오픈(필수)
마커 View의 터치 처리:
- 편집모드 ON에서만 터치 처리
- **탭(드래그 아님)**이면:
  - (선택) Flutter로 `markerTapped` 이벤트 전송(없어도 됨)
  - 네이티브 설정 Activity를 `FLAG_ACTIVITY_NEW_TASK`로 오픈
  - `Intent extra`: 최소 `index`
  - (선택) 탭 순간의 현재 위치 `xPctNow`,`yPctNow`도 같이 전달(디버그/표시용)

#### 5) 설정창 UI/동작(필수: MarkerDelayActivity 동등 구현)
설정창은 “작은 창(다이얼로그)” 형태로 표시한다(전체 화면 금지).

Activity 생명주기 안전장치:
- `onStart`:  
  - 오브젝트 숨김 `pushMarkersHidden()`  
  - 편집모드 강제 OFF `pushMarkerMoveModeOffByModal()`
- `onStop`:  
  - 오브젝트 복구 `popMarkersHidden()`  
  - 편집모드 원복 `popMarkerMoveModeOffByModal()`

UI 요구:
- 뒤 화면 **dim 제거**: `FLAG_DIM_BEHIND` 제거 + dimAmount 0
- 툴바(좌측 오버레이)에 가리지 않도록:
  - 현재 툴바 폭(px)을 얻어(`getToolbarWidthPx()` 같은 API)
  - 다이얼로그 폭을 `screenWidth - toolbarW - margin` 이하로 clamp
  - `gravity = RIGHT | CENTER_VERTICAL` 정렬
<!-- 키보드/IME 관련 요구사항은 Play 사전검수 이슈 방지를 위해 제거됨 -->

표시/입력 항목(최소):
- 좌표 표시:
  - 우선 오버레이에서 현재 중심 `getMarkerCenterPx(index)`를 가져와 **px로 표시**
  - 없으면 저장된 `xPct/yPct`를 0~100%로 표시(표시만)
- 딜레이 입력:
  - `delayMs` 숫자 입력(0..600000 clamp)
- 지터(랜덤) 선택:
  - 1..100% Spinner(프리미엄/스와이프 종류 등 조건에 따라 0 강제 가능)
- 마커 종류 선택:
  - 최소 5종을 지원: 순번/독립/스와이프/단독/방향모듈
  - 종류 변경 시 기존 JSON을 안전하게 변환(필요하면 index 재배치/parentIndex 갱신/순번 재정렬)

저장 동작(OK 버튼):
- `flutter.markers`를 파싱해서 해당 index의 객체를 찾아 업데이트
- `delayMs`, `jitterPct`, (조건부) `randomClickUse` 저장
- 좌표 설정창에서는 확장 옵션을 기본값으로 되돌릴 수 있음:
  - (키보드 제어 관련 필드는 사용하지 않음)
  - `pressMs`는 스와이프 메인(`toIndex!=0`)이면 유지, 아니면 제거
- 저장 후:
  - (선택) Flutter로 `markerDelayUpdated` 같은 이벤트 전송
  - 오버레이 즉시 갱신 호출(예: `requestRefreshMarkers()`)
  - 창 닫기

삭제 동작(Neutral 버튼):
- 삭제 전 반드시 “오버레이에서 이동된 좌표”를 prefs에 동기화:
  - 예: `syncMarkerPositionsNow()`
- 삭제 규칙:
  - 스와이프 sub(`swipe_to`)는 삭제 불가(버튼 숨김 또는 early return)
  - 삭제 시 연결된 색상 링(`colorIndex`)이 있으면 같이 정리
  - 순번 마커(`index>0` && kind in {click,module}) 삭제 후 남은 순번을 1..N으로 재부여하고,
    `parentIndex/toIndex/colorIndex`처럼 순번을 참조하는 필드는 매핑으로 동기 업데이트
  - 스와이프 체인(`toIndex`)은 연결된 끝점들을 guard 제한(예: 24개)으로 연쇄 삭제
- 삭제 후 오버레이 즉시 갱신 호출

#### 6) 품질 요구(완료 조건)
- 편집모드 OFF일 때 마커는 터치 통과(뒤 앱 클릭 방해 없음)
- 편집모드 ON에서 드래그로 마커 이동 가능, 이동 결과가 usable 기준으로 저장/복원됨
- 마커 탭 시 설정창이 뜨고, 저장/삭제가 즉시 반영됨
- 다이얼로그가 툴바에 가리지 않도록 배치/크기를 clamp
- 네비게이션바 길이를 정확히 제외한 usable 기준이 모든 화면에서 일관됨

#### 7) 산출물 형식
- 변경/추가된 파일은 “파일 단위로 전체 코드”를 보여줘라.
- 기존 프로젝트가 있다면 실제 파일을 스캔하여 동일한 클래스/함수 구조를 유지하라.
- 커맨드 안내가 필요하면 한 줄 체이닝은 `&&`가 아니라 `;`로 작성하라.

### [프롬프트 끝]

---

## ATX2에서 확인된 구현 힌트(참고)

- 편집모드 토글/모달 안전장치:  
  - `AutoClickAccessibilityService.toggleMarkerMoveMode()`  
  - `AutoClickAccessibilityService.pushMarkerMoveModeOffByModal()` / `popMarkerMoveModeOffByModal()`  
  - (키보드/IME 관련 API는 제거됨)
- 마커 탭에서 설정창 오픈:  
  - `MarkerOverlayService`의 터치 리스너에서 `MarkerDelayActivity`를 `Intent.FLAG_ACTIVITY_NEW_TASK`로 실행
- 설정창 저장/삭제 시 동기화:  
  - 삭제 전 `syncMarkerPositionsNow()` 호출  
  - 저장/삭제 후 `requestRefreshMarkers()` 호출




  # 마커 종류별 “표시(시각화) + 작동 원리” 상세 명세 (프롬프트 등록용)

이 문서는 ATX2의 마커 타입 5종을 **다른 앱에서도 똑같이 구현**할 수 있도록,
각 타입의 **표시 규칙(색/라벨/링/연결선/화살표)**과 **작동 원리(저장 필드/실행 루프/일시정지/예외)**를
구현 지시(프롬프트) 형태로 정리한 것입니다.

핵심 전제:
- 저장소는 Android `SharedPreferences("FlutterSharedPreferences")`의 `flutter.markers` (JSON 배열 문자열)
- 좌표는 **네비게이션바 길이를 정확히 제외한 usable 영역** 기준이며, 실행 좌표계는 **px 우선**(실제 화면 제스처 좌표계)

---

## 공통: 데이터 모델(필드) + 좌표계

### 공통 저장 키
- **SP 파일**: `"FlutterSharedPreferences"`
- **키**: `"flutter.markers"`
- **값**: JSON Array string

### 공통 필드(최소)
- **`index` (Int)**:
  - 순번 실행용: `index > 0` (1..N)
  - 독립/스와이프 서브/기타: `index < 0`
  - `index == 0`은 내부적으로 음수로 치환(중복 방지)
- **`kind` (String)**: `"click"`, `"swipe_to"`, `"solo_main"`, `"solo_item"`, `"module"`, `"color"` 등
- **좌표**:
  - `xPx`, `yPx` (Int): **실제 화면 px** (usable rect 기반 절대좌표)
  - `xPct`, `yPct` (Double): 호환/표시용. **px에서 재계산한 pct를 사용**(좌표계 불일치 방지)

### 좌표 규칙(중요)
- 실행(click/swipe)은 **항상 px 기반**으로 수행한다.
- 오버레이가 존재할 때는 저장된 pct/px보다 **오버레이 WindowManager params 중심(px)**를 우선한다.
  - 이유: `xPct=1.05` 같은 값이 저장되면 pct 기반 실행이 화면 끝(100%)에 붙어 잘못된 버튼을 누를 수 있음.

### 표시 공통(원형 객체)
표시 뷰는 타입에 따라 다르다.

- `kind == "color"` 또는 `kind == "swipe_to"`:
  - **링(테두리만)** 뷰로 표시 (`ColorRingView`)
  - 흰색 링 + 1px 더 작은 검은색 링(이중 테두리)
- 그 외(대부분 실행 객체):
  - **버블(원 + 텍스트 라벨)** 뷰로 표시 (`MarkerBubbleView`)

### 라벨 규칙(버블)
버블 내부 텍스트 라벨:
- `kind`가 `solo*`이면 `soloLabel` 표시(예: `A`, `A1`, `A10`)
- 그 외 `index <= 0`이면 `"0"`
- 그 외 `index > 0`이면 `"1"`, `"2"` … (순번 숫자)

### 색상 규칙(버블)
기본 색(실행/상태에 따라 blink로 잠깐 변경될 수 있음):
- **단독 메인**(`kind=="solo_main"`): 초록색
- **단독 서브**(`kind=="solo_item"` 또는 과거 `"solo"`): 연보라색
- **독립 실행 클릭**(`index<0 && kind=="click"`): 빨강색
- **그 외 음수 index(멀티/스와이프 등)**: 보라색
- **순번(양수 index)**: 파랑색

### 실행 시 시각 효과(blink)
- `index > 0`: 빨강 blink (기본 마커 실행 표시)
- `index <= 0`: 형광 초록 blink (독립/멀티 실행 표시 강화)
- `kind=="color"`/`"swipe_to"` 링은 blink 없음

---

## 1) 1.순번실행(ORDERED) — 표시와 작동 원리

### 1.1 표시(시각화)
- 조건(순번 루프 포함):
  - `kind == "click" && index > 0`
  - 또는 `kind == "module" && index > 0 && moduleSoloExec != true` (모듈의 “순번 실행” 모드)
- 버블 라벨: `index` 숫자
- 기본 색: 파랑(실행 시 빨강 blink)
- 편집모드 ON에서만 드래그 가능

### 1.2 작동(실행 원리)
- 실행 스레드(메인 루프)에서 `index` 오름차순으로 1..N 순서대로 반복 실행
- 각 마커 실행은 `fireMarkerOnce(m)`로 수행
- 대기 시간:
  - 기본 `delayMs`
  - 추가 지터: `delayMs * jitterPct / 100` 범위 랜덤(프리미엄 잠금이면 0으로 강제될 수 있음)
  - `sleepPausable(waitMs)`를 사용해 **단독/모듈 실행 중에는 대기 시간이 “감소하지 않게”** 정지했다가 재개

### 1.3 클릭 실행 상세
- 클릭 제스처:
  - 실행 좌표는 “오버레이 중심(px) 우선, 없으면 저장 xPx/yPx, 그래도 없으면 pct→px”
  - press 시간은 전역 설정(예: `getMarkerClickPressMs()`) 사용
- 순번 클릭 마커에서 `randomClickUse=true`면:
  - 80% 확률로 클릭, 20%는 “미클릭”(스킵)
  - 단, 스와이프(toIndex!=0)에는 적용하지 않음

---

## 2) 2.독립실행(INDEPENDENT) — 표시와 작동 원리

### 2.1 표시(시각화)
- 조건(독립/멀티 반복 실행):
  - `index < 0` 이고 주 실행 객체(링 제외)
  - 대표적으로 `kind=="click"`이며, 라벨은 `"0"`
- 색:
  - `index<0 && kind=="click"`은 빨강(실행 시 형광초록 blink)
  - 그 외 음수 실행 객체는 보라색 계열(라벨은 0)

### 2.2 작동(실행 원리)
- 병렬 스레드(각 마커마다 1개 스레드)에서 무한 반복:
  - `while (running)`:
    - 단독/모듈 실행으로 “전체 일시정지” 상태면 풀릴 때까지 대기
    - `fireMarkerOnce(m)` 실행
    - `(delayMs + jitter)` 만큼 대기
- 독립 실행은 다른 마커의 순번 루프와 **동시에 돌아가며**, 단독/모듈 실행 중에는 전부 pause됨.

### 2.3 “색상 조건부 실행”(멀티 색상 연동)
독립 클릭 마커(index<=0)에서 다음 조건이 있으면, 클릭 전에 색상 매치를 검사한다.
- 부모 객체에 `useColor == true`
- 부모 객체에 `colorIndex`가 있고, 해당 index의 `kind=="color"` 링 객체가 존재
- 링 객체에 `colorR/G/B`가 유효

실행 규칙:
- `isColorMatchAtPct(color.xPct, color.yPct, colorR, colorG, colorB)`가 **true일 때만** 부모 클릭을 수행
- false면 이번 tick은 아무 것도 하지 않고 다음 tick으로 넘어감

---

## 3) 3.스와이프(SWIPE) — 표시와 작동 원리

스와이프는 “실행 객체(시작점)” + “서브 링 체인(swipe_to)”로 구성된다.

### 3.1 표시(시각화)
#### 시작점(실행 객체)
- 저장 형태:
  - 일반적으로 `kind=="click"` 이면서 `toIndex != 0` (순번 스와이프)
  - 또는 `index<0`에서 스와이프를 사용하는 경우가 있을 수 있음(독립 스와이프)
- 표시는 **버블**(라벨은 순번이면 숫자, 독립이면 0)

#### 경로 포인트(서브 마커)
- 저장 형태:
  - `kind=="swipe_to"` / `index<0`
  - `parentIndex`: 시작점의 index
  - 체인 연결: 각 노드가 `toIndex`로 다음 노드를 가리킴, 마지막은 `toIndex==0`
  - 마지막 노드는 `moveUpMs`(UP까지 유지) 값을 가짐(기본 700ms)
- 표시는 **링(ColorRingView)** (흰색+검은색 이중 테두리)

#### 편집모드에서의 “연결선”
- 편집모드 ON에서만 표시(OFF면 숨김)
- `toIndex` 체인을 따라 각 구간(from→to)을 연결하는 **선(LinkLineView)** 오버레이를 추가로 띄움
- 선은 터치 불가(FLAG_NOT_TOUCHABLE)이며, 마커 숨김 상태면 알파 0

### 3.2 작동(실행 원리)
스와이프 실행은 `fireMarkerOnce(m)`에서 `toIndex != 0`이면 “스와이프”로 분기한다.

#### 경로 구성
- 시작점의 좌표(from)는 “오버레이 중심(px) 우선”
- 이후 `toIndex` 체인을 따라 `kind=="swipe_to"` 노드를 최대 24개까지 추적
- 각 노드의 좌표는 동일하게 “오버레이 중심(px) 우선”
- 마지막 노드(toIndex==0)의 `moveUpMs`를 **hold 시간**으로 사용(0이면 hold 없음)

#### 제스처 실행
- `pressMs`는 스와이프 “이동 시간(move duration)”으로 사용 (300..1500 등으로 clamp)
- `swipePathPx(path, moveDurationMs)` 또는 `swipePathPxWithHold(path, moveDurationMs, moveUpMs)`
- `dispatchGesture` 실패 방어:
  - 1회 실패하면 짧게 대기 후 1회 재시도
- 비동기 보정:
  - 스와이프 후 `(moveDurationMs + moveUpMs)` 만큼 항상 대기하여 제스처 취소를 방지

#### “스와이프 단독 실행(soloExec)”
시작점 실행 객체에 `soloExec==true`이면:
- 스와이프 실행 중에는 다른 모든 마커(순번/독립/다른 단독/모듈)를 일시정지했다가 종료 후 복구

---

## 4) 4.단독실행(SOLO) — 표시와 작동 원리

단독은 “A~Z 메인(solo_main)”과 “A1~A# 서브(solo_item)”로 구성되며,
**각 알파벳(A,B,C…)이 서로 병렬**로 스케줄링된다.

### 4.1 표시(시각화)
- `kind=="solo_main"`:
  - 버블 라벨: `soloLabel` (예: `"A"`)
  - 색: 초록
- `kind=="solo_item"`:
  - 버블 라벨: `soloLabel` (예: `"A1"`, `"A2"`)
  - 색: 연보라
- 실행 시에는 일반 blink 규칙이 적용되며(실행 표시), 단독 실행 중에는 다른 마커가 pause됨

### 4.2 작동(실행 원리)
단독은 메인 루프와 별도의 “단독 전용 스레드”에서 실행된다.

#### 스케줄
- 대상: `kind=="solo_main" && soloLabel != ""`
- 정렬: `soloLabel`의 알파벳 순(A,B,C…)
- 각 solo_main은 다음 두 대기 구간을 가진다.
1) **첫 실행 전 지연(pre-delay)**: `soloStartDelayMs` (기본 300000ms = 5분)
2) **반복 주기 delay**: `delayMs + jitter` 후 다음 실행 (여기에 다시 pre-delay를 더해 다음 실행 타이머로 사용)

#### 실행 시퀀스(해당 알파벳 1회 실행)
solo_main(A)가 due가 되면:
1) 전역 상태 `soloRunning=true`로 세팅하여 “다른 마커 전체”를 일시정지
2) `fireMarkerOnce(A)` 실행 (A 자체 클릭)
3) A의 콤보 실행:
   - `soloComboCount` (1..10)
   - `kind=="solo_item" && parentIndex == A.index`인 항목에서 `soloLabel`이 A1..A# 인 것을 찾아 순서대로 실행
   - 각 A1..A# 실행 전에는 `delayMs + jitter`만큼 **일반 sleep(Thread.sleep)** 로 기다림  
     (단독 실행 중에도 콤보는 계속 실행되어야 하므로 pausable sleep을 쓰지 않음)
4) 끝나면 `soloRunning=false`로 복구

#### 중요한 “설정 변경 즉시 반영” 규칙
첫 실행 전 지연 단계에서는 `soloStartDelayMs` 변경이 즉시 반영되어야 한다.
- 기존 pre-delay 값과 새 값 차이를 남은 시간(remain)에 가감하여 조정한다.

---

## 5) 5.방향모듈(MODULE) — 표시와 작동 원리

방향모듈은 “마커 중심에서 특정 방향으로 일정 거리(lenPx)만큼 스와이프”를 수행하는 타입이다.
실행 모드가 2개 존재한다.
- **모듈순번**: `index>0 && moduleSoloExec!=true` → 순번 루프에 포함
- **모듈단독(병렬)**: `index<0` 또는 `moduleSoloExec==true` → 독립 병렬 루프에 포함

### 5.1 표시(시각화)
- 표시 뷰: 버블(`MarkerBubbleView`)이지만, **원 밖에 화살표(↑→↓←)**를 붙여 보여준다.
- 원 자체는 “기본 마커 원 크기”를 유지하되,
  - 화살표가 원 밖에 있기 때문에 WindowManager “윈도우 크기”만 여유(padding) 있게 크게 잡는다.
- 모듈 버블은 “2중 원(outer+inner)”로 그려 더 강하게 구분한다.
- 라벨:
  - `index>0`이면 숫자
  - `index<=0`이면 0

### 5.2 작동(실행 원리)
`fireMarkerOnce(m)`에서 `kind=="module"`이면 모듈 실행으로 분기한다.

#### 실행 중 일시정지 정책
- 모듈 실행 중에는 다른 마커들이 일시정지되도록 `moduleRunning=true`를 사용한다.
- 실행이 끝나면 원래 상태로 복구한다.

#### 모듈 제스처
- 기본 파라미터:
  - `moduleLenPx` (30..5000): 이동 거리
  - `pressMs`: 이동 시간(duration)
  - `moduleMoveUpMs`: 도착 후 UP까지 유지(hold)
  - 시작점은 `m.xPct/m.yPct` 기반으로 usable rect → px로 변환한 뒤 clamp (또는 오버레이 중심을 쓰는 정책을 프로젝트와 맞춤)

#### 방향 선택 규칙
- `moduleDirMode == 0` (한 방향씩):
  - `modulePattern`이 나타내는 방향 시퀀스를 “커서(cursor)”로 한 개씩 순환
  - `modulePattern==10`(랜덤)은 U/R/D/L bag를 섞어 하나씩 소모 후 재셔플
- `moduleDirMode == 1` (전방향):
  - 한 tick에 여러 방향을 수행
  - `modulePattern==10`이면 U/R/D/L을 shuffle한 리스트, 아니면 패턴 시퀀스 그대로

각 방향 d에 대해:
- dx/dy를 계산해서 `swipeFromPctDeltaPxWithHold(fromXPct, fromYPct, dx, dy, dur, moduleMoveUpMs)` 수행
- 제스처 비동기 보정:
  - `(dur + 40 + moduleMoveUpMs)` 만큼 sleep

---

## “다른 앱에 동일 구현”을 위한 프롬프트(요약본)

아래를 그대로 프롬프트에 등록해 구현을 강제하세요.

1) 마커 저장소는 `FlutterSharedPreferences.flutter.markers` JSON 배열이며, `kind/index/xPx/yPx/xPct/yPct/delayMs/jitterPct/pressMs/toIndex/parentIndex/...` 필드를 위 규칙대로 유지한다.  
2) 표시:
   - `color`/`swipe_to`는 링(`ColorRingView`), 나머지는 버블(`MarkerBubbleView`)
   - 라벨/색/blink/모듈 화살표/연결선(편집모드에서만) 규칙을 위와 동일하게 구현한다.
3) 실행:
   - 순번(양수 click + 모듈순번)은 메인 루프에서 index 오름차순 실행
   - 독립(음수 click/swipe + 모듈단독)은 마커별 병렬 스레드로 반복 실행
   - 단독(solo_main)은 별도 스레드로 스케줄링하며, 실행 중에는 전체 pause(soloRunning)
   - 스와이프는 `toIndex` 체인을 따라 swipe_to 링들의 px 중심으로 polyline 제스처를 수행하고, 마지막 링의 `moveUpMs`로 hold한다.
   - 모듈은 패턴/방향모드/길이/홀드 규칙대로 스와이프를 수행하고, 실행 중 전체 pause(moduleRunning)
4) 일시정지/재개:
   - solo/module 실행 중에는 다른 마커들의 대기 시간이 감소하지 않도록 pausable sleep을 구현한다.



# 첫번째 아이콘(재생/정지) 연결 코드 분석 기반 “복제 구현용 프롬프트”

이 문서는 ATX2의 **첫번째 아이콘(재생/정지 토글)**에 연결된 네이티브 코드 흐름을 분석해,
프롬프트만으로 다른 앱에서 **동일한 실행/정지 동작, 스레드 구조, 잠금 정책, 일시정지 시간 처리**까지 똑같이 구현할 수 있게 정리한 자료입니다.

대상 원본(참고):  
`packages/autoclicker_plugin/android/src/main/kotlin/com/example/autoclicker_plugin/AutoClickAccessibilityService.kt`

---

## 0) 구현 목표(요구사항)

다른 앱에서 아래 기능을 “완전히 동일하게” 구현하라.

- 툴바 첫번째 아이콘은 **재생/정지(토글)** 이다.
- 재생 시작 시:
  - 마커 편집모드(이동모드)가 ON이면 자동으로 OFF로 전환 후 재생한다.
  - 실행 직전에 오버레이에서 이동한 마커 위치를 prefs에 동기화한다.
  - prefs에서 마커를 **항상 최신으로 다시 읽고**, 실행 대상(순번/독립/단독)을 분류해서 스레드를 구성한다.
  - 실행 중에는 **정지 버튼(=재생 토글)만** 누를 수 있고 나머지는 비활성화된다(단, 광고 잠금/녹화 잠금이 더 우선).
- 정지 시:
  - running=false로 바꾸고 모든 실행 스레드를 interrupt해서 즉시 중단한다.
  - UI 아이콘 상태를 즉시 갱신한다(정지 아이콘 → 재생 아이콘).
- 실행 중 “단독(solo)” 또는 “방향모듈(module)” 실행 구간에서는 다른 마커 실행을 일시정지하며,
  이 일시정지 동안에는 타이머(대기시간/정지시간)가 **감소하지 않고 멈췄다가** 재개 시 이어서 동작한다.
- 중지 조건(설정값):
  - 무한(0), 시간(1), 사이클수(2)

---

## 1) 상태 변수/정책(ATX2와 동일)

### 1.1 핵심 상태
- `running: Boolean` (volatile)
- `markerEditMode: Boolean` (이동모드)
- 실행 스레드:
  - `runnerThread: Thread?` : 순번 실행 루프
  - `parallelThreads: MutableList<Thread>` : 독립/멀티 반복 + 단독 전용 스레드 포함

### 1.2 “실행 중 잠금” 정책
실행 중에는 **재생 토글 버튼만 활성화** 유지:
- UI 컬럼(예: `toolbarCol1`) 내부의 `ImageButton`들을 대상으로
  - `btnPlay`만 예외로 두고 나머지 `isEnabled=false`, `alpha=0.25f`
  - 해제 시에는 `updateToolbarIcons()`로 기존 잠금(설정창/프리미엄/광고 등)을 다시 반영

우선순위:
1) 광고 잠금: 툴바 전체 + 드래그 핸들까지 잠금(최우선)
2) 녹화 잠금: 녹화 버튼만 누르게 잠금(재생 잠금보다 우선)
3) 실행(running) 잠금: 재생 버튼만 누르게 잠금

### 1.3 “일시정지 시간 누적” 정책(단독/모듈)
단독/모듈 실행 중에는 다른 마커가 일시정지되며, 이 기간은 “실행 시간 계산”에서 제외한다.

- `soloRunning`, `moduleRunning` (volatile)
- `pausedTotalMs`, `pauseBeganAtMs`
- `isPausedBySoloOrModule() = soloRunning || moduleRunning`
- 상태 전환 시:
  - pause 진입: `pauseBeganAtMs = now`
  - pause 해제: `pausedTotalMs += now - pauseBeganAtMs` 후 `pauseBeganAtMs=0`
- “활동 시간(elapsed)” 계산:
  - `activeElapsedSince(startedAt) = now - startedAt - pausedSoFar`

대기 함수:
- `sleepPausable(ms)`:
  - pause 상태면 남은 시간을 줄이지 않고 `20ms` 단위로 대기
  - pause가 아니면 `chunk=50ms` 단위로 sleep하면서 remain을 감소(오차 최소화)

---

## 2) UI(재생 아이콘) 요구사항

### 2.1 버튼 생성(동일 동작)
- 재생 버튼은 `ImageButton`(또는 동등)로 생성
- 클릭 콜백: `toggleRunner()`
- tooltip/설명:
  - running이면 “정지(Stop)”, 아니면 “재생(Play)”
- 아이콘 리소스 토글:
  - running=true: stop 아이콘(`ic_tb_stop`)
  - running=false: play 아이콘(`ic_tb_1`)

### 2.2 실행 시작 시 이동모드 자동 OFF
`toggleRunner()`는 다음 규칙을 반드시 따른다.
- running이면 `stopRunner()`
- running이 아니면:
  - `markerEditMode==true`면 `setMarkerMoveModeEnabled(false)`를 먼저 호출
  - 그 다음 `startRunner()`

---

## 3) 실행 시작(startRunner) 알고리즘(동일 동작)

### 3.1 시작 조건/초기화
- 이미 running이면 return
- 모듈 “한방향씩” 런타임 상태 초기화(예: `moduleDirRuntime.clear()`)
- 실행 직전 좌표 동기화:
  - `syncMarkerPositionsFromOverlayToPrefs()` 호출(추가/삭제/설정과 동일 목적)
- prefs에서 마커를 다시 읽음:
  - `initial = readMarkers()`
  - `markersCache = initial`
  - `initial`이 비어있으면 return(= 실행 시작 안 함)

### 3.2 실행 대상 분류(ATX2 동일)
`actions = initial.filter { kind != "color" && kind != "swipe_to" }`

- **순번 루프(seq)**:
  - `kind=="click" && index>0`
  - `kind=="module" && index>0 && moduleSoloExec != true`
  - `index` 오름차순 정렬
- **병렬 반복(parallel)**:
  - `(kind=="click" || kind=="swipe") && index<0`
  - 또는 `kind=="module" && (index<0 || moduleSoloExec==true)`
- **단독(solo_main)**:
  - `kind=="solo_main"` (별도 스레드에서 스케줄)

### 3.3 중지 조건 설정값
별도 SP(예: `"atx_settings"`)에서 읽는다.
- `stopMode` (0=무한, 1=시간, 2=사이클)
- `stopTimeLimitMs`
- `stopCycles`

### 3.4 실행 시작 플래그/아이콘 갱신
- `running=true`
- pause 누적 리셋: `pausedTotalMs=0`, `pauseBeganAtMs=0`
- UI 갱신은 main thread/handler에서 `updateToolbarIcons()`

### 3.5 병렬 반복 스레드 생성(독립/멀티)
각 `m in parallel`에 대해 Thread 생성:
- loop:
  - 단독/모듈 pause 상태면 풀릴 때까지 20ms 단위로 대기
  - `fireMarkerOnce(m)`
  - 지터 계산: `maxJitter = delayMs * jitterPct / 100`(0..600000 clamp)
  - `sleepPausable(delayMs + jitter)`

### 3.6 단독 전용 스레드(소로 스케줄러)
별도 Thread 1개를 병렬 목록에 포함해 실행:
- `remainToNext[soloMainIndex]` 로 “다음 실행까지 남은 시간” 관리
- 신규 solo_main:
  - 첫 실행 전 지연 `soloStartDelayMs`로 초기화
- (중요) 첫 실행 전 지연 단계에서 `soloStartDelayMs`가 변경되면 remain을 즉시 보정
- due가 된 solo_main이 있으면:
  - `setSoloRunning(true)`로 전체 pause 진입
  - `fireMarkerOnce(main)` 실행
  - 이어서 `soloComboCount`만큼 `solo_item(A1..A#)`를 순차 실행
    - 콤보 내부 대기는 `sleepPausable()`이 아니라 일반 `Thread.sleep()`을 사용(단독 스레드 자체가 멈추지 않게)
  - finally: `setSoloRunning(false)`
  - 다음 실행까지 남은시간 = `(main delay + jitter) + soloStartDelayMs`
- due가 없으면 50ms step으로 remain 감소(단, pause 상태에서는 감소하지 않음)

### 3.7 순번 실행 스레드(runnerThread)
Thread 1개 생성:
- `startedAt = elapsedRealtime()`, `cyclesDone=0`
- while(running):
  - 시간 제한(stopMode==1):
    - `activeElapsedSince(startedAt) >= stopTimeLimitMs`면 `stopRunner()` 후 break
  - `seq`가 비면 120ms 대기 후 continue
  - for(m in seq):
    - pause 상태면 풀릴 때까지 20ms 단위 대기
    - (워밍업) `markersChangedSinceLastRun && m.index==1 && (m.kind=="click"||"module")`이면:
      - `markersChangedSinceLastRun=false`
      - `warmupMs = (m.kind=="click" && m.toIndex!=0) ? 200 : 1000`
      - sleep(warmupMs)
    - `fireMarkerOnce(m)`
    - `sleepPausable(delayMs + jitter)` (프리미엄 잠금이면 jitter 0)
  - seq 한 바퀴면 cyclesDone++
  - 사이클 제한(stopMode==2):
    - cyclesDone>=stopCycles면 `stopRunner()` 후 break
- finally:
  - `running=false`
  - mainHandler로 `updateToolbarIcons()`

---

## 4) 정지(stopRunner) 알고리즘(동일 동작)

- `running=false`
- `setSoloRunning(false)`로 pause 플래그 정리
- `runnerThread?.interrupt()`
- `parallelThreads`의 모든 thread를 interrupt
- `parallelThreads = mutableListOf()`
- `runnerThread = null`
- mainHandler로 `updateToolbarIcons()` (play 아이콘 복구 + 잠금 해제 반영)

---

## 5) 구현 산출물 지시(다른 앱에 적용)

너는 아래 산출물을 **전체 코드**로 생성/수정하라.

1) 오버레이 툴바 UI에서 첫번째 아이콘(재생/정지)을 생성하고 `toggleRunner()`에 연결  
2) `toggleRunner()` / `startRunner()` / `stopRunner()` 구현  
3) “실행 중 잠금” 함수 구현: `setToolbarButtonsLockedForRunning(locked)`  
4) 단독/모듈 pause 시간 누적 로직 + `sleepPausable()` 구현  
5) stopMode(무한/시간/사이클) 지원  
6) prefs(`flutter.markers`)에서 마커를 읽어 분류(seq/parallel/solo)하는 로직 구현  

출력 형식:
- 변경/추가된 파일을 파일 단위로 전체 코드 출력
- 커맨드 안내가 필요하면 한 줄 체이닝은 `&&`가 아니라 `;`로 작성

---

## 6) 완료 조건(테스트 관점)

- 재생 버튼을 누르면 실행이 시작되고 아이콘이 stop으로 바뀐다.
- 실행 중에는 재생(정지) 버튼만 눌리고 나머지는 비활성화(알파 0.25) 된다.
- 재생 중 다시 버튼을 누르면 즉시 중단되고 아이콘이 play로 바뀐다.
- solo/module 실행 구간에는 다른 마커가 pause되며, pause 동안 시간 제한/대기시간이 줄지 않는다.
- stopMode=시간/사이클이 정확히 동작한다.

# 3) 스와이프: “원 2개(링)” + “연결선” 구현 프롬프트 (ATX2 동등 구현)

이 문서는 ATX2의 **스와이프 마커 표시**(서브마커 링 2중 원 + 연결선)를,
프롬프트만으로 다른 앱에서 **완전히 동일하게** 구현하기 위한 지시서입니다.

대상 구현(원본 힌트):
- 링(원 2개): `ColorRingView.kt`
- 연결선: `LinkLineView.kt` + `AutoClickAccessibilityService.updateSwipeLinkLines()`

---

## 0) 전제(데이터 구조)

저장소: Android `SharedPreferences("FlutterSharedPreferences")`의 `flutter.markers` JSON 배열.

스와이프는 다음 구조를 가진다.

- **시작점(실행 객체)**: `toIndex != 0` 인 “실행 마커”
  - 예: `kind=="click"` (순번 스와이프), 또는 독립 스와이프 등
- **서브 마커(경로 노드)**: `kind=="swipe_to"` 인 마커들(보통 `index < 0`)
  - `parentIndex`: 시작점의 index
  - `toIndex`: 다음 서브 노드 index (마지막은 0)

표시 규칙:
- `kind=="swipe_to"` 는 **버블이 아니라 링(2중 원)** 으로 표시한다.
- 편집모드(이동모드)에서만 `시작점→서브→서브...`를 **연결선**으로 표시한다.

---

## 1) “원 2개(링)” 만들기 프롬프트 (ColorRingView)

### 1.1 요구 UI
`kind=="swipe_to"` 마커를 아래 형태로 그려라.

- 채움(fill) 없음: 내부 투명
- 바깥 원: **흰색 테두리**
- 안쪽 원: 바깥 원보다 반지름 **1px 작은** 원을 **검은색 테두리**로 추가
- 두 원의 strokeWidth는 동일(약 dp(2.6) * scaleFactor)
- 원의 중심은 뷰의 정중앙
- `OverlayUiScale.scaleFactor`(또는 동일 개념)로 전체 dp가 스케일되게 구현

### 1.2 구현 지시(코드 생성)
Android Kotlin에서 `View`를 상속한 `ColorRingView(context)` 클래스를 생성하라.

- `Paint(Paint.ANTI_ALIAS_FLAG)` 2개:
  - white stroke: `Color.argb(220,255,255,255)`
  - black stroke: `Color.argb(220,0,0,0)`
  - `style = Paint.Style.STROKE`
  - `strokeWidth = dp(2.6f)`
- `onDraw`:
  - `r = min(width,height)/2`
  - `outer = r - stroke/2`
  - `drawCircle(cx,cy,outer, white)`
  - `inner = (outer - 1f).coerceAtLeast(0f)`
  - `drawCircle(cx,cy,inner, black)`
- `dp(v)`:
  - `v * density * OverlayUiScale.scaleFactor`

### 1.3 링을 “마커 윈도우”에 연결하는 규칙
마커 오버레이를 생성할 때:

- `if (m.kind == "swipe_to") view = ColorRingView(context)`
- (참고) `kind=="color"`도 동일 링 뷰를 쓰는 구조라면 같이 처리해도 무방

---

## 2) “연결하는 선” 만들기 프롬프트 (LinkLineView + updateSwipeLinkLines)

### 2.1 선(뷰) 요구사항: LinkLineView
스와이프 경로의 각 구간(from→to)을 화면 위에 선으로 그린다.

- 색: `0x6600A8FF` (연한 반투명 파란색)
- cap/join: ROUND
- 두께: **마커 원의 반지름(px)** 수준
  - 기준 지름: `34dp * density * scaleFactor` (최소 `14dp * density`)
  - `strokeWidth = diameter/2`
- `setPoints(x1,y1,x2,y2)`로 선의 양 끝점을 갱신하고 invalidate

### 2.2 연결선 오버레이(윈도우) 요구사항

연결선은 “뷰 1개로 전체를 그리는 방식”이 아니라, **구간(fromIndex → toIndex)마다 LinkLineView 1개씩** 만들고,
각 LinkLineView를 **화면 전체 크기(usableW x usableH)** 오버레이로 띄운 뒤, 그 안에서 선 1개를 그리는 방식으로 구현하라.

#### 표시/숨김 정책(ATX2 동일)
- 연결선은 **편집모드(이동모드)에서만 표시**한다.
  - 편집모드 OFF이면 연결선 윈도우를 전부 제거한다.
- `markersHidden || userObjectsHidden` 이 true인 “숨김 상태”에서는 연결선의 `alpha=0f`, 아니면 `alpha=1f`.
- 연결선은 사용자 터치를 절대 받지 않는다(항상 클릭-스루).

#### WindowManager 파라미터(필수)
각 구간별 `LinkLineView`를 다음 파라미터로 오버레이에 추가하라.

- **type**: `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`
- **flags**:
  - `FLAG_NOT_TOUCHABLE`
  - `FLAG_NOT_FOCUSABLE`
  - `FLAG_LAYOUT_NO_LIMITS`
  - `FLAG_LAYOUT_IN_SCREEN`
- **format**: `PixelFormat.TRANSLUCENT`
- **size**: `width=usableW`, `height=usableH`
- **gravity**: `Gravity.TOP | Gravity.LEFT`, `x=0`, `y=0`

#### 선의 좌표계(중요)
선의 endpoints는 “마커 윈도우의 중심 좌표(px)”로 계산한다.

- 마커 윈도우는 `WindowManager.LayoutParams`로 관리된다.
- 중심 좌표는 다음으로 계산한다(ATX2 동일):
  - `r = params.width / 2f`
  - `xCenter = params.x + r`
  - `yCenter = params.y + r`

### 2.3 “스와이프 체인”을 선으로 연결하는 알고리즘(updateSwipeLinkLines)

다음 알고리즘을 Kotlin으로 구현하라(ATX2 동등).

#### 2.3.1 자료구조(필수)
- `markerWins: Map<Int, MarkerWin>`  
  - key=index, value는 `(index, kind, view, params)` 포함
- `linkWins: MutableMap<Long, LinkWin>`  
  - key는 (fromIndex,toIndex) 쌍을 long으로 인코딩한 값
- `linkKey(a,b)` 인코딩은 음수 index도 안전하게 포함해야 한다:

```kotlin
private fun linkKey(a: Int, b: Int): Long {
  // (a,b) 쌍을 long 키로 인코딩 (음수 포함 안전)
  return (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
}
```

#### 2.3.2 시작점(starts) 정의
`markers` 리스트에서, 아래 조건을 만족하는 모든 마커를 “스와이프 시작점”으로 본다.

- `toIndex != 0`
- `kind != "swipe_to"`
- `kind != "color"`

즉, 시작점은 실행 객체이며, 서브 노드(`swipe_to`)와 색상 링(`color`)은 시작점이 아니다.

#### 2.3.3 keepKeys 계산(구간 유지 대상 만들기)
각 시작점에 대해 체인을 따라가며 “구간(from→to)” 키를 전부 수집한다.

- 최대 구간 수: `maxSeg = 20`
- 체인 탐색:
  - `curFrom = start.index`
  - `curTo = start.toIndex`
  - while(curTo != 0 && seg < maxSeg):
    - keepKeys.add(linkKey(curFrom, curTo))
    - next = markers.firstOrNull { it.index == curTo && it.kind == "swipe_to" }?.toIndex ?: 0
    - curFrom = curTo
    - curTo = next

#### 2.3.4 제거(remove missing)
`linkWins.keys` 중 keepKeys에 없는 것은 전부 `wm.removeView(view)` 하고 map에서 제거한다.

#### 2.3.5 생성/업데이트(upsert)
각 시작점 체인을 다시 순회하며 각 구간(from→to)을 화면에 반영한다.

- 조건:
  - `fromWin = markerWins[curFrom]` 가 없으면 break
  - `toWin = markerWins[curTo]` 가 없으면 break
  - `toWin.kind`가 `"swipe_to"`가 아니면 break (서브 노드가 아니면 선 연결 중단)
- endpoints:
  - fromCenter = center(fromWin.params)
  - toCenter = center(toWin.params)
- key = linkKey(curFrom, curTo)

**신규 구간**(linkWins[key]==null)이면:
- `LinkLineView(context)` 생성
- `setPoints(fromCenter.x, fromCenter.y, toCenter.x, toCenter.y)`
- `alpha = hiddenNow ? 0f : 1f`
- 위 “WindowManager 파라미터”로 `wm.addView(view, params)`

**기존 구간**이면:
- `params.width/height`를 최신 usableW/H로 갱신
- `view.setPoints(...)` 재호출
- `view.alpha` 갱신
- `wm.updateViewLayout(view, params)`

마지막에 다음 노드로 진행:
- `next = markers.firstOrNull { it.index == curTo && it.kind == "swipe_to" }?.toIndex ?: 0`
- `curFrom = curTo; curTo = next; seg++`

### 2.4 호출 타이밍(언제 갱신해야 하는가)
연결선은 아래 상황에서 “즉시 갱신”되어야 한다.

- 마커 오버레이가 업데이트될 때(마커 생성/삭제/종류 변경/좌표 변경 후)
- 편집모드가 ON/OFF 토글될 때
- 드래그로 마커를 이동할 때(이동 중 또는 이동 후)

최소 구현:
- 마커 윈도우 업데이트 함수 끝에서 `updateSwipeLinkLines(markers)` 호출
- 편집모드 OFF일 때는 `hideAllLinkWindows()`를 즉시 수행

---

## 3) 완료 조건(체크리스트)

- `swipe_to` 서브 마커는 **링(흰+검 이중 원)**으로 표시된다.
- 편집모드 ON에서만 시작점→서브→서브… 구간이 선으로 연결된다.
- 선 두께는 “원 반지름” 수준으로 보이고, 반투명 파란색이다.
- 마커를 이동하면 선 endpoints가 즉시 따라온다.
- 숨김 상태(`markersHidden` 또는 `userObjectsHidden`)에서는 선과 링이 함께 숨겨진다(alpha=0).

# 마커종류 4) 단독실행(SOLO) 코드 분석 기반 “복제 구현용 프롬프트”

이 문서는 ATX2의 **마커종류 4. 단독실행**(A~Z 메인 + A1..A# 콤보)을,
프롬프트만으로 다른 앱에서 **동일한 표시/저장/생성/변환/스케줄/실행/일시정지**까지 그대로 구현하도록 만드는 지시서입니다.

원본 구현 힌트(ATX2):
- 생성: `AutoClickAccessibilityService.addSoloMarkerDefault()`
- 타입 변환/삭제/설정: `MarkerDelayActivity` 내부 로직
- 실행 스케줄: `AutoClickAccessibilityService.startRunner()` 내부 `soloThread`
- 단독 TEST: `runSoloMainOnceBlocking()`, `showSoloTestCountdownAndRun()`

---

## 0) 단독실행이란? (기능 정의)

단독실행은 아래 규칙으로 동작한다.

- **solo_main**: 알파벳 메인 마커(예: A, B, C...)  
  - 각 메인은 **서로 독립적으로 스케줄**된다(병렬 개념).
- **solo_item**: 해당 알파벳의 콤보 서브 마커(예: A1..A10)  
  - 메인이 실행될 때, 설정된 콤보 개수만큼 서브를 **순서대로 직렬 실행**한다.
- 단독실행이 트리거되는 동안에는 **다른 모든 마커(순번/독립/스와이프/모듈/다른 단독)**을 일시정지한다.
  - 그리고 일시정지 동안에는 다른 마커들의 타이머가 줄어들지 않는다(남은 시간 유지).

---

## 1) 저장 포맷(SharedPreferences) — 필수

저장소:
- SP: `"FlutterSharedPreferences"`
- key: `"flutter.markers"`
- value: JSON Array String

### 1.1 solo_main 필드(필수)
- `index`: **고정 구간**을 사용(ATX2 v2 규칙)
  - A = 20001 ... Z = 20026
  - 규칙: `20000 + (letter - 'A' + 1)`
- `kind`: `"solo_main"`
- `soloLabel`: `"A"`..`"Z"` (반드시 대문자)
- `xPx`,`yPx` (+ `xPct`,`yPct` 유지 권장): 위치
- `delayMs`: 기본 1000 (권장 범위 0..600000)
- `jitterPct`: 기본 50 (0..100)
- `pressMs`: 기본 90
- `inputMode`: 단독 메인은 **항상 `"mouse"` 강제** (키보드 모드 잔재로 “클릭 안 함” 방지)
- `keyToken`: 기본 `"ENTER"` (단독에서는 실사용 안 해도 호환 위해 유지)
- `soloComboCount`: 1..10 (기본 1)
- `soloStartDelayMs`: 첫 실행 전 지연(ms) (기본 300000 = 5분)

### 1.2 solo_item 필드(필수)
- `index`: 자유(단, 다른 타입과 충돌 없게)  
- `kind`: `"solo_item"`
- `parentIndex`: 연결된 solo_main index (예: A 메인이 20001이면 parentIndex=20001)
- `soloLabel`: `"A1".."A10"` 같은 라벨 (대문자)
- `delayMs`,`jitterPct`,`pressMs`,`inputMode`,`keyToken`: 클릭 마커와 동일한 기본 규칙 적용 가능

---

## 2) 표시(시각화) 규칙 — ATX2 동일

표시는 버블(`MarkerBubbleView`)을 사용한다고 가정한다.

- 라벨:
  - solo_main/solo_item은 `soloLabel`을 표시 (예: A, A1, A10)
- 색:
  - solo_main: 초록색
  - solo_item: 연보라색
- 실행 표시(blink):
  - 메인/서브 모두 “클릭 실행 시” blink를 적용(프로젝트 공통 blink 정책)

---

## 3) 생성(단독 메인 추가) — ATX2 동등 알고리즘

단독 메인 생성 함수(예: `addSoloMarkerDefault()`)를 다음 규칙으로 구현하라.

1) 오버레이에서 이동한 좌표를 prefs에 동기화(`syncMarkerPositionsFromOverlayToPrefs()`).
2) `flutter.markers`를 JSON 배열로 파싱.
3) 이미 존재하는 solo_main 라벨(A~Z)을 수집:
   - kind가 `"solo"` 또는 `"solo_main"`인 객체의 `soloLabel` 첫 글자(대문자)를 used set에 추가.
4) `'A'..'Z'` 중 사용되지 않은 첫 글자를 `next`로 선택.
   - 없으면 “단독마커는 A~Z(26개)까지만 생성” 에러 처리.
5) `index = 20000 + (next - 'A' + 1)` 계산.
   - 동일 index가 이미 존재하면 생성 취소(중복 방지).
6) 생성 위치:
   - 기준점은 (0.5,0.5) 이되, “원 4개 만큼 아래”로 오프셋한 baseY를 사용(ATX2 정책).
   - 기존 객체와 겹치지 않는 빈 슬롯을 탐색(`findFreeSpawnPct` 유사).
   - 공간이 없으면 생성 실패.
7) JSONObject 생성:
   - `kind="solo_main"`, `soloLabel=next`
   - 기본값: `delayMs=1000`, `jitterPct=50`, `pressMs=90`, `inputMode="mouse"`, `keyToken="ENTER"`
   - `soloComboCount=1`
   - `soloStartDelayMs=300000`
   - 좌표는 px/pct 둘 다 저장(프로젝트 좌표 규칙 준수)
8) 배열에 append, SP에 저장, 오버레이 갱신 호출, 토스트/피드백.

---

## 4) 타입 변환: 다른 타입 → SOLO (저장 시 변환)

설정창(예: `MarkerDelayActivity`)에서 “마커 종류”를 SOLO로 바꿀 때는,
기존 객체를 아래 규칙으로 변환하라.

- 새 라벨 `next`는 기존 `nextSoloLetter()` 규칙으로 선택(사용되지 않은 A~Z).
- 새 index는 `20000 + (next - 'A' + 1)`.
- 객체 변환:
  - `kind="solo_main"`
  - `soloLabel=next`
  - `soloComboCount=1`
  - `soloStartDelayMs=300000`
  - `inputMode="mouse"` 강제
  - `pressMs`가 없으면 90을 넣기
  - `toIndex`, `parentIndex` 제거(스와이프/서브 연결 제거)
  - 좌표는 유지하되 `xPx/yPx`가 없으면 보강
- 변환 후:
  - 기존이 순번(index>0)이었다면 순번 마커를 1..N으로 재정렬(빈 번호 방지)

또한 변환 전에, 기존 객체가 스와이프 체인(toIndex) 또는 solo child를 가지고 있었다면
연결된 서브를 먼저 정리(remove)해야 한다(데이터 고아 방지).

---

## 5) 실행 스케줄(핵심): soloThread 로직 — ATX2 동등 구현

단독실행은 러너(startRunner)가 시작될 때 “별도 스레드 1개”로 수행한다.

### 5.1 스레드 내부 상태
- `remainToNext: Map<soloMainIndex, Long>` 다음 실행까지 남은 시간(ms)
- `preDelaySeen: Map<soloMainIndex, Int>` 마지막으로 관측한 pre-delay(ms)
- `executedOnce: Set<soloMainIndex>` 최소 1회 실행 완료 여부

### 5.2 메인 목록
주기적으로 최신 마커를 읽어 아래 목록을 만든다.
- `mains = markers.filter { kind=="solo_main" && soloLabel not blank }`
- 정렬 기준: `soloLabel` 알파벳 순(A,B,C...)

### 5.3 신규 메인 초기화(첫 실행 전 지연)
새로 등장한 solo_main은:
- `remainToNext[idx] = soloStartDelayMs`
- `preDelaySeen[idx] = soloStartDelayMs`

### 5.4 “첫 실행 전 지연 변경 즉시 반영” (중요)
아직 `executedOnce`에 없는 메인에 대해,
`soloStartDelayMs`가 바뀌면 남은시간을 아래처럼 보정한다.

- oldPre = preDelaySeen[idx]
- newPre = current soloStartDelayMs
- remain = remainToNext[idx]
- `remainToNext[idx] = max(0, remain + (newPre - oldPre))`
- `preDelaySeen[idx] = newPre`

### 5.5 due 실행(메인 1회 + 콤보)
`due = mains.firstOrNull { remainToNext[idx] <= 0 }` 가 있으면 실행:

1) `setSoloRunning(true)`로 전체 pause 진입
2) `fireMarkerOnce(due)` 실행 (A)
3) 콤보:
   - letter = due.soloLabel 첫 글자
   - count = due.soloComboCount (1..10)
   - items = markers.filter { kind=="solo_item" && parentIndex == due.index } 정렬(A1..A10)
   - for k=1..count:
     - 해당 `Ak` item을 찾으면:
       - wait = item.delayMs + jitter
       - **여기서는 `sleepPausable()` 금지**, 반드시 `Thread.sleep(wait)` 사용
         - 이유: soloRunning=true인 동안 sleepPausable은 현재 스레드까지 멈춰 콤보가 실행되지 않는 문제가 생길 수 있음.
       - `fireMarkerOnce(item)`
       - 추가로 `Thread.sleep(20)` (너무 붙어서 누락 방지)
4) finally: `setSoloRunning(false)`
5) 다음 실행까지 남은 시간 재설정:
   - pre = due.soloStartDelayMs
   - executedOnce.add(idx)
   - mainWait = due.delayMs + jitter
   - `remainToNext[idx] = mainWait + pre`

### 5.6 due가 없을 때 타이머 감소
step=50ms로 주기 sleep:
- pause 상태가 아니면 remainToNext의 모든 값을 step만큼 감소
- pause 상태면 감소하지 않음

---

## 6) 단독 TEST(선택 기능, ATX2 동일)

설정창의 TEST 버튼을 위해:
- `runSoloMainOnceBlocking(mainIndex)`:
  - 러너(running)와 무관하게 solo_main 1회 + 콤보 1회 실행
  - 내부에서 `setSoloRunning(true)`로 다른 마커 pause
- 카운트다운+실행(오버레이 메시지 표시 등):
  - TEST 중에는 soloStartDelayMs를 임시로 5초로 바꾸었다가 종료 후 원복(ATX2 정책)

---

## 7) 완료 조건(동일성 체크)

- solo_main을 추가하면 A~Z 중 비어있는 글자가 생성되고 index는 20001..20026으로 고정된다.
- solo_main은 초록, solo_item은 연보라, 라벨은 A / A1.. 형태로 표시된다.
- 러너 실행 중 solo_main이 due 되면:
  - 다른 마커가 멈추고(A~Z 단독 실행), A → A1..Ak가 순서대로 실행된다.
  - 실행이 끝나면 다른 마커들이 “남은 시간 그대로” 재개된다.
- 첫 실행 전 지연(soloStartDelayMs)을 바꾸면, 첫 실행이 그 변경을 즉시 반영한다.

---

## 8) 산출물 지시(코딩 모델에게)

- 위 규칙을 만족하도록 필요한 파일/클래스를 생성/수정하고,
- 변경된 파일은 **파일 단위 전체 코드**로 출력하라.
- 커맨드 안내가 필요하면 한 줄 체이닝은 `&&` 대신 `;` 를 사용하라.

# 메뉴 툴바(오버레이) 다른 앱 이식용 “권한/구조/구현 프롬프트”

이 문서는 ATX2의 “메뉴 툴바”를 다른 앱에서 그대로 쓰기 위해 필요한 **권한(접근성)**과
**오버레이 생성 방식(TYPE_ACCESSIBILITY_OVERLAY)**, **UI 구성/드래그/잠금 정책**, **네비게이션바 제외 usable 좌표계**
까지 포함하여, **프롬프트만으로 동일 구현**할 수 있게 정리한 지시서입니다.

원본 힌트(ATX2):
- 툴바 생성: `AutoClickAccessibilityService.showToolbarOverlay()`  
- 툴바 표시 API: `AutoClickAccessibilityService.showToolbar()`  
- 접근성 서비스 선언: `AndroidManifest.xml` + `res/xml/autoclick_accessibility_service.xml`
- 접근성 안내/설정 유도: `AccessibilityIntroActivity`
- Flutter 브릿지: MethodChannel `"atx_toolbar/autoclick"` 의 `ensurePermissionsAndShowToolbar`

---

## 0) 결론: 이 툴바는 어떤 권한으로 뜨나?

ATX2 메뉴 툴바는 **SYSTEM_ALERT_WINDOW**(다른 앱 위에 표시) 권한이 아니라,
**AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY**로 표시됩니다.

장점:
- 안드로이드에서 “다른 앱 위” 오버레이를 SYSTEM_ALERT_WINDOW 없이 구현 가능
- 동일 서비스에서 `dispatchGesture`로 클릭/스와이프 실행까지 연계 가능

필수 조건:
- 사용자가 **접근성 서비스**를 시스템 설정에서 직접 ON 해야 함.

---

## 1) 필요한 권한/매니페스트/리소스(필수)

### 1.1 AndroidManifest에 AccessibilityService 등록
아래 형태로 서비스 선언을 추가하라(패키지/클래스명은 대상 앱에 맞게).

- `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`
- `<intent-filter>`에 `android.accessibilityservice.AccessibilityService`
- `<meta-data android:name="android.accessibilityservice" android:resource="@xml/<service_config>" />`

### 1.2 accessibility-service 설정 XML 작성
`res/xml/autoclick_accessibility_service.xml` 같은 파일을 생성하고 아래 속성을 포함하라(ATX2 동등).

- `android:accessibilityEventTypes="typeAllMask"`
- `android:accessibilityFeedbackType="feedbackGeneric"`
- `android:accessibilityFlags="flagDefault"`
- `android:canRetrieveWindowContent="false"` (필요 없으면 false)
- `android:canPerformGestures="true"` (**제스처 실행 필요 시 필수**)
- `android:notificationTimeout="0"`
- `android:description="@string/<desc>"`

---

## 2) UX: 접근성 미설정 시 안내 화면/경고 문구(권장, ATX2 방식)

다른 앱에서 동일 UX를 원하면:
- 앱 시작 시(또는 툴바 표시 요청 시) 접근성 서비스 ON 여부를 검사한다.
- OFF면 “접근성 안내/경고(Disclosure)” 화면(Activity)을 띄우고,
  OK 버튼에서 `Settings.ACTION_ACCESSIBILITY_SETTINGS`로 이동시킨다.

이 Activity는:
- 네비게이션바 제외 usable 크기 기준으로 다이얼로그처럼 가운데 표시
- 문구(Disclosure/개인정보 수집 없음/사용자 제어 가능) 포함
- 설정으로 돌아와 ON 상태가 되면 서비스 인스턴스를 확인하며 재시도 후 자동으로 툴바를 띄우고 종료

---

## 3) 툴바 오버레이의 핵심 구조(필수)

### 3.1 오버레이 타입
툴바는 WindowManager로 다음 타입을 사용해 띄운다.

- **type**: `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`
- **flags**:
  - `FLAG_NOT_FOCUSABLE`
  - `FLAG_NOT_TOUCH_MODAL` (툴바 영역 밖은 뒤 앱으로 터치 전달)
  - `FLAG_LAYOUT_IN_SCREEN`
  - `FLAG_LAYOUT_NO_LIMITS`
- **format**: `PixelFormat.TRANSLUCENT`
- **gravity**: `Gravity.CENTER_VERTICAL | Gravity.LEFT`
- 초기 위치: `x=0`, `y=0`

### 3.2 뷰 구성(ATX2 동등)
툴바는 `LinearLayout(세로)` 1열 구조이며, 버튼/핸들을 위에서 아래로 배치한다.

- 드래그 핸들(TextView `"≡"`) 1개
- 아이콘 버튼들(ImageButton) 여러 개
  - 재생/정지 토글
  - 마커 추가(+)
  - 녹화
  - 설정
  - 이동모드(편집모드) 토글
  - 전체 객체 숨김/표시 토글

버튼 생성 함수(권장):
- iconRes, contentDescription, tipProvider, onClick을 받아 ImageButton 생성
- 클릭 시 “툴팁을 먼저 표시”하고(반응성), 그 다음 실제 동작 수행
- 버튼 배경은 투명, 아이콘은 CENTER_INSIDE

---

## 4) 화면 크기/네비게이션바 제외(절대 조건)

툴바 크기 산정은 “네비게이션바 제외 usable 크기” 기준이어야 한다.

- usableW/H를 얻는 함수를 구현하고, 모든 UI에서 동일 기준을 사용하라.
- ATX2 정책(핵심):
  - 가로/세로 회전과 무관한 “짧은 변(min(usableW, usableH))”을 기준으로 툴바 세로 길이를 계산
  - targetToolbarH ≒ baseLen * 0.98
  - 아이콘 개수(col1Count)로 나누어 버튼 높이(btnH) 산출
  - 폭(btnW)은 btnH에 비례 + 사용자 스케일(toolbarScalePct) 반영

이 요구는 “모든 화면에서 Android 네비게이션바 길이를 정확히 제외” 규칙을 만족해야 한다.

---

## 5) 드래그/최소화 동작(필수)

### 5.1 드래그는 핸들에서만
핸들에 `setOnTouchListener`를 달고:
- DOWN: rawX/rawY, params.x/y 저장
- MOVE: dx/dy로 params.x/y 업데이트 후 `wm.updateViewLayout`
  - ATX2는 “제한 없이 자유롭게(화면 밖도 가능)” 이동
- UP: 드래그가 아니라 탭이면 “최소화 토글” 실행

### 5.2 최소화(COLLAPSED) 상태
COLLAPSED에서는:
- 핸들과 재생 버튼만 보이게(나머지 버튼 visibility=GONE)
- FULL로 복귀할 때만 “화면 밖이면 초기 위치로 리셋” 같은 복구 로직을 적용(ATX2 정책)

---

## 6) 표시 API(showToolbar)와 “명시적 실행 의도” 저장(ATX2 방식)

서비스 외부(앱/플러터/플러그인)에서 호출할 공개 API를 제공하라.

- `showToolbar()`:
  - main thread에서 수행
  - “사용자가 툴바를 실행한 의도” 플래그를 prefs에 저장(예: autoStartToolbar=true)
  - toolbarView가 없으면 overlay 생성(showToolbarOverlay)
  - 있으면 상태를 FULL로 강제(setToolbarState(FULL))
  - 아이콘/잠금 상태 갱신(updateToolbarIcons)

---

## 7) 실행 중/광고 중/녹화 중 잠금 정책(중요)

다른 앱에서도 동일 UX를 위해 아래 잠금 정책을 구현하라.

- **광고 중 잠금(최우선)**:
  - 모든 ImageButton 비활성화 + alpha 0.25
  - 드래그 핸들도 비활성화
  - 광고 종료 시 현재 상태를 다시 반영(updateToolbarIcons)

- **녹화 중 잠금**:
  - 녹화 토글 버튼만 남기고 나머지 ImageButton 비활성화
  - 해제 시 updateToolbarIcons로 복구

- **재생(실행) 중 잠금**:
  - 재생(정지) 버튼만 활성화 유지, 나머지 버튼 비활성화
  - 해제 시 updateToolbarIcons로 복구

---

## 8) 다른 앱에 그대로 구현시키는 “프롬프트 명령어”

아래를 코딩 모델에 그대로 입력해, 동일한 메뉴 툴바를 구현하게 하라.

### [프롬프트 시작]
너는 Android(Kotlin) 앱에서 AccessibilityService 기반 “메뉴 툴바 오버레이”를 ATX2와 동일하게 구현하는 시니어 엔지니어다.

필수 요구:
1) SYSTEM_ALERT_WINDOW를 사용하지 말고, AccessibilityService + `TYPE_ACCESSIBILITY_OVERLAY`로 툴바를 띄워라.
2) 매니페스트에 AccessibilityService를 등록하고, `res/xml`에 service config를 추가하며 `canPerformGestures=true`로 설정하라.
3) 접근성 서비스가 OFF이면 안내 Activity를 띄워 Disclosure 문구를 보여주고 `ACTION_ACCESSIBILITY_SETTINGS`로 이동시키는 UX를 구현하라.
4) 툴바는 세로 1열 LinearLayout + 드래그 핸들 + ImageButton들로 구성하고, 버튼 클릭 시 툴팁을 먼저 표시한 뒤 동작을 실행하라.
5) 툴바 LayoutParams는:
   - type=TYPE_ACCESSIBILITY_OVERLAY
   - flags=NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS
   - gravity=LEFT|CENTER_VERTICAL, x=0,y=0
6) 드래그는 핸들에서만 가능하게 하고, 탭이면 최소화(COLLAPSED) 토글이 되게 하라.
   - COLLAPSED에서는 “핸들 + 재생 버튼”만 표시하라.
7) 크기 계산은 반드시 “네비게이션바 제외 usable 크기” 기준으로 하고, 회전과 무관하게 min(usableW,usableH) 기준으로 세로 길이를 0.98배로 맞춰 버튼 높이를 산정하라.
8) 잠금 정책을 구현하라:
   - 광고 중: 전체 버튼+핸들 잠금
   - 녹화 중: 녹화 버튼만 활성
   - 실행 중: 재생(정지) 버튼만 활성
9) 외부 호출 API `showToolbar()`를 제공하고, 호출 시 FULL 상태로 표시되게 하라.

산출물:
- 변경/추가된 파일은 파일 단위로 전체 코드를 출력하라.
- 커맨드 안내가 필요하면 한 줄 체이닝은 `&&` 대신 `;`를 사용하라.
### [프롬프트 끝]

