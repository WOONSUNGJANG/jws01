### Windows 포팅용 프롬프트: “마커종류 1번(순번실행 / Ordered Loop)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`startOrderedLoop()` + `fireMarkerOnce()` 동작을 기준으로, Windows 앱에서 **동일한 순번실행 엔진**을 구현하기 위한
**복사-붙여넣기용 프롬프트**이다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “순번실행(Ordered Loop)” 동작을 Windows에서 동일하게 재현해야 한다.

구현 목표(순번실행만):
- 마커 목록(markers)을 스냅샷으로 받아 “순번 실행 대상”만 필터링하고,
- index 오름차순으로 정렬해,
- 각 마커를 1개씩 실행한 뒤,
- (딜레이 + 랜덤지연)만큼 대기하고 다음 마커로 넘어간다.
- stop 조건(time/cycles/infinite)을 지원한다.
- pause가 걸리면 “대기/타이머 감소/다음 실행”이 모두 멈춰야 한다(ordered loop는 pausable).

입력 데이터:
- markers: List<Marker>
- global settings:
  - execProbabilityPercent (0~100): AI탐지방어(랜덤실행) 확률
  - randomDelayPctGlobal (0~100): 전역 랜덤지연% (단, AI탐지방어 체크된 마커에만 적용)
  - clickPressMsGlobal (10~500): 전역 누름 시간(ms)
  - stop condition: mode(infinite/time/cycles), timeSec, cycles
  - pause state: pauseCount, pauseBeganAtMs, pausedTotalMs (혹은 이에 준하는 구현)

Marker 모델(순번실행에서 필요한 필드만):
- index: Int
- kind: String           // "click" | "swipe" | "module" 등
- xPx, yPx: Int          // screen 기준 중심 좌표(px)
- delayMs: Int           // 마커 딜레이
- randomClickUse: Bool   // AI탐지방어 체크 여부(랜덤실행/랜덤지연 적용 게이트)
- swipeMode: Int         // swipe: 0=순번, 1=독립
- toIndex: Int           // swipe 체인 시작점 (swipe_to 노드 연결)
- moveUpMs: Int          // swipe_to 노드의 hold(ms)
- moduleSoloExec: Bool   // module: true면 독립(병렬) 취급
- moduleLenPx/moduleMoveUpMs/modulePattern/moduleDirMode: module 실행 파라미터
- useColor: Bool         // click 조건부 실행(색상 조건)
- colorIndex: Int        // 참조할 color 링의 index

링(kind)은 순번실행 대상이 아님:
- "color", "swipe_to" 는 실행하지 않는다.

1) 순번 실행 대상 필터 규칙(= Android startOrderedLoop와 동일):
- (kind=="click" AND index>0)
- OR (kind=="swipe" AND index>0 AND swipeMode==0)
- OR (kind=="module" AND index>0 AND moduleSoloExec==false)

2) 정렬 규칙:
- 위 대상만 모아서 index 오름차순으로 정렬

3) “AI탐지방어(랜덤실행)” 규칙(= Android fireMarkerOnce와 동일):
- randomClickUse==true 이고, 아래 aiEligible 이면 확률 실행/스킵을 적용한다.
- aiEligible 제외(kind):
  - "color_module"
  - "solo_main", "solo_item"
  - "swipe_to"
  - "color"
- 구현:
  - p = clamp(execProbabilityPercent, 0..100)
  - if p < 100:
      roll = randomInt(0..99)
      if roll >= p: return SKIP(실행 안 함)

4) “랜덤 지연(전역 랜덤지연%)” 규칙(= Android delayWithJitterMs + jitterPctForMarkerDelay와 동일):
- totalDelayMs = baseDelayMs + random(0 .. baseDelayMs * jitterPct/100)
- jitterPct는 아래 조건에서만 randomDelayPctGlobal로 설정, 아니면 0:
  - randomClickUse==true 이고,
  - aiDefenseEligibleForRandomDelay(kind) == true
- aiDefenseEligibleForRandomDelay 제외(kind):
  - "swipe_to"
  - "solo_main", "solo", "solo_item"
  - "color_module", "image_module"
- Ordered Loop 대상(click/swipe/module)은 위 제외에 해당하지 않으므로,
  “AI탐지방어 체크된 경우(randomClickUse=true)”에만 랜덤지연이 실제 적용된다.

5) stop 조건(= Android startOrderedLoop와 동일):
- mode == "time": 활성 경과시간(activeElapsedMs)이 timeSec*1000 이상이면 루프 종료
  - activeElapsedMs는 pause 시간을 제외해야 함
- mode == "cycle" 또는 "cycles": cycle 카운트가 cycles 이상이면 종료
- mode == "infinite": 종료 조건 없음(사용자 stop 명령 전까지)

6) pause 동작(ordered loop는 “대기/타이머 감소”가 모두 멈춰야 함):
- sleepPausable(ms):
  - 남은 시간 remainMs를 두고,
  - pauseCount>0이면 wait/sleep 반복으로 시간 감소를 멈춘다.
  - pause가 풀리면 다시 remainMs를 계속 깎는다.

7) 실행 흐름(pseudocode):

function runOrderedLoop():
  cond = loadStopCond()
  startedAt = nowElapsedMs()
  cycle = 0
  while macroRunning:
    if cond.mode=="time":
      activeElapsedMs = (nowElapsedMs() - startedAt - pausedSoFarMs())
      if activeElapsedMs >= cond.timeSec*1000: break
    if cond.mode in {"cycle","cycles"} and cycle >= cond.cycles: break

    snap = snapshot(markers)   // 깊은 복사 권장(동시 수정 안전)
    ordered = filterOrderedTargets(snap)
    sort(ordered by index asc)
    if ordered is empty:
      sleepPausable(200)
      continue

    for m in ordered:
      if not macroRunning: break
      fireMarkerOnceOrdered(m, snap)   // 아래 8) 참조
      jitterPct = jitterPctForMarkerDelay(m)  // 0 또는 randomDelayPctGlobal
      sleepPausable( delayWithJitterMs(m.delayMs, jitterPct) )

    cycle += 1

  if macroRunning:
    requestStop("ordered_stop_condition")

8) 마커 1개 실행(ordered용 fireMarkerOnce, 핵심만):
- 공통:
  - 링("color","swipe_to")이면 실행하지 않고 성공 처리(return true)
  - AI탐지방어 확률 스킵 적용 (3) 규칙
  - kind에 따른 실제 동작 수행

- kind=="click":
  - (색상조건) if m.useColor && m.colorIndex!=0:
      ref = find marker where (index==m.colorIndex && kind=="color")
      if ref 없으면 return SKIP
      if not isColorMatch(ref): return SKIP
  - 좌표: (m.xPx, m.yPx) 를 screen 기준 클릭
  - 누름시간: clickPressMsGlobal 사용(마커별 pressMs는 현재 Android 구현에서 숨김)
  - 실행: sendClick(x,y,pressMs=clickPressMsGlobal)

- kind=="swipe" (순번 대상은 swipeMode==0):
  - if m.toIndex != 0:
      points = [(m.xPx,m.yPx)]
      cur = m.toIndex
      holdMs = 0
      guard <= 24
      while cur!=0 and guard++<24:
        node = find marker where index==cur
        if node.kind!="swipe_to": break
        points.add( (node.xPx,node.yPx) )  // node도 screen 중심 좌표로 취급
        holdMs = max(0, node.moveUpMs)
        cur = node.toIndex
      dur = clamp(clickPressMsGlobal, 120..3000)
      if m.soloExec==true: globalPauseAroundGesture()
      sendSwipePath(points, durationMs=dur, holdMs=holdMs)
    else:
      // 체인 없음: 기본 오른쪽 220dp 상당 거리로 스와이프(Windows에서는 px로 고정 값 사용 가능)
      dur = clamp(clickPressMsGlobal, 120..3000)
      sendSwipe(from=(m.xPx,m.yPx), to=(m.xPx+defaultDx, m.yPx), durationMs=dur)

- kind=="module" (순번 대상은 !moduleSoloExec):
  - distPx = (m.moduleLenPx>0 ? m.moduleLenPx : defaultDistPx)
  - dur = clamp(clickPressMsGlobal, 80..5000)
  - hold = clamp(m.moduleMoveUpMs, 0..600000)
  - pattern/dirMode에 따라 TAP 또는 스와이프 1회 이상 수행(Android 로직과 동일한 시퀀스 권장)
  - module 실행 중에는 다른 마커를 pause시키는 “moduleRunning” 개념이 있음
    (Windows 포팅에서도 최소한: module 수행 중 ordered loop 외 다른 루프를 정지시키는 게이트를 둔다)

9) 구현 주의사항(Windows):
- Android는 Thread + Handler를 사용하지만 Windows(Dart)에서는:
  - 단일 async loop + cancellation token 으로 동일 동작을 만들 것
  - “pause가 시간 감소를 멈추는 sleepPausable”을 꼭 구현할 것(단순 Future.delayed 금지)
- Random:
  - execProbabilityPercent 스킵 roll은 0..99 정수
  - delay jitter는 0..extraMax 범위의 정수(ms)로 구현(포팅 오차 방지)

10) 검증 시나리오:
- click 1, click 2, swipe 3, module 4를 만들고 index 오름차순 실행되는지 확인
- execProbabilityPercent=0에서 randomClickUse=true인 마커는 전부 스킵되는지 확인
- randomDelayPctGlobal=50에서 delayMs=1000인 마커는 추가 지연 0..500ms가 붙는지 확인
- pause ON 동안 activeElapsed(time stop)이 증가하지 않는지 확인
```

---

## 1) Android 원본 코드 근거(요약)

- **대상 필터/정렬/실행-대기 루프**: `startOrderedLoop()`
- **랜덤 실행(확률 스킵) + 실제 동작 분기**: `fireMarkerOnce()`
- **랜덤 지연 계산**: `delayWithJitterMs()` + `jitterPctForMarkerDelay()` + `aiDefenseEligibleForRandomDelay()`
- **중지조건(time/cycles)에서 pause 시간 제외**: `startOrderedLoop()`의 `pausedSoFar` 계산

---

## 2) “순번실행(Ordered Loop)” 핵심 포인트만 한 줄 요약

- **필터**: `click(index>0)` + `swipe(index>0 & swipeMode=0)` + `module(index>0 & !moduleSoloExec)`
- **정렬**: index 오름차순
- **실행**: `fireMarkerOnce(m)` 호출
- **대기**: `sleepPausable( delayWithJitterMs(m.delayMs, jitterPctForMarkerDelay(m)) )`
- **중지**: time/cycles 조건을 pause 시간을 제외하고 계산

