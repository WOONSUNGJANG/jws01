### Windows 포팅용 프롬프트: “마커종류 2번(독립실행 / Independent Loops)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`startIndependentLoops()` + `fireMarkerOnce()` 동작을 기준으로, Windows 앱에서 **동일한 독립실행 엔진**을 구현하기 위한
**복사-붙여넣기용 프롬프트**이다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “독립실행(Independent Loops)” 동작을 Windows에서 동일하게 재현해야 한다.

핵심 개념:
- 독립실행은 “마커 1개당 워커 1개”를 돌린다.
- 별도의 “슈퍼바이저(supervisor)”가 주기적으로 최신 마커 목록을 확인해서,
  워커를 생성/종료(upsert)한다. (동적 반영: 실행 중 마커 추가/삭제/종류변경 반영)
- 각 워커는:
  - pause 동안은 실행/타이머 감소가 멈춘다(남은 시간 보존).
  - 자신이 담당하는 마커를 최신 상태로 다시 읽어(copy) 실행한다.
  - 실행 후에는 (딜레이 + 랜덤지연)만큼 pausable sleep 한다.

입력 데이터:
- markers: List<Marker>  // 동시 수정 가능하므로 스냅샷/락 정책 필요
- global settings:
  - execProbabilityPercent (0~100): AI탐지방어(랜덤실행) 확률
  - randomDelayPctGlobal (0~100): 전역 랜덤지연%
  - clickPressMsGlobal (10~500): 전역 누름시간(ms)
  - pause state: pauseCount, pauseLock(or async gate), paused time accounting

Marker 모델(독립실행에서 필요한 필드만):
- index: Int
- kind: String
- delayMs: Int
- randomClickUse: Bool
- swipeMode: Int
- moduleSoloExec: Bool
// 실행 자체는 fireMarkerOnce() 규칙을 재사용(클릭/스와이프/모듈/색상모듈/이미지모듈 포함)

링(kind)은 실행 대상이 아님:
- "color", "swipe_to" 는 워커 대상에서도 제외(어차피 fireMarkerOnce에서 즉시 return true지만, 워커를 만들 필요 없음)

1) 독립실행 대상(kind) 판정 규칙(= Android isIndependentKind와 동일)

function isIndependentKind(m):
  isIndependent        = (m.kind == "independent")
  isIndependentSwipe   = (m.kind == "swipe" && m.swipeMode == 1)         // 독립 스와이프(메인)
  isIndependentModule  = (m.kind == "module" && (m.index < 0 || m.moduleSoloExec == true))
  isColorModule        = (m.kind == "color_module")
  isImageModule        = (m.kind == "image_module")
  return isIndependent || isIndependentSwipe || isIndependentModule || isColorModule || isImageModule

주의:
- Android 구현은 “독립실행 = kind independent 만”이 아니라,
  swipe(module 포함) 및 color_module, image_module도 독립 루프에서 주기 실행된다.

2) 전체 구조: supervisor + workers(upsert)

상태:
- independentWorkers: Map<Int, Worker>  // key = marker index
- independentSupervisorRunning: bool

Supervisor 루프(= Android independentSupervisorThread):
- macroRunning 동안 반복
- snap = snapshot(markers)
- want = set of indices where isIndependentKind(marker)==true
- (start missing) for idx in want:
    if independentWorkers[idx] not exists: startWorker(idx)
- (stop removed) for idx in runningWorkers:
    if idx not in want: stopWorker(idx)
- sleepPausable(500ms)

3) 워커 동작(= Android startWorker)

startWorker(idx):
  if already running: return
  spawn a background task/thread:
    try:
      while macroRunning:
        // pause 중에는 실행/타이머 감소 모두 멈춤
        waitUntilPauseReleased()   // pauseCount==0 될 때까지 대기(250ms tick 같은 방식)
        if not macroRunning: break

        // 항상 최신 마커 상태를 다시 읽는다(동적 변경 반영)
        cur = getMarkerByIndex(idx).copy()
        if cur == null: break

        // 대상 kind가 아니게 바뀌면 워커 종료
        if not isIndependentKind(cur): break

        // 실행: Android fireMarkerOnce(cur)와 동일한 규칙을 재사용
        fireMarkerOnce(cur, snapshotMaybeOptional)

        // 워커 대기: (딜레이 + 랜덤지연) pausable sleep
        jitterPct = jitterPctForMarkerDelay(cur)   // 0 또는 randomDelayPctGlobal (규칙은 readx1 참고)
        sleepPausable( delayWithJitterMs(cur.delayMs, jitterPct) )
    finally:
      remove from independentWorkers map

4) pause 규칙(중요)

독립 워커는 Android에서 아래처럼 구현되어 있다:
- “pause 중에는 실행/타이머 감소 모두 멈춤(남은 시간 보존)”
- 구현 방식:
  - 워커 루프의 실행 직전에 pauseCount>0이면 wait(250ms) 반복
  - 그리고 워커가 사용하는 sleep도 sleepPausable()을 사용하여 pause 동안 remainMs 감소가 멈춘다

Windows(Dart/async)에서도 동일하게:
- 단순 Future.delayed 쓰면 pause 중에도 시간이 흘러버리므로 금지
- 반드시 “남은 시간(remain)” 기반 sleepPausable을 구현해서 pause 동안 remain을 깎지 않게 할 것

5) 딜레이/랜덤지연 규칙(= Android와 동일)

delayWithJitterMs(delayMs, jitterPct):
  base = max(delayMs, 0)
  pct  = clamp(jitterPct, 0..100)
  extraMax = floor(base * pct / 100)
  extra = randomInt(0..extraMax)
  return base + extra

jitterPctForMarkerDelay(m):
  enabled = aiDefenseEligibleForRandomDelay(m) && (m.randomClickUse==true)
  return enabled ? randomDelayPctGlobal : 0

aiDefenseEligibleForRandomDelay 제외(kind):
  - "swipe_to"
  - "solo_main", "solo", "solo_item"
  - "color_module", "image_module"

즉 독립실행 대상 중:
- independent / swipe(독립) / module(독립) 은 randomClickUse 체크 시 랜덤지연 적용 가능
- color_module / image_module 은 랜덤지연 제외(항상 jitter=0)

6) 랜덤 실행(AI탐지방어=랜덤실행) 규칙

fireMarkerOnce 내부에서 “확률 스킵”을 수행한다.
독립실행 워커도 동일 fireMarkerOnce를 호출하므로, 랜덤 실행 규칙은 그대로 적용된다.

aiEligible 제외(kind):
  - "color_module"
  - "solo_main", "solo_item"
  - "swipe_to"
  - "color"

따라서 독립실행 대상 중:
- independent / swipe(독립) / module(독립) / image_module 은 randomClickUse==true이면 확률 스킵 적용
- color_module 은 확률 스킵 제외(조건 만족 시만 클릭하는 특성)

7) Windows 구현 팁(안전한 포팅)

- Android는 Thread + synchronized(lock)로 동시성 제어.
- Windows(Dart)에서는:
  - supervisor: Timer 또는 async loop + cancellation token
  - worker: Isolate로 나누기보다는 “단일 isolate + 여러 async task”로 시작(간단)
  - markers의 변경이 잦으면:
    - snapshot(markers)로 want 계산(슈퍼바이저)
    - worker는 매 tick마다 getMarkerByIndex(idx)로 최신 상태를 읽고 실행
- stopWorker:
  - Android는 interrupt()를 사용하지만,
  - Dart에서는 cancellation token(AbortController 패턴)으로 sleep/대기를 깰 수 있게 구성

8) 검증 시나리오(독립실행만)

- 마커 3개:
  - independent idx=10 delay=1000
  - swipe idx=11 swipeMode=1 delay=700
  - module idx=-1 moduleSoloExec=true delay=1200
  를 만들고, 3개의 워커가 동시에(각자 주기대로) 실행되는지 로그로 확인

- 실행 중에:
  - independent 하나를 삭제하면 해당 워커가 0.5초 이내에 종료되는지
  - swipeMode를 1->0으로 바꾸면 해당 워커가 종료되는지
  - delayMs 값을 바꾸면 다음 루프부터 변경된 delay가 적용되는지

- pause ON:
  - pause 상태에서 “워커 실행 횟수”가 증가하지 않아야 함
  - pause 해제 후 다시 주기대로 이어져야 함(남은 시간 보존)
```

---

## 1) Android 원본 코드 근거(요약)

- **워커 대상(kind) 판정**: `startIndependentLoops()` 내부 `isIndependentKind()`
- **워커 1개 실행 루프**: `startWorker(idx)` 내부 while(macroRunning)
- **워커 upsert(생성/종료)**: `independentSupervisorThread` 내부 while(macroRunning)
- **대기 로직**: `sleepPausable(delayWithJitterMs(cur.delayMs, jitterPctForMarkerDelay(cur)))`
- **실행 로직**: `fireMarkerOnce(cur)`

