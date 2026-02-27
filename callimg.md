# callimg.md — Windows(Flutter)용 “이미지모듈 이미지 가져오기(템플릿 생성)” 구현 프롬프트

아래 요구사항/데이터 모델/흐름을 **그대로** 구현해서, Android `ScreenCaptureService.showImageModulePickerOverlays()`의 “이미지 가져오기/수정”과 동등한 동작을 Windows(Flutter Desktop)에서 재현하라.

---

## 1) 목표(사용자 기능)

마커 `kind == "image_module"`에서 사용자가 “이미지 가져오기” 버튼을 누르면 다음을 한 번에 수행한다.

- 화면(또는 캡처 프레임) 위에 **두 개의 드래그 가능한 사각형 오버레이**를 띄운다.
  - **빨간 사각형**: 템플릿을 잘라 저장할 “저장영역(crop 영역)”
  - **파란 사각형**: 이후 템플릿 매칭을 수행할 “검색영역(search 영역)” (중앙에 반투명 라벨 “검색영역” 표시)
- 패널(작은 컨트롤 창)에서
  - 저장영역 크기(가로/세로)를 **콤보박스**로 선택하고 “적용”
  - 검색영역 크기(가로/세로)를 **콤보박스**로 선택하고 “적용”
  - “자르기/저장”을 누르면 현재 프레임에서 저장영역을 **PNG로 저장**하고, 마커 설정 화면에 결과를 반영한다.
  - “닫기”는 저장 없이 종료하되, 마지막 조작 상태는 “임시(draft)”로 저장해 재진입 시 복구한다.

---

## 2) 저장해야 하는 마커 필드(결과)

Windows에서도 마커(JSON)에는 아래 값을 **usable 좌표(px)** 기준으로 저장한다.

- `imageTemplateFile`: 저장된 PNG 파일명(예: `20260206_153012_1234.png`)
- `imageW`, `imageH`: 저장영역 크기(px) (Android 범위: 8..1024, UI 제공 범위: 10..300 step 10)
- `imageCropLeftXPx`, `imageCropTopYPx`: 저장영역 좌상단의 위치(usable 기준)
- `imageStartXPx`, `imageStartYPx`, `imageEndXPx`, `imageEndYPx`: 검색영역의 시작/종료(usable 기준)

좌표계 규칙:

- **screen 좌표**: 캡처 프레임(또는 전체 화면)의 픽셀 좌표 (0,0 = 좌상단)
- **usable 좌표**: “시스템 바/네비게이션바 등을 제외한 UI 영역”의 좌표  
  - Android는 `usableRect`를 사용한다.  
  - Windows는 기본적으로 `usableLeft=0, usableTop=0, usableRight=캡처W, usableBottom=캡처H`로 두되, **앱 정책상 시스템 UI(예: 작업표시줄) 제외가 필요하면** 동일하게 usableRect를 정의해서 아래 공식에 적용한다.

변환 공식(필수):

- `cropLeftU = cropRectLeftS - usableLeft`
- `cropTopU  = cropRectTopS  - usableTop`
- `startXU   = searchRectLeftS - usableLeft`
- `startYU   = searchRectTopS  - usableTop`
- `endXU     = (searchRectLeftS + searchRectW) - usableLeft`
- `endYU     = (searchRectTopS  + searchRectH) - usableTop`

모든 U 값은 최소 0으로 clamp 한다.

---

## 3) UI/UX 요구사항(Android와 동일)

### 3-1. 오버레이 구성(동시에 표시)

- **빨간 저장영역 사각형**
  - 테두리 두께 2px 정도, 투명 배경
  - 드래그로 이동 가능(리사이즈는 패널의 “적용”으로만)
  - 화면 밖으로 나가지 않게 clamp (x: 0..screenW-w, y: 0..screenH-h)

- **파란 검색영역 사각형**
  - 테두리 2px, 투명 배경
  - 중앙에 텍스트 “검색영역” (반투명 흰색 + 검은 그림자)
  - 드래그로 이동 가능(리사이즈는 패널의 “적용”으로만)
  - 화면 밖으로 나가지 않게 clamp

- **컨트롤 패널**
  - 드래그 핸들(“≡”)로 패널 이동 가능
  - 제목: 신규면 “이미지 가져오기”, 기존 값 편집이면 “이미지 수정”
  - 파일명 표시: `파일명: <name>` (단일라인, 너무 길면 가운데 생략)
  - 저장영역 크기 콤보 2개(가로/세로) + “적용”
  - 검색영역 크기 콤보 2개(가로/세로) + “적용”
  - 버튼: “자르기/저장”, “닫기”

### 3-2. 콤보 옵션(정확히 동일)

- 저장영역 크기 옵션: **10..300 step 10**
- 검색영역 크기 옵션: **100..2500 step 50**

콤보 초기 선택은 “현재 사각형 크기와 가장 가까운 값(nearest)”로 맞춘다.

### 3-3. 기본 배치 규칙

- 최초 진입 시, 저장영역과 검색영역 기본 크기는 (없으면) 각각 128x128 / 500x500 수준으로 시작해도 되지만,
- Android는 “둘 다 기본값인 첫 진입”에서 **서로 겹치면** 검색영역을 우측→아래→좌측→위 순서 후보로 옮겨 겹침을 피한다.  
  Windows도 동일한 “겹침 회피” 로직을 넣어 UX를 맞춘다.

---

## 4) “임시(draft) 상태” 저장(저장 버튼 없이도 재진입 복구)

Android는 “저장 버튼을 누르기 전”에 사용자가 옮긴 사각형/선택한 파일명을 **마커 JSON에 섞지 않고** draft로만 저장한다.

Windows도 동일하게 구현한다.

### 4-1. Draft 세션(중요)

- 앱 시작/매크로 불러오기 등 “컨텍스트가 바뀌는 순간”에 `draftSessionId`를 새로 만들어 **이전 draft를 무효화**한다.
- draft 읽기는 “세션ID가 동일할 때만” 유효하다.

### 4-2. Draft 저장 대상

`targetIndex`(마커 index) 별로 저장:

- `file`
- `cropW`, `cropH`
- `cropLeftU`, `cropTopU`
- `startXU`, `startYU`, `endXU`, `endYU`
- `sessionId`

저장 타이밍:

- 사각형 드래그 중/종료 시 디바운스(예: 180ms)로 저장
- “닫기” 시에도 마지막 상태 저장
- 외부 이유(모달 강제 종료 등)로 닫힐 때도 마지막 상태 저장

저장 위치:

- Flutter에선 `shared_preferences`에 `imgDraft.<index>.<field>` 같은 키로 저장하거나,
- 앱 내부 상태 + 로컬 json 파일로 저장해도 되나, **마커 JSON(=실제 저장 데이터)에는 저장 전엔 절대 반영하지 말 것.**

---

## 5) 파일 저장 규칙(atximg)

저장 디렉토리:

- Windows: 앱 데이터 폴더(예: `getApplicationSupportDirectory()` 또는 `getApplicationDocumentsDirectory()` 아래) 내에 `atximg/`
- Android와 동일하게 폴더가 없으면 생성

파일명 자동 생성(중복 방지):

- 포맷 예: `yyyyMMdd_HHmmss_<4digits>.png`
- 동일 이름이 존재하면 재시도(최대 20회) 후, 마지막 fallback으로 `epoch_random.png`

### 5-1. “이미지 수정(편집)”에서 덮어쓰기 금지(중요)

Android 동작:

- 편집 상태에서 현재 파일명 == 기존 파일명인 채로 저장을 누르면, **기존 파일을 덮어쓰지 않고** 항상 새 파일명을 생성해서 저장한다.
- 이유: “메크로 저장 전까지는 이전 이미지가 유지”되어야 하므로, 중간 편집이 기존 파일을 파괴하면 안 된다.

Windows도 동일하게:

- `originalFile`이 있고 `currentFile == originalFile`이면 `generateAutoImageFileName()`로 새 파일명을 강제로 사용한다.

---

## 6) 캡처/크롭 구현(핵심)

요구사항:

- “자르기/저장” 클릭 시, 저장영역 사각형의 screen 좌표 `(leftS, topS, wS, hS)`를 이용해 **현재 캡처 프레임**에서 픽셀을 잘라 `PNG`로 저장한다.
- 크롭 결과에는 오버레이 UI(특히 “검색영역” 텍스트)가 섞이면 안 된다.

권장 구현(Windows):

1) **기반 프레임 확보**
   - 가장 안정적인 방법: 픽커 오픈 직전에 화면(또는 대상 윈도우) 스크린샷을 떠서 “정지 이미지”로 쓰고, 오버레이는 그 위에만 그린다.  
     그러면 오버레이가 캡처에 섞일 일이 없다.
   - 라이브 프레임을 계속 갱신한다면:
     - 저장 직전 1프레임 동안 오버레이 라벨을 숨기고,
     - 다음 프레임에서 캡처한 뒤,
     - 라벨을 복구하는 방식으로 처리한다(Android는 80ms 딜레이로 이를 보장).

2) **크롭**
   - `leftS/topS`를 0..(W-1/H-1)로 clamp
   - `right = min(leftS + wS, W)`, `bottom = min(topS + hS, H)`
   - w/h는 최소 1 보장
   - 픽셀 포맷은 ARGB8888 기준으로 다루고 PNG로 저장

3) **저장 성공 후 처리**
   - (필수) draft를 마지막으로 저장해둔다(“저장 전 임시 상태”도 유지)
   - picker UI를 닫고
   - 마커 설정 화면에 아래 값을 전달/반영한다:
     - file, w, h, cropLeftU, cropTopU, startXU,startYU,endXU,endYU

Flutter 화면 전환/전달 방식:

- Android는 브로드캐스트 + 설정 Activity 재오픈을 썼지만,
- Windows(Flutter)에서는 보통:
  - `Navigator.pop(context, PickResult(...))`로 결과 반환
  - 또는 `Provider/Riverpod/Bloc` 상태로 마커 편집 화면에 즉시 반영

---

## 7) 결과 반영 정책(저장 버튼 전/후)

중요한 정책:

- **“자르기/저장”을 누르기 전까지는** 마커 JSON(`markers`)을 변경하지 않는다. (draft만 변경)
- “자르기/저장” 성공 시에만, 마커 JSON의 `imageTemplateFile` 및 좌표/크기를 업데이트한다.
- “닫기”는 마커 JSON 변경 없음(draft만 저장).

---

## 8) 예외/가드(필수)

- 캡처 프레임이 준비되지 않았으면 픽커를 열지 않거나(또는 비활성),
  저장 시에도 실패 메시지를 띄우고 종료하지 않는다.
- 크기/좌표가 이상하면 clamp 후 진행.
- 파일 저장 실패 시 사용자에게 실패를 알리고(토스트/스낵바 등) 상태는 유지한다.

---

## 9) 구현 산출물(Windows 쪽에 꼭 만들어야 하는 것)

- `ImagePickerOverlayPage` (또는 다이얼로그/모달)
  - 스크린샷(또는 라이브 프레임) 표시
  - 빨강/파랑 사각형 드래그 이동
  - 패널(콤보 4개 + 적용 2개 + 저장/닫기)
- `PickResult` 모델
  - file, cropW/H, cropLeftU/cropTopU, startXU/YU/endXU/YU, purpose(optional), targetIndex
- `DraftStore`
  - `draftSessionId` + per-index draft 저장/복구(shared_preferences 권장)
  - 디바운스 저장(180ms 권장)
- `atximg` 디렉토리 관리 + `generateAutoImageFileName()` + “편집 시 덮어쓰기 방지”

---

## 10) (참고) Android 동등 동작 포인트 요약

- 사각형 이동 시 “저장 버튼 없이도” 180ms 디바운스로 draft 저장
- 저장 시:
  - 편집이면 기존 파일 덮어쓰기 금지 → 새 파일명 생성
  - “검색영역” 라벨이 캡처에 포함되는 문제를 피하려고 저장 직전 라벨 숨김 후 다음 프레임에서 캡처
  - 저장 성공하면 결과를 설정 화면에 즉시 반영
- 닫기 시에도 마커는 건드리지 않고 draft만 남김

# 이미지모듈 “이미지 가져오기(크롭 저장)” 프롬프트 (Android 코드 기준)

아래 프롬프트는 `오토클릭짱` Android 구현(`ScreenCaptureService.kt` + `MarkerSettingsActivity.kt`)의 **이미지모듈(image_module) “이미지 가져오기/수정” 기능**을 그대로 재현하기 위한 상세 요구사항이다.  
Windows(Flutter)로 포팅할 때도 **동일한 데이터 키/좌표계/동작 순서**를 유지해야 Android에서 저장된 매크로와 호환된다.

---

### 0) 목표(사용자 경험)

- 사용자는 “이미지 가져오기” 버튼을 누르면
  - 화면 위에 **빨간 사각형(저장/크롭 영역)** 과 **파란 사각형(검색 영역)** 이 나타나고,
  - 작은 **컨트롤 패널**에서 크기(콤보) 조절 및 저장을 누르면,
  - 현재 화면 캡처 프레임에서 **빨간 사각형 영역만 잘라 PNG로 저장**되고,
  - 설정창으로 돌아와 **파일명 + 검색영역 좌표 + 크롭오프셋 + 크롭 W/H**가 자동 반영된다.
- “닫기”를 누르면 저장 없이 설정창으로 복귀한다.
- “저장”을 누르기 전까지 사용자가 움직인 사각형 위치/크기는 **마커 데이터(`flutter.markers`)에 즉시 반영되면 안 되고**, 임시(draft)로만 보존되어야 한다.

---

### 1) 관련 파일(원본 기준)

- **오버레이/캡처/저장/브로드캐스트**: `color_picker_tool/android/app/src/main/kotlin/.../ScreenCaptureService.kt`
- **설정창 버튼/수신/필드 반영**: `color_picker_tool/android/app/src/main/kotlin/.../MarkerSettingsActivity.kt`

---

### 2) 진입점(설정창 → 서비스)

#### 2.1 image_module 버튼

- `MarkerSettingsActivity`에서 image_module 설정 UI의 “이미지 가져오기” 버튼 클릭 시:
  - 현재 입력값(파일명/검색영역/크롭크기/크롭오프셋)을 `ImagePickerPreset`으로 구성한다.
  - `ScreenCaptureService.openImageModulePickerWithPreset(index, preset)` 호출
  - 설정창(Activity)은 **종료하지 않고** 화면에서만 숨긴다:
    - `hiddenForPicker=true`
    - `FLAG_NOT_TOUCHABLE` 추가
    - root view `INVISIBLE` + `alpha=0`

#### 2.2 서비스 진입에서 purpose/draftPrefix 설정

- `openImageModulePickerWithPreset()`는 아래를 보장한다:
  - `imagePickerPurpose = "image_module"`
  - `imagePickerDraftPrefix = "imgDraft"`
  - 메인스레드에서 `showImageModulePickerOverlays(targetIndex, preset)` 실행

> 동일 UI를 solo_verify에서도 재사용하므로, purpose에 따라 저장 필드가 달라진다. (이 문서는 **image_module 목적**에 초점)

---

### 3) 핵심 좌표계 규칙(중요)

- **screen(px)**: 실제 전체 화면 좌표(상단 status bar/하단 navigation bar 포함)
- **usable(px)**: 사용자에게 실제 터치/표시 가능한 영역  
  - 반드시 **Android 네비게이션바(시스템 바) 길이를 정확히 제외한 rect**여야 한다.
  - Android 구현은 `getUsableRectPx()`를 기준으로 (xU, yU)를 기록한다.

#### 저장 규칙

- 오버레이의 `WindowManager.LayoutParams.x/y`는 **screen(px)** 기준.
- 마커에 저장되는 필드(검색영역, 크롭오프셋)는 **usable(px)** 기준으로 저장한다:
  - `cropLeftU = rectLp.x - usable.left`
  - `cropTopU  = rectLp.y - usable.top`
  - `startXU = searchLp.x - usable.left`
  - `startYU = searchLp.y - usable.top`
  - `endXU   = (searchLp.x + searchLp.width) - usable.left`
  - `endYU   = (searchLp.y + searchLp.height) - usable.top`

---

### 4) 오버레이 구성(서비스 내부)

`showImageModulePickerOverlays(targetIndex, preset)`는 다음 3개 오버레이를 동시에 띄운다.

#### 4.1 빨간 사각형(저장/크롭 영역)

- 단순 `View` 1개, 테두리:
  - stroke 2dp, 색상 `#EF4444` (Red)
- 드래그로 위치 이동:
  - `ACTION_DOWN`에서 rawX/rawY, 시작 x/y 저장
  - `ACTION_MOVE`에서 `lp.x/y` 갱신
  - 화면 바깥으로 나가지 않게 clamp
- 이동/종료 시 **draft 상태 디바운스 저장 예약**

#### 4.2 파란 사각형(검색 영역)

- `FrameLayout` 1개 + 내부에 중앙 라벨(TextView) “검색영역”
  - 테두리 stroke 2dp, 색상 `#3B82F6` (Blue)
  - 라벨 텍스트는 반투명 + 그림자(가독성)
- 드래그로 위치 이동(빨간 사각형과 동일)
- 초기 배치:
  - 기본값으로 생성될 때 빨간 사각형과 겹치면,
  - 후보 위치(오른쪽 → 아래 → 왼쪽 → 위)를 시도해 겹치지 않게 이동

#### 4.3 컨트롤 패널(작은 창)

- `LinearLayout(VERTICAL)` + 배경 `overlay_panel_bg_opaque`
- 포함 요소:
  - 드래그 핸들 “≡” (패널 이동)
  - 제목: “이미지 가져오기” 또는 “이미지 수정”
  - 파일명 라벨(수정 불가): `파일명: <name>`
  - 저장영역 크기 콤보 2개 + “적용” 버튼
  - 검색영역 크기 콤보 2개 + “적용” 버튼
  - 버튼 2개:
    - “자르기/저장”
    - “닫기”

#### 4.4 크기 입력은 콤보(스피너)로 제한

- 저장영역(크롭) 크기 옵션:
  - 10..300 step 10
- 검색영역 크기 옵션:
  - 100..2500 step 50
- 현재 값이 옵션에 없으면 “가장 가까운 값”으로 selection 잡기(nearestIdx)

---

### 5) draft(임시 상태) 저장/복원 규칙(매우 중요)

사용자가 오버레이에서 위치/크기를 바꾸더라도 “저장”을 누르기 전까지는 `flutter.markers`를 건드리지 않고, **SharedPreferences에 draft로만 저장**한다.

#### 5.1 draft 저장 키

- 키 prefix는 purpose별로 다름:
  - image_module: `imgDraft`
  - solo_verify: `soloVerifyDraft`
- 실제 key는:
  - `${draftPrefix}.${markerIndex}.session`
  - `${draftPrefix}.${markerIndex}.file`
  - `${draftPrefix}.${markerIndex}.cropW` / `cropH`
  - `${draftPrefix}.${markerIndex}.cropLeftU` / `cropTopU`
  - `${draftPrefix}.${markerIndex}.startXU` / `startYU`
  - `${draftPrefix}.${markerIndex}.endXU` / `endYU`

#### 5.2 session 무효화(섞임 방지)

- `imagePickerDraftSessionId`를 하나 유지한다.
- draft 저장 시 session도 함께 저장하고, 읽을 때 **session이 다르면 draft를 무시**한다.
- 매크로 불러오기(load) 같은 큰 이벤트 후에는 `bumpImagePickerDraftSession()`을 호출해 기존 draft를 전부 무효화한다.

#### 5.3 디바운스 저장

- 사용자가 드래그/적용을 계속하는 동안 너무 자주 저장하지 않도록:
  - 180ms 디바운스(`schedulePersistImagePickerState` → `persistImagePickerStateNow`)

---

### 6) 파일명 정책(덮어쓰기 방지)

- 새로 가져오기(편집 아님)이며 파일명이 비어있으면 자동 생성:
  - `yyyyMMdd_HHmmss_<4digits>.png`
  - 저장 폴더에 동일 파일이 있으면 다시 시도(최대 20회)
- “이미지 수정”에서 저장할 때도, **기존 파일을 덮어쓰지 않는다**:
  - 현재 파일명이 원래(marker에 저장된 original)와 같으면,
  - 무조건 새 자동 파일명으로 저장(사용자가 매크로 저장 전까지는 이전 파일 유지)

---

### 7) “자르기/저장” 버튼 동작(핵심)

#### 7.1 저장 직전 라벨 숨김(오버레이가 캡처에 찍히는 문제 방지)

- 파란 검색영역 내부 라벨 “검색영역”이 스크린샷에 같이 저장되는 문제를 막기 위해:
  - 저장 직전에 라벨을 `INVISIBLE`로 변경
  - 다음 프레임에 반영되도록 **짧게 대기** 후 캡처(원본은 80ms)

#### 7.2 캡처에서 크롭

- 빨간 사각형의 `lp.x/y`(screen 좌표)와, 스피너에서 선택한 \(w,h\)로 크롭을 시도:
  - `bmp = cropBitmapFromScreenRect(leftS, topS, w, h)`
- `cropBitmapFromScreenRect()`의 핵심 요구:
  - 최근 캡처 프레임(`frameBytes`)이 준비되어 있어야 함 (`captureReady==true`)
  - 화면 좌표(screen)를 프레임 좌표(frame)로 변환:
    - `frameX = round(screenX * frameW / screenW)`
    - `frameY = round(screenY * frameH / screenH)`
  - `rowStride`, `pixelStride`를 이용해 RGBA(또는 ARGB로 재조합) 픽셀을 복사
  - 최종적으로 `ARGB_8888 Bitmap` 생성

#### 7.3 PNG 저장

- 저장 폴더: `atxImgDir()` (Android는 `getExternalFilesDir()/atximg`)
- `Bitmap.compress(PNG, quality=100)`로 저장
- 저장 후 임시 bitmap은 반드시 `recycle()`로 해제(메모리)

#### 7.4 결과 좌표 계산(usable로 변환)

- 파란 검색영역 rect(LP)에서 start/end를 usable로 환산:
  - `startXU, startYU, endXU, endYU`
- 빨간 크롭의 left/top도 usable로 환산:
  - `cropLeftU, cropTopU`

#### 7.5 설정창 복귀 + 결과 전달(2중 안전장치)

- 오버레이 제거 전에 마지막 draft 저장을 1회 수행(저장 버튼 없이도 유지되게):
  - `persistImagePickerStateNow(usable)`
- 오버레이 제거:
  - `removeImageModulePickerOverlays()`  
    - 이 때 toolbar 숨김 상태도 pop되어 복구됨(다른 모달이 열려있으면 ref-count로 지연 표시)
- 결과 전달은 2가지로 한다(원본 구현):
  1. **브로드캐스트** `ACTION_IMAGE_MODULE_PICK_RESULT` 송신
  2. **PendingIntent로 MarkerSettingsActivity 재표시**(서비스 startActivity 제한 대비)

#### 7.6 브로드캐스트 payload(필수)

- action: `com.atx.pic.color_picker_tool.action.IMAGE_MODULE_PICK_RESULT`
- extras:
  - `targetIndex` (`EXTRA_PICK_TARGET_INDEX`)
  - `purpose` (`EXTRA_PICK_PURPOSE`) = `"image_module"`
  - `fileName` (`EXTRA_PICK_FILE`) = `<savedFile>`
  - `cropW` (`EXTRA_PICK_W`) / `cropH` (`EXTRA_PICK_H`)
  - `cropLeftU` (`EXTRA_PICK_CROP_LEFT_U`) / `cropTopU` (`EXTRA_PICK_CROP_TOP_U`)
  - `xU,yU,x2U,y2U` (`EXTRA_PICK_X_U`, `EXTRA_PICK_Y_U`, `EXTRA_PICK_X2_U`, `EXTRA_PICK_Y2_U`)

---

### 8) “닫기” 버튼 동작

- 저장 없이 종료한다.
- 단, 마지막 상태는 draft로 저장해 다음에 다시 열었을 때 위치/크기가 유지되게 한다:
  - `persistImagePickerStateNow(usable)`
- 오버레이 제거 후, 브로드캐스트로 “취소” 상태를 알린다:
  - 좌표/크롭 관련 extras를 `-1`로 송신
- PendingIntent로 설정창을 다시 띄운다(visible 복구)

---

### 9) 설정창에서 결과를 반영하는 규칙

`MarkerSettingsActivity`는 2가지 경로로 결과를 반영한다.

#### 9.1 Intent로 즉시 반영(재오픈 시)

- 서비스가 설정창을 PendingIntent로 다시 띄울 때 `EXTRA_PICK_*`를 intent에 넣어준다.
- Activity는 onCreate 초반에 이를 읽어 UI에 즉시 반영한다:
  - 파일명 입력칸 갱신
  - 미리보기(refresh) 갱신
  - 검색영역 start/end 좌표 텍스트 입력칸 갱신
  - `pickedImageW/H`, `pickedCropLeft/Top` 갱신

#### 9.2 브로드캐스트 리시버로 반영(오버레이 닫힘 직후)

- `ACTION_IMAGE_MODULE_PICK_RESULT`를 수신하면 동일하게 UI/변수를 갱신한다.
- “설정창 숨김 복구”도 여기서 수행:
  - `FLAG_NOT_TOUCHABLE` 제거
  - root `VISIBLE`, alpha `1f`

> 여기까지는 “UI 반영” 단계이고, 실제 마커 JSON 저장은 사용자가 설정창에서 “저장/보내기”를 눌러 `saveMarker()`가 수행될 때 확정된다.

---

### 10) 마커에 저장되는 JSON 키(호환 필수)

image_module 마커는 최종적으로 아래 키를 사용한다(필드명은 그대로 유지):

- `imageTemplateFile`: 저장된 PNG 파일명
- `imageW`, `imageH`: 크롭 저장 영역 크기
- `imageCropLeftXPx`, `imageCropTopYPx`: 크롭 left/top (usable 기준)
- `imageStartXPx`, `imageStartYPx`, `imageEndXPx`, `imageEndYPx`: 검색영역 start/end (usable 기준)

호환 참고:

- Activity는 구버전 호환을 위해 기존 key `imageCheckXPx/Y`가 있으면 `imageStartXPx/Y`로 fallback 한다.

---

### 11) Windows(Flutter) 포팅 시 구현 지침(동일 동작 요구)

- **필수 UI/로직**:
  - 화면 위 3개 레이어(크롭 사각형/검색 사각형/패널)를 그리기
  - 드래그 이동 + 화면 clamp
  - 콤보 박스로 크기 제한 제공(저장영역, 검색영역 각각)
  - 저장 전 “검색영역” 라벨이 스크린샷에 포함되지 않도록 처리(저장 직전 숨김 + 1프레임 대기)
  - 저장은 PNG로 `atximg/` 폴더(앱 전용 데이터 폴더) 아래에 생성
  - 저장 전까지는 마커 JSON(메크로 데이터)을 덮어쓰지 않고 **draft**로만 유지
  - draft는 marker index + purpose 기반 key로 저장하고, “세션 id”로 무효화 가능하게 만들기

- **좌표계(중요)**:
  - Windows에서도 “usable rect”를 정의해, taskbar/시스템 영역을 정확히 제외한 기준으로 (xU,yU, cropLeftU, ...)를 저장해야 한다.
  - 크롭 자체는 화면 캡처 이미지의 픽셀 좌표(screen 기준)로 수행하되, 저장되는 값은 usable 기준으로 환산한다.

---

### 12) 구현 체크리스트(테스트)

- 이미지 가져오기 창을 열고, 사각형을 움직였다가 “닫기” 후 재진입하면 위치/크기가 그대로 유지되는가(draft)?
- 저장 없이 종료했을 때 `flutter.markers`가 변경되지 않는가?
- “이미지 수정” 상태에서 저장해도 기존 파일이 덮어써지지 않고 새 파일이 생성되는가?
- 검색영역의 라벨 텍스트가 저장된 PNG에 찍히지 않는가?
- 저장 후 설정창에 파일명/검색영역/크롭 정보가 자동 반영되는가?
- 하단 네비게이션바가 있는 기기에서도 usable 기준 값이 정확한가?

