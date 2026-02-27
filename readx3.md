### Windows 포팅용 프롬프트: “마커종류 3번(스와이프 / Swipe)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`fireMarkerOnce()` 내 **스와이프 실행 로직**을 기준으로, Windows 앱에서 동일하게 구현하기 위한 **복사-붙여넣기용 프롬프트**이다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “스와이프(kind=swipe)” 동작을 Windows에서 동일하게 재현해야 한다.

핵심 개념(데이터 모델):
- 스와이프는 “메인(swipe)” 마커 1개와, “서브(swipe_to)” 노드(링)들의 체인으로 구성된다.
- 체인 링크는 toIndex로 연결된다:
  - main.toIndex -> firstSub.index
  - sub.toIndex  -> nextSub.index (또는 0이면 끝)
- 각 swipe_to 노드는 좌표(xPx,yPx)와 hold(moveUpMs) 값을 가진다.
  - hold는 “스와이프가 끝난 뒤 손을 떼기 전 유지 시간(ms)”로 사용된다.
  - Android 구현은 “마지막 swipe_to의 moveUpMs”를 holdMs로 사용한다.
- swipe_to 자체는 루프에서 “실행 대상이 아님(링)”이다.
  - fireMarkerOnce에서 kind=="swipe_to"면 바로 return true (실행 안 함)

Marker 필드(스와이프에 필요한 것만):
- index: Int
- kind: String           // "swipe" | "swipe_to"
- xPx, yPx: Int          // screen 기준 중심 좌표(px)
- toIndex: Int           // 체인 링크
- parentIndex: Int       // UI/정리용(실행엔 직접 필수는 아님)
- moveUpMs: Int          // swipe_to의 hold(ms)
- swipeMode: Int         // swipe(메인) 실행루프 선택용: 0=순번, 1=독립
- delayMs: Int           // 메인 swipe의 실행 주기(순번 루프/독립 루프에서 사용)
- soloExec: Bool         // true면 실행 시 global pause로 “단독 실행”처럼 처리

전역 설정(스와이프에 필요):
- clickPressMsGlobal (10~500): Android에서 pressMsForMarkerClick(m)이 전역값을 사용
- screenW/screenH: 기본 스와이프 목적지 clamp에 사용(체인 없는 기본 스와이프)
- (선택) defaultSwipeDxPx: 체인 없을 때 오른쪽으로 밀 기본 거리 (Android는 220dp)

1) 스와이프가 “어느 루프에서 실행되는가”

스와이프 메인(kind=="swipe")은 설정 값에 따라 두 군데 중 하나에서 실행된다:
- Ordered(순번실행): kind=="swipe" && index>0 && swipeMode==0
- Independent(독립실행): kind=="swipe" && swipeMode==1

즉, Windows 포팅에서도:
- swipeMode==0이면 readx1(순번실행) 대상 규칙에 포함
- swipeMode==1이면 readx2(독립실행) 대상 규칙에 포함

2) 실행 진입 게이트(공통 fireMarkerOnce 규칙)

fireMarkerOnce는 스와이프 실행 전에 공통 로직을 수행한다:
- 링은 실행하지 않음:
  - if kind in {"color","swipe_to"}: return true
- AI탐지방어(랜덤 실행) 확률 스킵(해당되는 kind일 때만)
- 그 다음에 “스와이프 체인” 또는 “기본 스와이프” 로직으로 분기한다.

3) 스와이프 체인 실행 로직 (Android의 핵심)

조건:
- if m.toIndex != 0:
    체인 스와이프를 수행한다. (Android에서는 kind가 swipe가 아니어도 toIndex가 있으면 이 분기로 들어감)
    Windows 포팅에서는 안전을 위해 “메인 kind==swipe 에서만 toIndex 체인을 의미 있게 사용”하도록 설계하라.

체인 구성:
- points = []
- points.add( (main.xPx, main.yPx) )
- cur = main.toIndex
- lastHoldMs = 0
- guard = 0
- while cur != 0 and guard++ < 24:
    node = find marker where index==cur
    if node missing: break
    if node.kind != "swipe_to": break
    points.add( (node.xPx, node.yPx) )
    lastHoldMs = max(0, node.moveUpMs)
    cur = node.toIndex

스와이프 시간(duration):
- dur = clamp(clickPressMsGlobal, 120..3000)
  (Android는 pressMsForMarkerClick(m)을 쓰고, swipe는 너무 짧으면 cancelled가 잦아 min=120ms로 올림)

실행:
- if main.soloExec == true:
    globalPauseAroundGesture( sendSwipePath(points, dur, holdMs=lastHoldMs) )
  else:
    sendSwipePath(points, dur, holdMs=lastHoldMs)

sendSwipePath(points, dur, holdMs):
- Windows에서는 “마우스 드래그 경로”로 구현한다.
- 최소 구현:
  - points를 순서대로 이동하며 Drag를 이어서 만들거나,
  - 간단히 첫 점->마지막 점으로 1회 드래그(체인 손실)로 시작할 수 있으나,
    Android와 동일 재현 목표면 points 전체를 path로 처리하라.
- holdMs:
  - 드래그 종료 후 버튼을 holdMs 동안 누른 상태로 유지한 다음 release.

4) 체인이 없을 때 기본 스와이프(오른쪽으로)

조건:
- if m.kind=="swipe" and m.toIndex==0:
    기본 스와이프를 수행한다.

Android 기본:
- from = (m.xPx, m.yPx)
- toX = clamp(fromX + dpToPx(220dp), 0..screenW-1)
- toY = fromY
- dur = clamp(clickPressMsGlobal, 120..3000)
- sendSwipe(from=(fromX,fromY), to=(toX,toY), durationMs=dur)

Windows 포팅:
- defaultSwipeDxPx를 앱 설정으로 둬라(기본 220dp 상당의 px)
  - 예: 220 * (DPI / 160) 또는 고정 220px(단순) 중 택1
- screen 경계 clamp는 유지(0..W-1)

5) swipe_to(서브/링) “왜 실행 대상이 아닌가”

- swipe_to는 스와이프 path를 구성하기 위한 노드이며,
  단독으로 실행되면 의미가 없다.
- Android fireMarkerOnce는 처음에:
  - if kind=="swipe_to": return true
  로 처리하여 루프에서 호출되어도 아무 동작을 하지 않는다.
- Windows도 동일하게:
  - swipe_to는 루프 대상 목록(ordered/independent/solo)에서 제외하는 것이 바람직하다.

6) 오류/안전장치(권장 포팅 사양)

- guard:
  - 체인 추적 최대 길이 24(무한 루프/순환 참조 방지)
- holdMs:
  - 음수면 0으로 clamp
- dur:
  - 120..3000ms clamp
- points:
  - 최소 2점(메인+서브1개)이면 의미 있는 스와이프
  - points가 1점만 나오면(서브를 못 찾음) 기본 스와이프 fallback 고려

7) 테스트 시나리오

- 케이스 A: 체인 스와이프
  - main(kind=swipe, idx=3, x,y) toIndex=-1
  - sub1(kind=swipe_to, idx=-1, x,y) toIndex=-2 moveUpMs=100
  - sub2(kind=swipe_to, idx=-2, x,y) toIndex=0  moveUpMs=700
  => 실행 시 points 3개가 순서대로 반영되고, hold=700ms가 적용되어야 한다.

- 케이스 B: 체인 없는 기본 스와이프
  - main(kind=swipe, toIndex=0)
  => 오른쪽으로 defaultSwipeDxPx만큼 드래그.

- 케이스 C: swipeMode
  - swipeMode=0이면 순번실행(readx1)에서만 실행됨
  - swipeMode=1이면 독립실행(readx2)에서 주기 실행됨
```

---

## 1) Android 원본 코드 근거(요약)

- **체인 스와이프(points + hold)**:
  - `fireMarkerOnce()`의 “스와이프 체인(toIndex)” 블록
  - `guard < 24`, `lastHoldMs = node.moveUpMs`, `dur = pressMsForMarkerClick(m).coerceIn(120,3000)`
  - `AutoClickAccessibilityService.swipePathPx(points, moveDurationMs=dur, holdMs=lastHoldMs, delayMs=0)`
- **체인 없는 기본 스와이프(오른쪽 220dp)**:
  - `fireMarkerOnce()`의 “스와이프(메인)인데 체인이 없으면 기본 스와이프”
- **링 비실행 규칙**:
  - `fireMarkerOnce()` 시작부: `if (m.kind=="color" || m.kind=="swipe_to") return true`

