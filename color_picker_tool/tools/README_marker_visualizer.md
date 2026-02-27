# 마커 클릭/스와이프 시각화 (ADB 로그 → PC UI)

USB 디버깅(ADB) 로그를 읽어 **클릭/스와이프 좌표를 PC 화면에 색으로 표시**합니다.  
휴대폰 해상도/가로세로 전환은 `ScreenCaptureService` 로그에서 자동 인식합니다.

## 준비

- **Python 3.9+**
- **ADB (platform-tools)**가 PATH에 있어야 함

## 실행 (권장: adb를 파이썬이 직접 실행)

PowerShell:

```powershell
python .\tools\adb_marker_visualizer.py --adb
```

기본으로 수신 로그는 `.\tools\logs\atx_log_*.txt`에 **자동 저장(auto)** 됩니다.  
저장을 끄려면:

```powershell
python .\tools\adb_marker_visualizer.py --adb --no-save
```

자동 저장을 명시적으로 켜려면:

```powershell
python .\tools\adb_marker_visualizer.py --adb --save-log auto
```

## 실행 + 로그 저장(수신한 원본 로그 라인) (경로 지정)

PowerShell:

```powershell
python .\tools\adb_marker_visualizer.py --adb --save-log .\tools\logs\my_run.txt
```

또는(호환 옵션):

```powershell
python .\tools\adb_marker_visualizer.py --adb --save .\tools\logs\my_run.txt
```

## 실행 (현재 쓰는 logcat 파이프를 그대로 사용)

PowerShell:

```powershell
adb logcat -v time ScreenCaptureService:I AutoClickAccessibilityService:I *:S | python .\tools\adb_marker_visualizer.py --stdin
```

저장도 같이:

```powershell
adb logcat -v time ScreenCaptureService:I AutoClickAccessibilityService:I *:S | python .\tools\adb_marker_visualizer.py --stdin --save-log auto
```

## 여러 기기 연결 시(시리얼 지정)

```powershell
python .\tools\adb_marker_visualizer.py --adb --serial <device-serial>
```

## 화면 배율(줌) 조절

- 상단의 `줌` 슬라이더로 화면을 **더 크게/작게** 볼 수 있습니다.
- 캔버스를 마우스로 드래그하면 화면을 **이동(팬)** 할 수 있습니다.
- `FIT` 버튼: 줌=1.0, 이동=0으로 초기화

## 저장된 로그 불러오기/재생(배속)

- UI에서 `로그불러오기` 버튼으로 파일 선택 후 재생(기본 1x)
- `재생/일시정지`, `처음`, 배속(1x/2x/3x/5x/10x) 지원

명령행으로 바로 재생:

```powershell
python .\tools\adb_marker_visualizer.py --replay .\tools\logs\atx_log_20260216_123000.txt --speed 5x
```

※ 배속 의미는 "느리게" 입니다. 예) `2x` = 2배 느리게(시간이 2배로 늘어남)

## 표시 규칙(대략)

- **tap/click**: 빨간 점
- **swipe**: 하늘색 화살표 선
- 로그 라인에 `color_module`, `image_module`, `soloVerify`가 있으면 색이 약간 달라집니다.

