### Windows 포팅용 프롬프트: “마커종류 4번(단독실행 / Solo Loop)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`startSoloLoop()` + `fireMarkerOnce()`(soloVerify 구간 포함) 동작을 기준으로, Windows 앱에서 **동일한 단독실행 엔진**을 구현하기 위한
**복사-붙여넣기용 프롬프트**이다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “단독실행(Solo Loop, 마커종류 4번)”을 Windows에서 동일하게 재현해야 한다.

단독실행의 핵심 목적:
- solo_main(단독 메인)이 “타이머(preDelay)”가 끝나면 트리거되고,
- 트리거 순간에는 다른 모든 마커 실행(순번/독립)을 잠시 멈춘 뒤(global pause),
- solo_main을 1회 실행하고,
- 연결된 solo_item(단독 sub)들을 A1,A2,... 라벨 순서대로 최대 comboCount개 실행한다.
- soloVerify(클릭 실행확인)가 켜져 있으면, 이미지 존재 여부에 따라:
  - 있으면 1회 클릭 후 다음 sub로 진행
  - 없으면 goto(재개)로 특정 sub로 점프하거나, 단독 구간을 즉시 종료한다.

입력 데이터:
- markers: List<Marker>
- global settings:
  - clickPressMsGlobal (10~500): 전역 누름시간(ms) (Android는 pressMsForMarkerClick이 전역만 사용)
  - execProbabilityPercent / randomDelayPctGlobal: AI탐지방어(랜덤실행/랜덤지연) (fireMarkerOnce 공통 규칙)
  - pause state:
    - pauseCount (0이면 정상, >0이면 pause)
    - sleepPausable(ms): pause 동안 시간 감소가 멈추는 sleep
  - capture availability (Windows에서는 screen capture 엔진의 준비 상태):
    - captureReady: bool
    - captureRevoked 판정(권한/세션이 완전히 끊긴 상태)

Marker 모델(단독실행에 필요한 필드)
- index: Int
- kind: String            // "solo_main" | "solo_item" | (legacy) "solo"
- xPx, yPx: Int           // screen 기준 중심 좌표(px)
- delayMs: Int            // 단독 메인 실행 후, “다음 단독 트리거까지의 추가 대기(mainWait)”로 사용됨
- soloLabel: String       // 단독 메인의 라벨(A~Z). 비어있으면 solo_main으로 인식하지 않음
- soloStartDelayMs: Int   // preDelay(실행 전 지연). 타이머 기반
- soloComboCount: Int     // 콤보(sub 실행 개수, 1..10)

// solo_item(단독 sub)
- parentIndex: Int        // 어떤 solo_main에 매달렸는지
- soloLabel: String       // 예: "A1", "A2", "B1" ...
- delayMs: Int            // sub 간 딜레이(단독 콤보 내부 sleep)

// soloVerify(클릭 실행확인)
- soloVerifyUse: Bool
- soloVerifyTemplateFile: String
- soloVerifyStartXPx/soloVerifyStartYPx/soloVerifyEndXPx/soloVerifyEndYPx: Int  // usable 기준 영역(Windows에서는 screen 기준으로 통일 권장)
- soloVerifyAccuracyPct: Int (50..100)
- soloVerifyW/soloVerifyH: template size
- soloVerifyCropLeftXPx/soloVerifyCropTopYPx: crop origin(디버그/템플릿 저장용)
- soloVerifyGotoOnStopMissing: Int   // “이미지 없음”일 때 점프할 solo_item index (0이면 단독 종료)

// solo preClick (실행 전 1회 클릭)
- soloPreClickUse: Bool
- soloPreClickXPx/soloPreClickYPx: Int  // usable 기준(하지만 음수/범위 초과 가능). Windows는 저장 좌표계 정책을 명확히 하라.

단독 실행의 제어 플래그(런타임 상태):
- soloAbortRequested: bool
- soloGotoRequestedIndex: int   // 0이면 없음, 그 외는 “다음에 실행할 solo_item index로 점프”

========================================================
1) Solo Loop 전체 구조 (Android startSoloLoop와 동일)
========================================================

Solo Loop는 별도의 스레드/태스크로 계속 돈다.

상태 맵:
- remainToNext: Map<Int, long>  // soloMainIndex -> 남은 preDelay(ms) (pause 중에는 감소하지 않음)
- executedOnce: Set<Int>        // 해당 solo_main이 한 번이라도 실행된 적 있는지
- preDelaySeen: Map<Int, int>   // 첫 실행 전(preDelay 카운트다운 중) 설정 변경 보정용

solo_main 수집 규칙:
- markers 중 kind in {"solo_main","solo"} 이고 soloLabel이 비어있지 않은 것만 메인으로 인정
- soloLabel(문자열) 오름차순으로 정렬 후 사용

루프:
while macroRunning:
  mains = getSoloMainsSortedByLabel()
  if mains empty:
    sleepPausable(300)
    continue

  // 신규 메인 초기화 + 첫 실행 전 preDelay 변경 보정
  for each main in mains:
    if remainToNext[main.index] not exists:
      pre = max(main.soloStartDelayMs, 0)
      remainToNext[main.index] = pre
      preDelaySeen[main.index] = pre
    else if main.index not in executedOnce:
      // 첫 실행 전에는 soloStartDelayMs가 바뀌면 남은 시간(remain)을 보정한다.
      pre = max(main.soloStartDelayMs, 0)
      oldPre = preDelaySeen[main.index]
      if oldPre != pre:
        remainToNext[main.index] = max(0, remainToNext[main.index] + (pre - oldPre))
        preDelaySeen[main.index] = pre

  // 제거된 메인 정리
  alive = set(mains.index)
  for idx in keys(remainToNext):
    if idx not in alive:
      remove remainToNext[idx], preDelaySeen[idx], executedOnce[idx]

  // due 선택: 라벨 정렬된 mains 중 remain<=0 인 첫 번째
  due = first main in mains where remainToNext[main.index] <= 0
  if due == null:
    // due 없음: pause가 아닐 때만 remain을 step 만큼 감소
    step = 50ms
    if pauseCount == 0:
      for each idx in remainToNext:
        remainToNext[idx] = max(0, remainToNext[idx] - step)
    sleepPausable(step)
    continue

  // due가 있으면 “단독 실행 구간” 수행
  runSoloSegment(due)

  // 실행 후 다음 트리거까지 remain 재설정:
  // Android는 (mainWait + preDelay)로 다시 세팅한다.
  executedOnce.add(due.index)
  mainWait = delayWithJitterMs(due.delayMs, jitterPctForMarkerDelay(due))
  pre = max(due.soloStartDelayMs, 0)
  remainToNext[due.index] = mainWait + pre

========================================================
2) 단독 실행 구간(runSoloSegment): global pause + main + items + goto
========================================================

요구사항:
- 단독이 트리거되면 “다른 마커(순번/독립)”을 잠시 멈춘다.
- 그리고 단독이 끝나면 다른 마커들은 “남은 시간 그대로” 이어서 실행된다.

구현:
- withGlobalPause { ... } 형태의 게이트를 둔다.
  - 구현 예: pauseCount++ 후, ordered/independent의 sleepPausable이 멈추게 함
  - 블록 종료 시 pauseCount--.

withGlobalPause {
  soloAbortRequested = false
  soloGotoRequestedIndex = 0

  // 2-1) solo_main 자체를 1회 실행
  fireMarkerOnce(due)
  if soloAbortRequested: return

  // 2-2) solo_item 수집 (부모=due.index)
  items = markers.filter(kind=="solo_item" && parentIndex==due.index && soloLabel not blank)
  sort items by soloLabel 규칙(아래 3) 참고

  combo = clamp(due.soloComboCount, 1..10)
  done = 0
  p = 0

  // 2-3) solo_main 실행에서 goto가 발생했으면, sub 시작 위치 점프
  if soloGotoRequestedIndex != 0:
    jumpPos = indexOf items where item.index == soloGotoRequestedIndex
    soloGotoRequestedIndex = 0
    if jumpPos >= 0: p = jumpPos
    else: soloAbortRequested = true

  // 2-4) sub 실행 루프
  while p < items.size:
    if not macroRunning: break
    if soloAbortRequested: break
    if done >= combo: break

    it = items[p]

    // (중요) 단독 콤보 내부 딜레이는 Android에서 “pausable 아님”
    // -> Windows에서도 동일하게 하려면, 이 sleep은 pause 영향을 받지 않게 구현한다.
    //    (단, global pause 안에서 실행되므로 외부 루프는 이미 멈춰 있음)
    sleepRaw( delayWithJitterMs(it.delayMs, jitterPctForMarkerDelay(it)) )

    fireMarkerOnce(it)
    if soloAbortRequested: break

    done += 1

    // 2-5) sub 실행 중 goto가 발생하면 “앞으로” 점프만 허용
    if soloGotoRequestedIndex != 0:
      jumpPos = indexOf items where item.index == soloGotoRequestedIndex
      soloGotoRequestedIndex = 0
      if jumpPos > p:
        p = jumpPos
        continue

    p += 1
    sleepRaw(20ms)
}

========================================================
3) solo_item 정렬 규칙(라벨 A1..A10)
========================================================

Android의 parseSoloItemOrder(label):
- 문자열 끝에서 연속된 숫자를 떼어낸다.
  - head: 문자 부분(대문자)
  - num: 숫자 부분(int?) (없으면 null)

정렬:
- head 오름차순
- num 둘 다 있으면 num 오름차순
- num이 있는 쪽이 앞
- 둘 다 없으면 원 문자열 비교

========================================================
4) fireMarkerOnce에서 soloVerify(클릭 실행확인) 동작(중요)
========================================================

soloVerify는 solo_main/solo_item에서만 의미가 있다.
Windows는 Android의 “화면공유 권한” 대신, 자체 캡처 엔진의 준비 상태(captureReady)를 사용한다.

soloVerifyEligible 조건:
- isSolo = kind in {"solo_main","solo_item","solo"}
- soloVerifyUse == true
- soloVerifyTemplateFile not blank

4-1) 캡처 준비 여부 게이트
- soloVerifyEligible인데 captureReady가 순간적으로 false일 수 있어,
  Android는 최대 약 480ms(80ms*6) 짧게 기다린다.
- 그래도 준비가 안 되면:
  - “캡처 권한이 완전히 해제(revoked)”된 상황이면:
    - goto/abort를 무효화하고, 그냥 평범한 클릭 1회로 진행(순서대로 실행)
  - 그렇지 않으면:
    - gotoTarget = soloVerifyGotoOnStopMissing
    - gotoTarget이 유효하면 soloGotoRequestedIndex=gotoTarget, 아니면 soloAbortRequested=true
    - return true (단독 구간을 유지하되, 다음 sub 점프/종료로 처리)

4-2) gotoEnabled / preClickTap
- Android는 “gotoEnabled(=gotoTarget!=0)”일 때만 preClick을 수행한다.
- 조건:
  - gotoEnabled = (soloVerifyGotoOnStopMissing != 0)
  - soloPreClickUse == true
  - preClick 좌표가 (-1,-1)이 아님
- 수행:
  - preClick 좌표(usable 기준)를 screen 좌표로 변환 후 click 1회
  - 1000ms 대기 후 soloVerify 매칭 진행

4-3) soloVerify 매칭 규칙(현재 버전은 “방식 1가지로 통일”)
- 의미:
  - 이미지가 “있으면” 1회 클릭하고 다음 sub로 진행
  - 이미지가 “없으면” goto(재개)로 점프(없으면 단독 종료)

매칭:
- region: soloVerifyStart/End 영역(usable 기준) -> screen rect로 변환
- minScore: accuracyPct(50..100)에서 0.55..0.85로 매핑
- foundNow():
  - matchOnce()를 5회 수행(40ms 간격)
  - score>=minScore 인 횟수가 3회 이상이면 found=true(다수결)
  - best score는 로깅용

4-4) found=false(이미지 없음) 처리
- gotoTarget = soloVerifyGotoOnStopMissing
- gotoTarget이 유효하면 soloGotoRequestedIndex=gotoTarget, soloAbortRequested=false
- 아니면 soloAbortRequested=true
- return true (클릭 없이 “재개/종료”)

4-5) found=true(이미지 있음) 처리: “마커 원 내부에서 1회 클릭”
- 클릭 좌표는 (marker center x,y) 주변에서 랜덤하게 만든다.
- Android 알고리즘:
  - 반지름(radiusPx) = markerSizePx/2
  - phase1: 왼쪽으로 조금씩 이동(3..8px step 누적)하여 radius에 도달할 때까지
  - phase2: 원판 내부 균일 분포(각도+반지름 sqrt 샘플링)
  - 최종 dx,dy를 더한 좌표를 screen 범위로 clamp 후 click 1회

========================================================
5) Windows 구현 팁(안전한 포팅)
========================================================

- 단독실행은 “다른 루프의 남은 시간 보존”이 요구사항이므로,
  ordered/independent의 대기 로직은 반드시 sleepPausable(remain 기반)이어야 한다.
- withGlobalPause는 pauseCount를 이용해:
  - ordered/independent의 sleepPausable이 시간 감소를 멈추게 하고,
  - 단독 구간이 끝난 뒤 이어서 실행되게 한다.
- 단독 콤보 내부 sub 딜레이는 Android에서 Thread.sleep(= pausable 아님)이다.
  Windows도 동일하게 “raw sleep”로 구현하는 편이 재현도가 높다.

========================================================
6) 검증 시나리오
========================================================

- solo_main A:
  - soloStartDelayMs=3000
  - soloComboCount=3
  - delayMs=1000
- solo_item(A1,A2,A3,A4) 각각 delayMs=500
- 3초 뒤:
  - global pause가 걸리고
  - A(main) 실행 1회
  - A1,A2,A3 실행(3개만)
  - 단독 종료 후 ordered/independent가 “남은 시간 그대로” 이어져야 한다.

- soloVerify:
  - 이미지가 없을 때 gotoTarget이 설정되어 있으면 해당 sub로 점프하는지
  - 없으면 단독 구간이 즉시 종료되는지
  - 이미지가 있을 때는 1회 클릭 후 다음 sub로 넘어가는지
```

---

## 1) Android 원본 코드 근거(요약)

- **스케줄러/타이머 구조**: `startSoloLoop()`
  - `remainToNext`, `executedOnce`, `preDelaySeen`
  - due 선택(라벨 순) + step(50ms) 감소 + pauseCount==0일 때만 감소
  - due 발생 시 `withGlobalPause { fireMarkerOnce(main); sub들 실행; goto 처리 }`
  - 완료 후 `remainToNext[due] = delayWithJitterMs(due.delayMs, ...) + soloStartDelayMs`
- **soloVerify / goto / preClickTap / 원 내부 클릭**: `fireMarkerOnce()`의 soloVerify 블록

