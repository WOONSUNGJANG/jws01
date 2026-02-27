### Windows 포팅용 프롬프트: “마커종류 7번(이미지모듈 / image_module)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`fireMarkerOnce()` 내 **이미지모듈(image_module)** 실행 로직과 템플릿 매칭 함수(`loadTemplateImage`, `findBestTemplateInRegion`,
`verifyTemplateAtCenter`)를 기준으로 Windows 앱에서 동일하게 구현하기 위한 **복사-붙여넣기용 프롬프트**다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “이미지모듈(kind=image_module)” 동작을 Windows에서 동일하게 재현해야 한다.

이미지모듈의 핵심 목적:
- 화면의 특정 검색영역에서 “템플릿 이미지”를 찾아 점수(score)가 기준(minScore) 이상이면 성공으로 본다.
- 성공 시, 10회 추가 검증(다수결/과반)으로 흔들림을 줄인다.
- 최종 성공이면 클릭(또는 소리/진동 모드)을 수행한다.

========================
입력 데이터(필수)
========================

전역 상태:
- captureReady: bool
  - 캡처 준비가 안 되면 이미지모듈은 아무것도 하지 않고 SKIP한다.
- 최신 프레임 버퍼(스크린샷) + 픽셀 접근 API
  - findBestTemplateInRegion / verifyTemplateAtCenter가 이를 사용

전역 설정:
- clickPressMsGlobal (10..500): 전역 누름 시간(ms)
- imageVerifyThirdIntervalMs (0..1000): 10회 검증에서 3번째 이후 간격(ms)
- withGlobalPause(action): action 수행 동안 다른 마커 실행을 멈추는 게이트

Marker 모델(image_module에 필요한 필드)
- index: Int
- kind: "image_module"
- xPx, yPx: Int               // screen 기준 마커 중심(px)
- imageTemplateFile: String   // 템플릿 파일명(예: assets 또는 외부 파일)
- imageStartXPx, imageStartYPx, imageEndXPx, imageEndYPx: Int
  - Android에서는 usable 좌표(px)로 저장됨
  - 값이 -1이면 기본값 계산에 사용(아래 1) 참고)
- imageAccuracyPct: Int (50..100)
- imageW, imageH: Int         // 템플릿 크기(디버그/보정용)
- imageClickMode: Int
  - 0: 마커 위치 클릭 (marker_center)
  - 1: 찾은 이미지 중앙좌표 클릭 (template_center)
  - 2: 소리내기 (ringtone)  -> 클릭 대신 소리 재생
  - 3: 진동하기 (vibrate)   -> 클릭 대신 진동
- imageCropLeftXPx, imageCropTopYPx: Int  // 마지막 crop 위치(디버그/템플릿 저장용)

추가(상태 표시/디버깅 저장, 선택):
- imageFoundCenterXPx/Y: 찾은 이미지 중앙(usable) 저장
- imageLastScorePct / imageLastMinPct / imageLastOk: 최근 점수/기준/성공 여부 저장

유틸/함수(Windows에 구현해야 함)
- getScreenSize(): (screenW, screenH)
- getUsableRect(): (left, top, right, bottom)
  - Android는 system bars inset 제외 영역(usable)을 사용
  - Windows는 screen==usable 정책으로 단순화 가능하지만, .jws 호환을 위해 좌표계 정책을 확정할 것
- loadTemplateImage(fileName): TemplateImage?
  - 파일이 없으면 null -> SKIP
- findBestTemplateInRegion(tpl, leftS, topS, rightS, bottomS): Match?
  - 반환: (cx, cy, score)  // cx,cy는 screen 좌표의 템플릿 중심점
- verifyTemplateAtCenter(tpl, centerScreenX, centerScreenY): Float
  - 특정 중심점에서 템플릿이 “있다/없다”를 점수로 반환
- click(x,y, pressMs): bool
- playCurrentRingtoneOnce(durationMs): void   // Windows에서는 system sound 대체 가능
- vibrateOnce(durationMs): void              // Windows에서는 no-op 또는 haptic 대체

========================
1) 검색영역 계산(usable -> screen 변환 포함)
========================

Android 규칙:
- usable = getUsableRect()
- wU = usable.width, hU = usable.height
- sxU0:
  - if imageStartXPx >= 0: imageStartXPx
  - else: (m.xPx - usable.left)
  - clamp 0..wU-1
- syU0:
  - if imageStartYPx >= 0: imageStartYPx
  - else: (m.yPx - usable.top)
  - clamp 0..hU-1

- exU0 기본값:
  - if imageEndXPx >= 0: imageEndXPx
  - else: sxU0 + 500   // 기본 검색영역 500x500
  - clamp 0..wU-1
- eyU0 기본값:
  - if imageEndYPx >= 0: imageEndYPx
  - else: syU0 + 500
  - clamp 0..hU-1

- sxU = min(sxU0, exU0), exU = max(...)
- syU = min(...),        eyU = max(...)

screen rect:
- leftS   = clamp(usable.left + sxU, usable.left .. usable.right-1)
- topS    = clamp(usable.top  + syU, usable.top  .. usable.bottom-1)
- rightS  = clamp(usable.left + exU, usable.left+1 .. usable.right)
- bottomS = clamp(usable.top  + eyU, usable.top+1  .. usable.bottom)

Windows 포팅:
- “usable” 개념이 없으면 usable==screen 으로 두고 동일 계산을 적용하라(호환).

========================
2) 정확도(50..100) -> minScore(0.55..0.85) 매핑
========================

Android 규칙:
- acc = clamp(imageAccuracyPct, 50..100)
- minScore = clamp( 0.55 + ((acc-50)/50)*0.30, 0.55..0.85 )

주의:
- 내부 점수는 단순 MAD 기반(샘플링)이라 0.90 같은 높은 기준은 실패가 잦아,
  사용자 입력(50..100)을 위 범위로 매핑한다.

========================
3) 1차 탐색: findBestTemplateInRegion
========================

best = findBestTemplateInRegion(tpl, leftS, topS, rightS, bottomS)
- best가 null이면:
  - 디버그 저장: score=-1, ok=0 등
  - SKIP

okMatch = (best.score >= minScore)
- okMatch false면:
  - 디버그 저장(best center, best score, minScore)
  - SKIP

추가 저장(Android):
- hitCenterXU = (best.cx - usable.left) clamp
- hitCenterYU = (best.cy - usable.top) clamp
- updateImageFoundCenterInPrefs(index, hitCenterXU, hitCenterYU)
- updateImageMatchDebugInPrefs(index, hitCenterXU, hitCenterYU, best.score, minScore)

Windows 포팅:
- 동일한 “마지막 찾은 중심점/점수/기준”을 UI에 보여주려면 동일 키로 저장 추천.

========================
4) 10회 다중 검증(과반)
========================

목적:
- 단발성 false-negative/false-positive를 줄인다.
- “있다”를 과반(>=6/10)으로 판정.

Android 규칙:
- verifyCount = 10
- interval:
  - k==1: 40ms
  - k>=2: imageVerifyThirdIntervalMs(ms) (0이면 스킵)
- scores[k] = verifyTemplateAtCenter(tpl, best.cx, best.cy)
- okVotes = count(scores[k] >= minScore)
- if okVotes < 6: SKIP

Windows 포팅:
- capture 프레임이 빠르게 변하면 interval이 중요하다.
- 최소 구현에서는 40ms 고정, 이후 0~1000ms 설정 가능하게 해라.

========================
5) 성공 시 실행(클릭/소리/진동)
========================

1) 클릭 좌표 결정:
- markerCenter(mx,my):
  - Android는 “실제 오버레이 표시 중심”을 우선(markerScreenCenterPx),
    실패 시 저장값(markerScreenCenterPxFromStored)을 사용.
  - Windows는 보통 (m.xPx,m.yPx)를 바로 사용.

- clickMode:
  - 0/기본: marker_center => (mx,my)
  - 1: template_center => (best.cx,best.cy)
  - 2: ringtone => 클릭 대신 playCurrentRingtoneOnce(1400ms)
  - 3: vibrate  => 클릭 대신 vibrateOnce(520ms)

2) 실행은 withGlobalPause로 감싼다:
- ok = withGlobalPause {
    if mode==2: playCurrentRingtoneOnce(1400); return true
    if mode==3: vibrateOnce(520); return true
    else: return click(x,y, pressMs=clickPressMsGlobal)
  }

3) 실행 후:
- mode 0/1인 경우 터치 시각화(파란 점) 가능(Windows는 optional)
- 로그(선택):
  - v10 점수 배열, okVotes, minScore 등을 기록

========================
6) post-verify(클릭 후에도 이미지가 남는지 5회 확인) — 디버그/경고용
========================

Android 규칙:
- mode가 클릭인 경우(0/1)만 수행(소리/진동은 클릭이 없으므로 생략)
- postVerifyCount=5
- k==0: 120ms 대기
- k>0:  40ms 대기
- postScores[k] = verifyTemplateAtCenter(tpl, best.cx, best.cy)
- postMax = max(postScores)
- stillFound = (postMax >= minScore)
- stillFound이면 WARN 로그 남김(좌표/타이밍 문제 추적)

Windows 포팅:
- 클릭했는데도 템플릿이 계속 보이면, 좌표/대상창/캡처 타이밍 문제일 수 있으니
  디버그로 매우 유용하다. 가능하면 동일하게 구현.

========================
7) 랜덤 실행/랜덤 지연 규칙과의 관계(중요)
========================

- image_module은 “독립실행(Independent Loops)” 대상이다.
- fireMarkerOnce의 “랜덤 실행(확률 스킵)”은 image_module에도 적용될 수 있다.
  (aiEligible 제외 목록에 image_module이 없기 때문)
- 그러나 “랜덤 지연(jitter)”은 image_module에는 적용되지 않는다.
  (aiDefenseEligibleForRandomDelay에서 image_module 제외)

Windows 포팅에서도 동일하게:
- image_module 워커 주기 delayMs는 적용하되 jitterPct=0 고정
- randomClickUse/execProbabilityPercent에 따른 확률 스킵은 Android와 동일 동작 권장

========================
8) 테스트 시나리오
========================

- 케이스 A: 찾기 성공 + 클릭(마커중심)
  - 템플릿이 화면에 확실히 존재하는 영역을 설정
  - imageClickMode=0
  - 10회 검증에서 okVotes>=6이면 click 발생

- 케이스 B: 찾기 성공 + 클릭(템플릿중앙)
  - imageClickMode=1
  - click 좌표가 best.cx,best.cy로 바뀌는지 확인

- 케이스 C: 소리/진동 모드
  - imageClickMode=2 또는 3
  - 클릭 없이 소리/진동만 발생하고, post-verify는 실행되지 않아야 함

- 케이스 D: 점수 흔들림
  - 정확도 50..100 조절 시 minScore 매핑이 0.55..0.85로 바뀌는지
  - okVotes 조건(>=6/10)이 제대로 작동하는지
```

---

## 1) Android 원본 코드 근거(요약)

- `fireMarkerOnce()`의 `if (m.kind == "image_module") { ... }` 블록
  - 검색영역 기본값 500x500
  - minScore 매핑(0.55..0.85)
  - 10회 검증(과반)
  - clickMode: marker_center / template_center / ringtone / vibrate
  - post-verify 5회(클릭 모드만)
- 템플릿 로드: `loadTemplateImage(fileName)`
- 영역 탐색: `findBestTemplateInRegion(tpl, leftS, topS, rightS, bottomS)`
- 중심 검증: `verifyTemplateAtCenter(tpl, best.cx, best.cy)`

### Windows 포팅용 프롬프트: “마커종류 7번(이미지모듈 / image_module)” 작동 원리 (Android 코드 기준)

이 문서는 `c:\ATX_PIC\color_picker_tool\android\app\src\main\kotlin\com\atx\pic\color_picker_tool\ScreenCaptureService.kt`의
`fireMarkerOnce()` 내 **이미지모듈(image_module)** 실행 로직과, 템플릿/매칭 함수(`loadTemplateImage`, `findBestTemplateInRegion`,
`verifyTemplateAtCenter`)를 기준으로 Windows 앱에서 동일하게 구현하기 위한 **복사-붙여넣기용 프롬프트**이다.

---

## 0) 프롬프트(그대로 복사해서 AI에게 지시)

```text
너는 Windows용 자동클릭 앱을 만드는 코딩 에이전트다.
Android 앱(color_picker_tool)의 “이미지모듈(kind=image_module)” 동작을 Windows에서 동일하게 재현해야 한다.

이미지모듈의 핵심 목적:
- 캡처된 화면 프레임에서 특정 영역(region) 안에 템플릿 이미지가 존재하는지 “점수(score)”로 평가한다.
- 점수가 기준(minScore) 이상이면 “찾았다”고 판단한다.
- 단발성 오탐/미탐을 줄이기 위해:
  - 최초 best 후보를 찾은 뒤
  - 해당 중심점(best.cx,best.cy)에서 10회 검증(다수결/과반)으로 최종 확정한다.
- 최종 확정 시 클릭/소리/진동(모드) 중 하나를 수행한다.

========================
입력 데이터(필수)
========================

전역 상태:
- captureReady: bool  // 화면 캡처 프레임 준비 여부
- 최신 프레임 버퍼(frame):
  - bytes: ByteArray (RGB 또는 BGRA 등)
  - frameW/frameH
  - rowStride/pixelStride
  - screenW/screenH (논리 screen 크기)
- pause 기능:
  - sleepPausable(ms): pause 동안 시간 감소가 멈추는 sleep
  - withGlobalPause(action): action 수행 동안 다른 마커 실행을 일시 정지

Marker 모델(image_module에 필요한 필드)
- index: Int
- kind: "image_module"
- xPx, yPx: Int            // screen 기준 중심 좌표(px) (기본 클릭 위치 후보)
- delayMs: Int              // 독립실행 워커의 주기 (readx2 참고)
- imageTemplateFile: String // 템플릿 파일명
- imageStartXPx, imageStartYPx, imageEndXPx, imageEndYPx: Int
  - Android에서는 usable(px) 기준으로 저장(네비바/컷아웃 제외)
  - -1이면 기본값 사용(마커 중심 주변에서 시작)
- imageAccuracyPct: Int (50..100)
- imageW, imageH: Int       // 템플릿 크기(px)
- imageClickMode: Int
  - 0: 마커 위치 클릭(marker_center)
  - 1: 찾은 이미지 중앙 클릭(template_center)
  - 2: 소리내기(ringtone)
  - 3: 진동하기(vibrate)
- imageCropLeftXPx/imageCropTopYPx: Int (템플릿 crop 좌상단, 디버그용)

추가 설정/상태(Android에 존재, Windows에도 권장)
- imageVerifyThirdIntervalMs (0..1000): 10회 검증 중 3번째 이후 간격
- updateImageFoundCenter / updateImageMatchDebug:
  - 디버그 표시를 위해 “찾은 중심(usable)”와 “점수/기준”을 저장(선택)

========================
1) 이미지모듈이 실행되는 루프 위치
========================

Android 구현에서 image_module은 “독립실행(Independent Loops)” 대상이다.
- readx2의 isIndependentKind에 image_module 포함
- 워커가 주기적으로 fireMarkerOnce(image_module)을 호출한다.

Windows 포팅에서도 동일하게:
- image_module을 독립 워커 대상으로 두고, 주기 실행(조건 만족 시만 클릭)

========================
2) image_module 실행 전체 흐름 (Android fireMarkerOnce와 동일)
========================

function fireImageModule(m):
  assert m.kind == "image_module"

  // 2-0) 캡처 준비 없으면 아무 것도 안 함
  if captureReady == false: return SKIP

  // 2-1) 템플릿 로드(그레이스케일 변환 + 캐시)
  tpl = loadTemplateImage(m.imageTemplateFile)
  if tpl == null: return SKIP

  // 2-2) 검색영역(region) 계산: usable(px) -> screen(px)
  usable = getUsableRect()
  wU = max(usable.width, 1)
  hU = max(usable.height, 1)

  // start/end usable 좌표 (기본값)
  // Android 기본:
  //  - startXU/startYU: (imageStartXPx/Y >=0 이면 그 값) else (m.xPx-usable.left)/(m.yPx-usable.top)
  //  - endXU/endYU: (imageEndXPx/Y >=0 이면 그 값) else (start + 500)  // 기본 500x500
  sxU0 = (m.imageStartXPx >= 0 ? m.imageStartXPx : (m.xPx - usable.left))
  syU0 = (m.imageStartYPx >= 0 ? m.imageStartYPx : (m.yPx - usable.top))
  exU0 = (m.imageEndXPx   >= 0 ? m.imageEndXPx   : (sxU0 + 500))
  eyU0 = (m.imageEndYPx   >= 0 ? m.imageEndYPx   : (syU0 + 500))
  sxU0 = clamp(sxU0, 0..wU-1)
  syU0 = clamp(syU0, 0..hU-1)
  exU0 = clamp(exU0, 0..wU-1)
  eyU0 = clamp(eyU0, 0..hU-1)
  sxU = min(sxU0, exU0); exU = max(sxU0, exU0)
  syU = min(syU0, eyU0); eyU = max(syU0, eyU0)

  // screen rect
  leftS   = clamp(usable.left + sxU, usable.left .. usable.right-1)
  topS    = clamp(usable.top  + syU, usable.top  .. usable.bottom-1)
  rightS  = clamp(usable.left + exU, usable.left+1 .. usable.right)
  bottomS = clamp(usable.top  + eyU, usable.top+1  .. usable.bottom)

  // 2-3) 정확도 -> minScore 매핑(= Android와 동일)
  // acc=50 -> 0.55, acc=100 -> 0.85 (선형)
  acc = clamp(m.imageAccuracyPct, 50..100)
  minScore = clamp(0.55 + ((acc-50)/50)*0.30, 0.55..0.85)

  // 2-4) 1차 탐색: region 안에서 best 후보 찾기
  best = findBestTemplateInRegion(tpl, leftS, topS, rightS, bottomS)  // returns {cx,cy,score} in screen px
  if best == null:
    updateDebug(score=-1) (선택)
    return SKIP

  // 디버그: found center usable 저장(선택)
  hitCenterXU = clamp(best.cx - usable.left, 0..wU-1)
  hitCenterYU = clamp(best.cy - usable.top, 0..hU-1)
  updateFoundCenter(hitCenterXU, hitCenterYU)
  updateDebug(score=best.score, minScore=minScore)

  if best.score < minScore:
    return SKIP

  // 2-5) 10회 다중 검증(= Android: verifyTemplateAtCenter 10회, 과반 6/10)
  verifyCount = 10
  scores[0..9]
  iv = clamp(imageVerifyThirdIntervalMs, 0..1000)
  for k in 0..9:
    if k==1: sleepPausable(40ms)
    else if k>=2 and iv>0: sleepPausable(iv)
    scores[k] = verifyTemplateAtCenter(tpl, best.cx, best.cy)
  okVotes = count(scores[k] >= minScore)
  if okVotes < 6:
    return SKIP

  // 2-6) 최종 성공: 클릭/소리/진동 수행 (withGlobalPause)
  markerCenter = (m.xPx, m.yPx)  // Android는 overlay 중심을 우선하지만 Windows는 저장값 사용 가능

  clickMode = clamp(m.imageClickMode, 0..3)
  if clickMode == 1:
    (x,y) = (best.cx, best.cy)   // template center click
  else:
    (x,y) = markerCenter         // marker center (mode 0/2/3)

  ok = withGlobalPause {
    if clickMode == 2:
      playRingtoneOnce(1400ms)
      true
    else if clickMode == 3:
      vibrateOnce(520ms)  // Windows에서는 진동 없으면 beep로 대체 가능
      true
    else:
      click(x,y, pressMs=clickPressMsGlobal)
  }

  // (선택) post-verify(클릭 모드에서만): 클릭 후에도 템플릿이 남아 있으면 경고 로그
  if ok and clickMode in {0,1}:
    postScores[0..4]
    for k in 0..4:
      if k==0: sleepPausable(120ms) else sleepPausable(40ms)
      postScores[k] = verifyTemplateAtCenter(tpl, best.cx, best.cy)
    postMax = max(postScores)
    if postMax >= minScore:
      logWarn("stillFoundAfterClick")

  return ok

========================
3) 템플릿 로드(loadTemplateImage) 요구사항
========================

Android 템플릿 구조:
- TemplateImage { w,h, gray[w*h], lastModified }
- gray는 0..255 그레이스케일
- 파일 변경(lastModified) 감지 + LRU 캐시(총 바이트 상한)

Windows 포팅:
- 템플릿 PNG/JPG를 읽어 (w,h) 그레이스케일 배열로 변환한다.
- lastModified(파일 mtime) 기반으로 캐시 무효화.
- 캐시 상한을 둬서 OOM 방지(LRU + 총 바이트 상한).

========================
4) 매칭 함수(findBestTemplateInRegion) 요구사항(핵심 알고리즘)
========================

Android 매칭은 “MAD(Mean Absolute Difference) 기반 점수”로 단순/고속 구현되어 있다.

점수 정의:
- 템플릿/프레임의 샘플 포인트에서:
  - 화면 픽셀을 그레이(y)로 변환: y = (r*30 + g*59 + b*11)/100
  - abs(y - tpl.gray) 를 누적
- mad = sumAbs / count
- score = clamp(1 - mad/255, 0..1)

1차 탐색:
- region 내에서 left/top을 step 간격으로 스캔:
  - step = max(4, min(w,h)/16)
  - grid = 16 (16x16 샘플)
- bestScore/bestCx/bestCy 업데이트

2차 refine:
- best 주변 refineR = clamp(step, 6..24)
- 더 촘촘한 scoreAtDense(grid2=32, step=2px)로 재탐색
- refine 성공하면 refine 결과(cx,cy,score)를 사용

출력:
- TemplateMatchDebug(cx,cy,score)  // cx,cy는 “템플릿 중심점” screen px

Windows 포팅:
- 동일 MAD 기반으로 시작하면 Android와 점수/민감도가 비슷해진다.
- 속도가 느리면:
  - region을 줄이거나
  - step을 키우거나
  - grid를 낮추는 대신 10회 검증을 유지(오탐 방지)

========================
5) verifyTemplateAtCenter 요구사항
========================

Android verifyTemplateAtCenter(tpl, cx, cy):
- 중심점을 기준으로 tpl.w/tpl.h 크기의 박스를 잡고,
- grid=64 샘플로 MAD score를 계산해 0..1 score 반환
- 좌표는 screen->frame 비율 변환을 적용(캡처 해상도 다를 수 있음)

Windows 포팅:
- 같은 방식으로 “중심점에서의 점수”를 빠르게 계산하는 verify 함수를 둔다.
- 10회 검증(과반)과 post-verify 5회는 그대로 재현(안정성)

========================
6) 랜덤 실행/랜덤 지연 관계(중요)
========================

Android fireMarkerOnce:
- 랜덤 실행(확률 스킵) aiEligible에서 image_module은 제외되지 않음
  -> randomClickUse==true 이면 execProbabilityPercent에 의해 스킵될 수 있음
- 랜덤 지연은 image_module은 제외(aiDefenseEligibleForRandomDelay에서 image_module 제외)
  -> delay jitter는 0 (독립 워커의 주기 대기에서는 jitter=0)

Windows 포팅에서도 동일하게:
- image_module은 확률 스킵 적용 가능(원본과 동일)
- 지연 jitter는 적용하지 않음(원본과 동일)

========================
7) 테스트 시나리오
========================

- 케이스 A: 템플릿이 존재
  - region 내에 템플릿이 있는 화면 준비
  - best.score>=minScore + 10회 검증 과반 만족 => 클릭/모드 동작

- 케이스 B: 템플릿이 없음
  - best.score<minScore 또는 10회 검증 과반 실패 => 아무것도 하지 않음

- 케이스 C: 클릭 모드
  - mode=0: marker center click
  - mode=1: template center click
  - mode=2: 소리 (클릭 없음)
  - mode=3: 진동/대체 동작 (클릭 없음)

- 케이스 D: post-verify
  - 클릭 후에도 템플릿이 남아있으면 경고 로그가 남는지 확인
```

---

## 1) Android 원본 코드 근거(요약)

- `fireMarkerOnce()`의 `if (m.kind == "image_module") { ... }` 블록
  - region(usable->screen), minScore 매핑, 10회 검증(과반), clickMode 분기, post-verify 5회
- 템플릿 캐시/그레이 변환: `TemplateImage` + `loadTemplateImage()`
- 1차 탐색+refine: `findBestTemplateInRegion()`
- 중심 검증 점수: `verifyTemplateAtCenter()`

