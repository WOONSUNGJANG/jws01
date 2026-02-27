package com.atx.pic.color_picker_tool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.VibrationEffect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.content.SharedPreferences
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.CheckBox
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import android.graphics.Color
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {
  data class ImagePickerPreset(
    val file: String?,
    val cropW: Int,
    val cropH: Int,
    val cropLeftU: Int,
    val cropTopU: Int,
    val startXU: Int,
    val startYU: Int,
    val endXU: Int,
    val endYU: Int,
  )

  companion object {
    private const val TAG = "ScreenCaptureService"
    const val ACTION_START = "com.atx.pic.color_picker_tool.action.START"
    const val ACTION_START_BASIC = "com.atx.pic.color_picker_tool.action.START_BASIC"
    const val ACTION_STOP = "com.atx.pic.color_picker_tool.action.STOP"
    const val ACTION_OPEN_PANEL = "com.atx.pic.color_picker_tool.action.OPEN_PANEL"
    // (요청) 접근성 툴바(TYPE_ACCESSIBILITY_OVERLAY)에서 보내는 제어 액션
    const val ACTION_TOOL_TOGGLE_MACRO = "com.atx.pic.color_picker_tool.action.TOOL_TOGGLE_MACRO"
    const val ACTION_TOOL_ADD_MARKER = "com.atx.pic.color_picker_tool.action.TOOL_ADD_MARKER"
    const val ACTION_TOOL_TOGGLE_EDIT_MODE = "com.atx.pic.color_picker_tool.action.TOOL_TOGGLE_EDIT_MODE"
    const val ACTION_TOOL_TOGGLE_OBJECTS_VISIBLE = "com.atx.pic.color_picker_tool.action.TOOL_TOGGLE_OBJECTS_VISIBLE"
    const val ACTION_TOOL_SET_EDIT_MODE = "com.atx.pic.color_picker_tool.action.TOOL_SET_EDIT_MODE"
    const val ACTION_TOOL_SET_OBJECTS_VISIBLE = "com.atx.pic.color_picker_tool.action.TOOL_SET_OBJECTS_VISIBLE"
    const val EXTRA_BOOL_VALUE = "value"
    const val ACTION_TOOL_DELETE_ALL_MARKERS = "com.atx.pic.color_picker_tool.action.TOOL_DELETE_ALL_MARKERS"
    const val ACTION_TOOL_RESTART_PROJECTION = "com.atx.pic.color_picker_tool.action.TOOL_RESTART_PROJECTION"
    const val ACTION_TOOL_OPEN_FAV1 = "com.atx.pic.color_picker_tool.action.TOOL_OPEN_FAV1"
    const val ACTION_TOOL_OPEN_FAV2 = "com.atx.pic.color_picker_tool.action.TOOL_OPEN_FAV2"
    const val ACTION_TOOL_OPEN_FAV3 = "com.atx.pic.color_picker_tool.action.TOOL_OPEN_FAV3"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_RESULT_DATA = "resultData"

    // (중요) Android O+에서 채널 중요도는 생성 후 변경 불가.
    // 알림이 안 보이는 문제를 피하기 위해 v2 채널로 새로 생성한다.
    const val NOTIF_CHANNEL_ID = "autoclick_status_v2"
    const val NOTIF_ID = 41

    @Volatile
    var lastPickedArgb: Int = 0xFF000000.toInt()

    @Volatile
    private var instance: ScreenCaptureService? = null

    // (readme3.md 기반) 메뉴 항목 중 개인정보보호정책 URL
    // TODO: 실제 정책 URL로 교체 권장
    private const val PRIVACY_POLICY_URL = "https://www.jwssoft.com/"

    /** Flutter/Activity 등 외부에서 "현재 실행 중인 오버레이"의 마커를 갱신하고 싶을 때 */
    fun refreshMarkersFromPrefs() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          svc.reloadMarkersFromPrefsAndRefresh()
        } catch (_: Throwable) {}
      }
    }

    fun pushMarkersHidden() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post { svc.pushMarkersHiddenInternal() }
    }

    fun popMarkersHidden() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post { svc.popMarkersHiddenInternal() }
    }

    fun pushMarkerMoveModeOffByModal() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post { svc.pushMarkerMoveModeOffByModalInternal() }
    }

    fun popMarkerMoveModeOffByModal() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post { svc.popMarkerMoveModeOffByModalInternal() }
    }

    fun pushToolbarHiddenByModal() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post { svc.pushToolbarHiddenByModalInternal() }
    }

    fun popToolbarHiddenByModal() {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post { svc.popToolbarHiddenByModalInternal() }
    }

    fun startIntent(context: Context, resultCode: Int, data: Intent): Intent {
      return Intent(context, ScreenCaptureService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, data)
      }
    }

    fun startBasicIntent(context: Context): Intent {
      return Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_START_BASIC }
    }

    fun stopIntent(context: Context): Intent {
      return Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }

    /** 다른 코드(액티비티/리시버 등)에서 색상 패널을 열고 싶을 때 사용 */
    fun openPanelIntent(context: Context): Intent {
      return Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_OPEN_PANEL }
    }

    fun openColorModulePicker(targetIndex: Int) {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          try { svc.dbg("openColorModulePicker targetIndex=$targetIndex") } catch (_: Throwable) {}
          svc.showColorModulePickerOverlay(targetIndex)
        } catch (_: Throwable) {}
      }
    }

    fun openImageModulePicker(targetIndex: Int) {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          try { svc.dbg("openImageModulePicker targetIndex=$targetIndex") } catch (_: Throwable) {}
          svc.imagePickerPurpose = MarkerSettingsActivity.PICK_PURPOSE_IMAGE_MODULE
          svc.imagePickerDraftPrefix = "imgDraft"
          svc.showImageModulePickerOverlays(targetIndex, preset = null)
        } catch (_: Throwable) {}
      }
    }

    fun openImageModulePickerWithPreset(targetIndex: Int, preset: ScreenCaptureService.ImagePickerPreset?) {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          try { svc.dbg("openImageModulePickerWithPreset targetIndex=$targetIndex presetFile=${preset?.file}") } catch (_: Throwable) {}
          svc.imagePickerPurpose = MarkerSettingsActivity.PICK_PURPOSE_IMAGE_MODULE
          svc.imagePickerDraftPrefix = "imgDraft"
          svc.showImageModulePickerOverlays(targetIndex, preset = preset)
        } catch (_: Throwable) {}
      }
    }

    fun openSoloVerifyPickerWithPreset(targetIndex: Int, preset: ScreenCaptureService.ImagePickerPreset?) {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          try { svc.dbg("openSoloVerifyPickerWithPreset targetIndex=$targetIndex presetFile=${preset?.file}") } catch (_: Throwable) {}
          svc.imagePickerPurpose = MarkerSettingsActivity.PICK_PURPOSE_SOLO_VERIFY
          svc.imagePickerDraftPrefix = "soloVerifyDraft"
          svc.showImageModulePickerOverlays(targetIndex, preset = preset)
        } catch (_: Throwable) {}
      }
    }

    // solo verify 실행 전 클릭(1회) 좌표 선택(50x50 이동 사각형)
    fun openSoloVerifyPreClickPicker(targetIndex: Int, startXU: Int, startYU: Int) {
      val svc = instance ?: return
      Handler(Looper.getMainLooper()).post {
        try {
          try { svc.dbg("openSoloVerifyPreClickPicker targetIndex=$targetIndex start=($startXU,$startYU)") } catch (_: Throwable) {}
          svc.showSoloVerifyPreClickPickerOverlay(targetIndex, startXU, startYU)
        } catch (_: Throwable) {}
      }
    }

    // 단독 TEST: solo_main 1회 + 콤보 1회 실행(다른 마커 pause)
    fun runSoloTest(mainIndex: Int) {
      val svc = instance ?: return
      // (중요) TEST는 Thread.sleep을 포함하므로 메인에서 돌리면 UI가 멈춘다.
      Thread {
        try {
          // (요청) TEST 실행 시 객체보기 OFF로 자동 변경하고 실행
          val latch = CountDownLatch(1)
          Handler(Looper.getMainLooper()).post {
            try {
              // 최신 좌표/종류를 반영(설정창에서 수정 직후 TEST 시 캐시 stale 방지)
              svc.reloadMarkersFromPrefsIntoCacheOnly()
              svc.setObjectsVisibleInternal(false)
            } catch (_: Throwable) {}
            latch.countDown()
          }
          try {
            latch.await(400, TimeUnit.MILLISECONDS)
          } catch (_: Throwable) {}

          svc.runSoloMainOnceBlocking(mainIndex)
        } catch (_: Throwable) {}
      }.start()
    }
  }

  private var mediaProjection: MediaProjection? = null
  private var projectionCallback: MediaProjection.Callback? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  // MediaProjection이 시스템에 의해 중단될 수 있어, 재승인 유도(스팸 방지용)
  @Volatile private var lastAutoRestartRequestAtMs: Long = 0L
  @Volatile
  private var resizeInProgress: Boolean = false
  @Volatile
  private var resizePending: Boolean = false
  @Volatile
  private var resizeFailStreak: Int = 0
  @Volatile
  private var lastResizeEventAtMs: Long = 0L
  private val resizeDebounceHandler = Handler(Looper.getMainLooper())
  private val resizeDebounceRunnable =
    object : Runnable {
      override fun run() {
        try {
          // 회전 이벤트가 계속 들어오는 동안은 기다렸다가(안정화 후) 한 번만 재구성
          val now = android.os.SystemClock.uptimeMillis()
          if (now - lastResizeEventAtMs < 420L) {
            resizeDebounceHandler.postDelayed(this, 220L)
            return
          }
          ensureCaptureConfiguredToScreen()
        } catch (_: Throwable) {}
      }
    }

  // ImageReader 콜백 처리 전용 스레드(메인 스레드 부하/ANR 방지)
  private var imageThread: HandlerThread? = null
  private var imageHandler: Handler? = null

  // 최신 프레임 메모리(plane0 RGBA). sampleAtScreen()에서 읽으므로 동기화 필요.
  private val frameLock = Any()
  private var frameInfo: FrameInfo? = null
  private var frameBytes: ByteArray = ByteArray(0)

  private var wm: WindowManager? = null
  private var toolbarRoot: View? = null
  private var toolbarLp: WindowManager.LayoutParams? = null
  // 무브 더블탭으로 최소화 토글(재생+무브만 표시)
  @Volatile
  private var toolbarMinimizedManual: Boolean = false
  @Volatile
  private var lastMoveTapAtMs: Long = 0L
  private var lastMoveTapX: Float = 0f
  private var lastMoveTapY: Float = 0f
  private var lastToolbarMinimizedApplied: Boolean? = null

  // 색상 패널(별도 창)
  private var panelRoot: View? = null
  private var panelLp: WindowManager.LayoutParams? = null
  private var tvHex: TextView? = null
  private var tvCoord: TextView? = null
  private var colorSwatch: View? = null
  private var passThroughCb: CheckBox? = null
  private var etX: EditText? = null
  private var etY: EditText? = null
  private var sbOpacity: SeekBar? = null
  private var tvOpacity: TextView? = null

  // (별도) 기존 색상창(overlay_color_picker) UI 참조
  private var colorPickerSwatch: View? = null
  private var colorPickerTvHex: TextView? = null
  private var colorPickerTvCoord: TextView? = null
  private var colorPickerPassThroughCb: CheckBox? = null

  // 터치 오버레이(툴바 영역을 가리지 않도록 상/하 2개 창)
  private var touchTopRoot: View? = null
  private var touchBottomRoot: View? = null
  // 시작 기본: 밑에 앱 터치 ON (터치 오버레이 미표시)
  private var passThroughEnabled: Boolean = true
  private var pickOnceArmed: Boolean = false

  // 화면설정(별도 오버레이 창)
  private var screenSettingsRoot: View? = null
  private var screenSettingsLp: WindowManager.LayoutParams? = null

  // 메크로 저장/열기(별도 오버레이 창)
  private var macroSaveRoot: View? = null
  private var macroSaveLp: WindowManager.LayoutParams? = null
  private var macroOpenRoot: View? = null
  private var macroOpenLp: WindowManager.LayoutParams? = null

  // 설정(화면설정) 메뉴 오버레이(readme3.md의 ⋮ 메뉴 대체)
  private var settingsMenuRoot: View? = null
  private var settingsMenuLp: WindowManager.LayoutParams? = null
  private var settingsTextRoot: View? = null
  private var settingsTextLp: WindowManager.LayoutParams? = null

  // -------- 마커(원형 버블) --------
  private data class Marker(
    var index: Int,
    var kind: String = "click",
    // screen 기준 px (중심 좌표) - 네비게이션바/컷아웃 포함 전체 화면
    var xPx: Int,
    var yPx: Int,
    // screen 기준 pct (0~1)
    var xPct: Double,
    var yPct: Double,
    var delayMs: Int = 300,
    var jitterPct: Int = 50,
    var pressMs: Int = 90,
    // (추가) 더블클릭(2회 클릭) - 스와이프 계열(swipe/swipe_to)에는 적용하지 않음(실행부에서 무시)
    var doubleClick: Boolean = false,
    // swipe 체인/링/단독 등 확장 필드(ATX2 호환)
    var parentIndex: Int = 0,
    var toIndex: Int = 0,
    var moveUpMs: Int = 700,
    // swipe(메인) 실행 방식: 0=순번(기본), 1=독립(딜레이대로 개별 실행)
    var swipeMode: Int = 0,
    var soloLabel: String = "",
    var soloStartDelayMs: Int = 720000, // 기본 12분
    var soloComboCount: Int = 1,
    var soloExec: Boolean = false,
    // module 전용: 방향/동작(없으면 기본 RIGHT 스와이프)
    var moduleDir: String = "R", // U,D,L,R,UL,UR,DL,DR,TAP
    var moduleSoloExec: Boolean = false,
    var moduleLenPx: Int = 0,
    var moduleMoveUpMs: Int = 0,
    var moduleDirMode: Int = 0,
    var modulePattern: Int = 0,
    var modulePatternV2: Int = 0,
    // color 조건부 실행(독립 클릭 등)
    var useColor: Boolean = false,
    var colorIndex: Int = 0,
    var colorR: Int = -1,
    var colorG: Int = -1,
    var colorB: Int = -1,
    // color_module 전용: 체크 좌표(usable px) + 정확도(50~100)
    var colorCheckXPx: Int = -1,
    var colorCheckYPx: Int = -1,
    var colorAccuracyPct: Int = 100,
    // image_module 전용: 템플릿 파일명 + 검색영역(usable px: 시작/종료) + 정확도(50~100) + 템플릿 크기(px)
    var imageTemplateFile: String = "",
    var imageStartXPx: Int = -1,
    var imageStartYPx: Int = -1,
    var imageEndXPx: Int = -1,
    var imageEndYPx: Int = -1,
    var imageAccuracyPct: Int = 90,
    var imageW: Int = 128,
    var imageH: Int = 128,
    // image_module 전용: 클릭 위치
    // 0=마커 위치 클릭(기본), 1=찾은 이미지 중앙좌표 클릭
    var imageClickMode: Int = 0,
    // image_module 전용: 마지막으로 잘라 저장한 템플릿 사각형의 좌상단(usable px)
    var imageCropLeftXPx: Int = -1,
    var imageCropTopYPx: Int = -1,
    // solo_main / solo_item 전용: 클릭실행확인(이미지로 확인)
    var soloVerifyUse: Boolean = false,
    // 0: 이미지가 있으면 "실패"로 간주(재클릭) / 이미지가 없으면 성공 통과
    // 1: 이미지가 있으면 "성공"으로 간주(통과) / 이미지가 없으면 재클릭
    var soloVerifyOnFoundMode: Int = 0,
    var soloVerifyTemplateFile: String = "",
    var soloVerifyStartXPx: Int = -1,
    var soloVerifyStartYPx: Int = -1,
    var soloVerifyEndXPx: Int = -1,
    var soloVerifyEndYPx: Int = -1,
    var soloVerifyAccuracyPct: Int = 80,
    var soloVerifyW: Int = 128,
    var soloVerifyH: Int = 128,
    var soloVerifyCropLeftXPx: Int = -1,
    var soloVerifyCropTopYPx: Int = -1,
    // (추가) solo verify "재개" 시 단독 sub goto 대상
    // - onStopMissing: mode=0(이미지 있으면 클릭/없으면 재개)에서 "없으면 재개" 시 이동할 solo_item index
    // - onStopFound: mode=1(이미지 없으면 클릭/있으면 재개)에서 "있으면 재개" 시 이동할 solo_item index
    // 0이면 기존처럼 단독 구간 즉시 종료(다른 마커 재개)
    var soloVerifyGotoOnStopMissing: Int = 0,
    var soloVerifyGotoOnStopFound: Int = 0,
    // (추가) solo verify 실행 전 "특정좌표 1회 클릭"
    // - 좌표는 usable px 기준(네비게이션바/컷아웃 제외)
    var soloPreClickUse: Boolean = false,
    var soloPreClickXPx: Int = -1,
    var soloPreClickYPx: Int = -1,
    // 랜덤 클릭 스킵
    var randomClickUse: Boolean = true,
  )

  private var markerEditMode: Boolean = false
  private val markerViews = LinkedHashMap<Int, View>()
  private val markerLps = LinkedHashMap<Int, WindowManager.LayoutParams>()
  private var markersCache: MutableList<Marker> = mutableListOf()
  private val markersLock = Any()

  // 마커 드래그 중 좌표 즉시 반영(디바운스 저장)
  private val markerSaveHandler by lazy { Handler(Looper.getMainLooper()) }
  @Volatile
  private var markerSaveScheduled: Boolean = false

  // 설정창(모달) 안전장치용 상태
  private var markersHiddenCount: Int = 0
  private var markerMoveModeOffByModalCount: Int = 0
  private var markerEditModeBeforeModal: Boolean = false
  private var toolbarHiddenByModalCount: Int = 0
  private var objectsVisible: Boolean = true
  private var objectToggleBtn: ImageButton? = null
  private var editToggleBtn: ImageButton? = null
  private var playBtn: ImageButton? = null
  private var plusBtn: ImageButton? = null
  private var trashBtn: ImageButton? = null
  private var settingsBtn: ImageButton? = null
  private var closeBtn: ImageButton? = null
  private var moveBtn: View? = null

  // 중지조건 설정(모달)
  private var stopTimeRoot: View? = null
  private var stopTimeLp: WindowManager.LayoutParams? = null
  private var stopCyclesRoot: View? = null
  private var stopCyclesLp: WindowManager.LayoutParams? = null

  // (요청) 화면에 표시되는 디버그 로그(오버레이/토스트)는 사용하지 않음

  // 편집모드 토글 직후 "의도치 않은 설정창 자동 오픈" 방지용
  @Volatile
  private var suppressOpenMarkerSettingsUntilMs: Long = 0L

  // 편집모드 토글 직후 refreshMarkerViews 호출/재배치 진단용
  @Volatile
  private var lastEditToggleAtMs: Long = 0L
  @Volatile
  private var editToggleSeq: Int = 0

  // -------- 실행 엔진(ATX2 스타일) --------
  @Volatile
  private var macroRunning: Boolean = false
  private var orderedThread: Thread? = null
  private var soloThread: Thread? = null
  private var independentSupervisorThread: Thread? = null
  private val independentThreads = LinkedHashMap<Int, Thread>()

  private val pauseLock = Object()
  @Volatile
  private var pauseCount: Int = 0
  @Volatile
  private var pausedTotalMs: Long = 0L
  @Volatile
  private var pauseBeganAtMs: Long = 0L

  // 편집모드 연결선(스와이프 체인 시각화) - ATX2 동등(구간별 윈도우)
  private data class LinkWin(val view: LinkLineView, val lp: WindowManager.LayoutParams)

  // key: (fromIndex,toIndex) 인코딩
  private val linkWins = LinkedHashMap<Long, LinkWin>()

  // modulePattern / moduleDirMode 상태(커서/랜덤 bag)
  private data class ModuleDirRuntime(
    var cursor: Int = 0,
    val bag: MutableList<String> = mutableListOf(),
  )
  private val moduleDirLock = Any()
  private val moduleDirRuntime = LinkedHashMap<Int, ModuleDirRuntime>()

  // module 실행 중 전역 pause 표식(중첩 안전)
  private val moduleRunningLock = Any()
  @Volatile
  private var moduleRunningCount: Int = 0

  // (reserved) keep for binary compatibility (unused)
  @Volatile
  private var soloAbortRequested: Boolean = false

  // solo verify "재개" 확장: 단독 sub로 goto(점프) 요청
  // - 0: 기존 동작(단독 구간 즉시 종료)
  // - >0: 해당 solo_item index로 점프(나머지 스킵)
  @Volatile
  private var soloGotoRequestedIndex: Int = 0

  private fun pushModuleRunning() {
    synchronized(moduleRunningLock) { moduleRunningCount++ }
    pushGlobalPause()
  }

  private fun popModuleRunning() {
    popGlobalPause()
    synchronized(moduleRunningLock) {
      if (moduleRunningCount > 0) moduleRunningCount--
    }
  }

  private inline fun <T> withModuleRunning(block: () -> T): T {
    pushModuleRunning()
    try {
      return block()
    } finally {
      popModuleRunning()
    }
  }

  private fun pushGlobalPause() {
    synchronized(pauseLock) {
      if (pauseCount == 0) pauseBeganAtMs = android.os.SystemClock.elapsedRealtime()
      pauseCount++
      pauseLock.notifyAll()
    }
  }

  private fun popGlobalPause() {
    synchronized(pauseLock) {
      if (pauseCount > 0) pauseCount--
      if (pauseCount == 0 && pauseBeganAtMs > 0L) {
        val now = android.os.SystemClock.elapsedRealtime()
        pausedTotalMs += (now - pauseBeganAtMs).coerceAtLeast(0L)
        pauseBeganAtMs = 0L
      }
      pauseLock.notifyAll()
    }
  }

  private inline fun <T> withGlobalPause(block: () -> T): T {
    pushGlobalPause()
    try {
      return block()
    } finally {
      popGlobalPause()
    }
  }

  // 화면설정 값들(prefs로 유지)
  @Volatile
  private var toolbarScaleXPercent: Int = 100 // 50~200
  @Volatile
  private var markerScalePercent: Int = 100 // 50~200 (UI는 -50~+100)
  @Volatile
  private var clickPressMsGlobal: Int = 90 // 10~500
  @Volatile
  private var execProbabilityPercent: Int = 80 // 0~100
  @Volatile
  private var randomDelayPctGlobal: Int = 50 // 0~100 (AI탐지방어 ON인 마커에만 적용)
  @Volatile
  private var imageVerifyThirdIntervalMs: Int = 120 // 0~1000
  @Volatile
  private var touchVizEnabled: Boolean = false
  // (디버깅) 실행 마커 정보를 adb(logcat)로 스트리밍
  @Volatile
  private var debugAdbEnabled: Boolean = false
  private fun adbStream(line: String) {
    if (!debugAdbEnabled) return
    try {
      // ScreenCaptureService 태그로 찍어야 기존 logcat 필터(ScreenCaptureService:I)에 그대로 잡힌다.
      Log.i(TAG, "ATX_STREAM $line")
    } catch (_: Throwable) {
      // 공유 화면을 띄우지 못했으면 숨겼던 툴바를 즉시 복구
      try {
        prefs().edit().putBoolean("flutter.restore_toolbar_after_share", false).apply()
      } catch (_: Throwable) {}
      try {
        val r = shareChooserReceiver
        if (r != null) {
          try { unregisterReceiver(r) } catch (_: Throwable) {}
        }
      } catch (_: Throwable) {}
      shareChooserReceiver = null
      try { AutoClickAccessibilityService.requestShowToolbar() } catch (_: Throwable) {}
    }
  }

  private fun adbStreamMarkerMeta(m: Marker, phase: String, extra: String = "") {
    if (!debugAdbEnabled) return
    try {
      val cat = markerCat(m.kind)
      val base =
        "phase=$phase cat=$cat kind=${m.kind} idx=${m.index} xPx=${m.xPx} yPx=${m.yPx} " +
          "delayMs=${m.delayMs} jitterPct=${m.jitterPct} pressMs=${m.pressMs} " +
          "to=${m.toIndex} swipeMode=${m.swipeMode} soloExec=${m.soloExec}"
      val more =
        when (m.kind) {
          "module" ->
            " moduleDir=${m.moduleDir} moduleLenPx=${m.moduleLenPx} moduleMoveUpMs=${m.moduleMoveUpMs} " +
              "dirMode=${m.moduleDirMode} pattern=${m.modulePattern} solo=${m.moduleSoloExec}"
          "color_module" ->
            " check=(${m.colorCheckXPx},${m.colorCheckYPx}) rgb=(${m.colorR},${m.colorG},${m.colorB}) acc=${m.colorAccuracyPct}"
          "image_module" ->
            " tpl=${m.imageTemplateFile} acc=${m.imageAccuracyPct} region=(${m.imageStartXPx},${m.imageStartYPx})-(${m.imageEndXPx},${m.imageEndYPx}) mode=${m.imageClickMode}"
          "solo_main", "solo_item" ->
            " soloVerifyUse=${m.soloVerifyUse} tpl=${m.soloVerifyTemplateFile} acc=${m.soloVerifyAccuracyPct} " +
              "gotoMissing=${m.soloVerifyGotoOnStopMissing} preClickUse=${m.soloPreClickUse} pre=(${m.soloPreClickXPx},${m.soloPreClickYPx})"
          else -> ""
        }
      val ex = if (extra.isNotBlank()) " $extra" else ""
      adbStream("MARKER $base$more$ex")
    } catch (_: Throwable) {}
  }

  private val markerSizePx: Int
    get() = (dpToPx(44f) * (markerScalePercent.coerceIn(50, 200) / 100f)).roundToInt().coerceAtLeast(18)
  private val markerMinDistPx: Int
    get() = (markerSizePx * 1.1f).roundToInt()

  private var lastSampleScreenX: Int = -1
  private var lastSampleScreenY: Int = -1

  private var coordInputRoot: View? = null
  private var coordInputWasTouchOverlayVisible: Boolean = false

  // -------- 실행 로그(설정창: 로그보기) --------
  private data class ExecLogLine(val tMs: Long, val cat: Int, val text: String)
  private val execLogLock = Any()
  private val execLogLines = ArrayDeque<ExecLogLine>()
  private val execLogMaxLines = 450
  @Volatile private var execLogPaused: Boolean = false
  @Volatile private var execLogEnabled: Boolean = false
  @Volatile private var execLogSummaryMode: Boolean = false
  @Volatile private var execLogMinimized: Boolean = false
  private val execLogFilters = BooleanArray(8) { true } // 1..7
  private var execLogRoot: View? = null
  private var execLogLp: WindowManager.LayoutParams? = null
  private var execLogTv: TextView? = null
  private var execLogScroll: ScrollView? = null
  private var execLogBtnStartStop: Button? = null

  private fun markerCat(kind: String): Int {
    return when (kind) {
      "click" -> 1
      "independent" -> 2
      "swipe", "swipe_to" -> 3
      "solo_main", "solo_item", "solo" -> 4
      "module" -> 5
      "color_module" -> 6
      "image_module" -> 7
      else -> 0
    }
  }

  private fun fmtTimeMs(ms: Long): String {
    return try {
      val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
      fmt.format(java.util.Date(ms))
    } catch (_: Throwable) {
      ms.toString()
    }
  }

  private fun execLogColorForCat(cat: Int): Int {
    // 1~7: 빨주노초파남보
    return when (cat) {
      1 -> Color.parseColor("#EF4444") // 빨강
      2 -> Color.parseColor("#F97316") // 주황
      3 -> Color.parseColor("#EAB308") // 노랑
      4 -> Color.parseColor("#22C55E") // 초록
      5 -> Color.parseColor("#3B82F6") // 파랑
      6 -> Color.parseColor("#4F46E5") // 남색(인디고)
      7 -> Color.parseColor("#A855F7") // 보라
      else -> Color.parseColor("#111827")
    }
  }

  private fun loadExecLogPrefs() {
    try {
      val p = prefs()
      execLogEnabled = p.getBoolean("flutter.exec_log_enabled", false)
      execLogPaused = p.getBoolean("flutter.exec_log_paused", false)
      execLogSummaryMode = p.getBoolean("flutter.exec_log_summary_mode", false)
      execLogMinimized = p.getBoolean("flutter.exec_log_minimized", false)
      for (i in 1..7) {
        execLogFilters[i] = p.getBoolean("flutter.exec_log_filter_$i", true)
      }
    } catch (_: Throwable) {}
  }

  private fun persistExecLogPrefs() {
    try {
      val e = prefs().edit()
        .putBoolean("flutter.exec_log_enabled", execLogEnabled)
        .putBoolean("flutter.exec_log_paused", execLogPaused)
        .putBoolean("flutter.exec_log_summary_mode", execLogSummaryMode)
        .putBoolean("flutter.exec_log_minimized", execLogMinimized)
      for (i in 1..7) {
        e.putBoolean("flutter.exec_log_filter_$i", execLogFilters[i])
      }
      e.apply()
    } catch (_: Throwable) {}
  }

  private fun setExecLogEnabled(enabled: Boolean, updatePanelCheckbox: Boolean = true) {
    execLogEnabled = enabled
    try { prefs().edit().putBoolean("flutter.exec_log_enabled", enabled).apply() } catch (_: Throwable) {}
    if (!enabled) {
      removeExecLogOverlay(fromCloseButton = false)
    } else {
      showExecLogOverlay()
    }
    if (updatePanelCheckbox) {
      val root = screenSettingsRoot
      if (root != null) {
        try {
          val cb = root.findViewById<CheckBox>(R.id.cbLogView)
          cb.setOnCheckedChangeListener(null)
          cb.isChecked = enabled
          cb.setOnCheckedChangeListener { _, isChecked -> setExecLogEnabled(isChecked, updatePanelCheckbox = false) }
        } catch (_: Throwable) {}
      }
    }
  }

  private fun refreshExecLogTextOnUi() {
    val tv = execLogTv ?: return
    val filters = execLogFilters.copyOf()
    val out = SpannableStringBuilder()
    val lang = I18n.langFromPrefs(prefs())
    synchronized(execLogLock) {
      for (ln in execLogLines) {
        if (ln.cat in 1..7 && !filters[ln.cat]) continue
        if (execLogSummaryMode) {
          // 요약: 시간 + 카테고리 이름만 표시(카테고리 이름만 색상)
          out.append('[').append(fmtTimeMs(ln.tMs)).append("] ")
          val catStart = out.length
          out.append(I18n.logCatName(lang, ln.cat))
          val catEnd = out.length
          out.append('\n')
          try {
            out.setSpan(
              ForegroundColorSpan(execLogColorForCat(ln.cat)),
              catStart,
              catEnd,
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
          } catch (_: Throwable) {}
        } else {
          // 상세: 현재처럼(전체 라인 색상)
          val start = out.length
          out.append('[').append(fmtTimeMs(ln.tMs)).append("] ")
            .append(ln.text)
            .append('\n')
          val end = out.length
          try {
            out.setSpan(
              ForegroundColorSpan(execLogColorForCat(ln.cat)),
              start,
              end,
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
          } catch (_: Throwable) {}
        }
      }
    }
    try { tv.text = out } catch (_: Throwable) {}
    try {
      execLogScroll?.post { try { execLogScroll?.fullScroll(View.FOCUS_DOWN) } catch (_: Throwable) {} }
    } catch (_: Throwable) {}
  }

  private fun appendExecLog(cat: Int, msg: String) {
    if (!execLogEnabled) return
    if (execLogPaused) return
    if (cat !in 1..7) return
    val now = System.currentTimeMillis()
    synchronized(execLogLock) {
      execLogLines.addLast(ExecLogLine(now, cat, msg))
      while (execLogLines.size > execLogMaxLines) execLogLines.removeFirst()
    }
    // 필터가 켜져있을 때만 UI 업데이트(성능)
    if (!execLogFilters[cat]) return
    Handler(Looper.getMainLooper()).post { try { refreshExecLogTextOnUi() } catch (_: Throwable) {} }
  }

  private fun showExecLogOverlay() {
    val wmLocal = wm ?: return
    if (execLogRoot != null) {
      try { wmLocal.updateViewLayout(execLogRoot, execLogLp) } catch (_: Throwable) {}
      return
    }

    val lang = I18n.langFromPrefs(prefs())
    val p0 = prefs()

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
      setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    val titleRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }

    val drag = TextView(this).apply {
      text = "≡"
      setTextColor(Color.parseColor("#111827"))
      textSize = 18f
      gravity = Gravity.CENTER
      setPadding(0, 0, 0, 0)
    }
    // (요청) "작게/크게"는 버튼 텍스트 대신 아이콘(토글)로 표시
    val btnSmallLarge =
      android.widget.ImageButton(this).apply {
        // minimized=false(큰 상태) => 작게(축소) 아이콘
        // minimized=true(작은 상태) => 크게(확대) 아이콘
        setImageResource(
          if (execLogMinimized) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
        )
        try { setColorFilter(Color.parseColor("#111827")) } catch (_: Throwable) {}
        try { setBackgroundColor(Color.TRANSPARENT) } catch (_: Throwable) {}
        scaleType = android.widget.ImageView.ScaleType.CENTER
        contentDescription = if (execLogMinimized) I18n.logLarge(lang) else I18n.logSmall(lang)
      }
    val dragCol =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          rightMargin = dpToPx(10f)
        }
      }
    dragCol.addView(drag)
    dragCol.addView(
      btnSmallLarge,
      LinearLayout.LayoutParams(dpToPx(30f), dpToPx(30f)).apply {
        topMargin = dpToPx(2f)
      }
    )
    val tvTitle = TextView(this).apply {
      text = I18n.logWindowTitle(lang)
      setTextColor(Color.parseColor("#111827"))
      textSize = 15f
      setTypeface(typeface, android.graphics.Typeface.BOLD)
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val resizeHandle = TextView(this).apply {
      // 리사이즈 핸들(우하단으로 늘리기)
      text = "⤢"
      setTextColor(Color.parseColor("#374151"))
      textSize = 16f
      gravity = Gravity.CENTER
      setPadding(dpToPx(6f), 0, 0, 0)
    }
    titleRow.addView(dragCol)
    titleRow.addView(tvTitle)
    titleRow.addView(resizeHandle)
    panel.addView(titleRow)

    // 작게/크게 + 상세/요약 토글
    val modeRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dpToPx(6f), 0, 0)
      }
    val btnDetail =
      Button(this).apply {
        text = I18n.logDetail(lang)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpToPx(8f) }
      }
    val btnSummary =
      Button(this).apply {
        text = I18n.logSummary(lang)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpToPx(8f) }
      }
    fun applyModeUi() {
      try {
        btnDetail.alpha = if (!execLogSummaryMode) 1.0f else 0.55f
        btnSummary.alpha = if (execLogSummaryMode) 1.0f else 0.55f
      } catch (_: Throwable) {}
    }
    btnDetail.setOnClickListener {
      execLogSummaryMode = false
      try { prefs().edit().putBoolean("flutter.exec_log_summary_mode", false).apply() } catch (_: Throwable) {}
      applyModeUi()
      refreshExecLogTextOnUi()
    }
    btnSummary.setOnClickListener {
      execLogSummaryMode = true
      try { prefs().edit().putBoolean("flutter.exec_log_summary_mode", true).apply() } catch (_: Throwable) {}
      applyModeUi()
      refreshExecLogTextOnUi()
    }
    applyModeUi()
    modeRow.addView(btnDetail)
    modeRow.addView(btnSummary)
    panel.addView(modeRow)

    // 필터(7종) - 중복 선택 / 2줄 표시
    val filterWrap = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dpToPx(6f), 0, dpToPx(6f))
    }
    fun mkFilter(i: Int, kind: String): CheckBox {
      val cb = CheckBox(this).apply {
        text = I18n.logFilterShort(lang, kind)
        setTextColor(Color.parseColor("#374151"))
        textSize = 13f
        isChecked = execLogFilters[i]
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
          rightMargin = dpToPx(6f)
        }
      }
      cb.setOnCheckedChangeListener { _, isChecked ->
        execLogFilters[i] = isChecked
        try { prefs().edit().putBoolean("flutter.exec_log_filter_$i", isChecked).apply() } catch (_: Throwable) {}
        refreshExecLogTextOnUi()
      }
      return cb
    }
    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    row1.addView(mkFilter(1, "click"))
    row1.addView(mkFilter(2, "independent"))
    row1.addView(mkFilter(3, "swipe"))
    row1.addView(mkFilter(4, "solo_main"))
    row2.addView(mkFilter(5, "module"))
    row2.addView(mkFilter(6, "color_module"))
    row2.addView(mkFilter(7, "image_module"))
    filterWrap.addView(row1)
    filterWrap.addView(row2)
    panel.addView(filterWrap)

    val tvLog = TextView(this).apply {
      setTextColor(Color.parseColor("#111827"))
      textSize = 12f
      text = ""
    }
    val sv = ScrollView(this).apply {
      addView(tvLog)
      // 창 크기 변경에 따라 유동적으로 늘어나도록 weight 사용
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    panel.addView(sv)

    val rowBtns = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dpToPx(8f), 0, 0)
    }
    val btnStartStop = Button(this).apply {
      text = if (execLogPaused) I18n.logStart(lang) else I18n.logStop(lang)
      isAllCaps = false
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val btnClear = Button(this).apply {
      text = I18n.logClearScreen(lang)
      isAllCaps = false
      layoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpToPx(8f) }
    }
    val btnClose = Button(this).apply {
      text = I18n.logClose(lang)
      isAllCaps = false
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpToPx(8f) }
    }
    rowBtns.addView(btnStartStop)
    rowBtns.addView(btnClear)
    rowBtns.addView(btnClose)
    panel.addView(rowBtns)

    val usable = getUsableRectPx()
    val minW = dpToPx(260f)
    val minH = dpToPx(300f)
    val maxW = usable.width().coerceAtLeast(minW)
    val maxH = usable.height().coerceAtLeast(minH)
    val savedW = try { p0.getInt("flutter.exec_log_w", dpToPx(320f)) } catch (_: Throwable) { dpToPx(320f) }
    val savedH = try { p0.getInt("flutter.exec_log_h", dpToPx(520f)) } catch (_: Throwable) { dpToPx(520f) }
    val initW = savedW.coerceIn(minW, maxW)
    val initH = savedH.coerceIn(minH, maxH)
    val lp =
      WindowManager.LayoutParams(
        initW,
        initH,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (usable.left + dpToPx(8f)).coerceAtLeast(0)
        y = (usable.top + dpToPx(8f)).coerceAtLeast(0)
      }

    fun clampLpToUsable() {
      val w = lp.width.coerceAtLeast(1)
      val h = lp.height.coerceAtLeast(1)
      val maxX = (usable.right - w).coerceAtLeast(usable.left)
      val maxY = (usable.bottom - h).coerceAtLeast(usable.top)
      lp.x = lp.x.coerceIn(usable.left, maxX)
      lp.y = lp.y.coerceIn(usable.top, maxY)
    }

    // 최소화/복구
    var lastExpandedW = initW
    var lastExpandedH = initH
    val padExpanded = intArrayOf(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
    val padMin = intArrayOf(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
    fun setPanelPadding(pad: IntArray) {
      try { panel.setPadding(pad[0], pad[1], pad[2], pad[3]) } catch (_: Throwable) {}
    }
    fun applyMinimizeUi() {
      val minimized = execLogMinimized
      try {
        btnSmallLarge.setImageResource(
          if (minimized) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
        )
        btnSmallLarge.contentDescription = if (minimized) I18n.logLarge(lang) else I18n.logSmall(lang)
      } catch (_: Throwable) {}
      // 최소화 상태에서는 "크게" 버튼만 보이게
      try {
        // (변경) 이동 아이콘+작게/크게 버튼은 항상 titleRow에 남긴다.
        titleRow.visibility = View.VISIBLE
        tvTitle.visibility = if (minimized) View.GONE else View.VISIBLE
        resizeHandle.visibility = if (minimized) View.GONE else View.VISIBLE
        modeRow.visibility = if (minimized) View.GONE else View.VISIBLE
        filterWrap.visibility = if (minimized) View.GONE else View.VISIBLE
        sv.visibility = if (minimized) View.GONE else View.VISIBLE
        rowBtns.visibility = if (minimized) View.GONE else View.VISIBLE
      } catch (_: Throwable) {}
      if (minimized) {
        setPanelPadding(padMin)
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
      } else {
        setPanelPadding(padExpanded)
        lp.width = lastExpandedW.coerceIn(minW, maxW)
        lp.height = lastExpandedH.coerceIn(minH, maxH)
      }
      clampLpToUsable()
      try { wmLocal.updateViewLayout(panel, lp) } catch (_: Throwable) {}
    }

    // 드래그
    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lp.x
              startY = lp.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lp.x = startX + dx
              lp.y = startY + dy
              clampLpToUsable()
              try { wmLocal.updateViewLayout(panel, lp) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    // 리사이즈(우하단으로 늘리기/줄이기)
    resizeHandle.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startW = 0
        private var startH = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startW = lp.width
              startH = lp.height
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lp.width = (startW + dx).coerceIn(minW, maxW)
              lp.height = (startH + dy).coerceIn(minH, maxH)
              clampLpToUsable()
              try { wmLocal.updateViewLayout(panel, lp) } catch (_: Throwable) {}
              return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
              try {
                if (lp.width > 0 && lp.height > 0) {
                  prefs().edit()
                    .putInt("flutter.exec_log_w", lp.width.coerceAtLeast(1))
                    .putInt("flutter.exec_log_h", lp.height.coerceAtLeast(1))
                    .apply()
                }
              } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    btnSmallLarge.setOnClickListener {
      if (!execLogMinimized) {
        // 작게: 현재 크기를 기억
        if (lp.width > 0 && lp.height > 0) {
          lastExpandedW = lp.width
          lastExpandedH = lp.height
        }
        execLogMinimized = true
        try { prefs().edit().putBoolean("flutter.exec_log_minimized", true).apply() } catch (_: Throwable) {}
        applyMinimizeUi()
      } else {
        // 크게: 이전 크기로 복구
        execLogMinimized = false
        try { prefs().edit().putBoolean("flutter.exec_log_minimized", false).apply() } catch (_: Throwable) {}
        applyMinimizeUi()
      }
    }

    btnStartStop.setOnClickListener {
      execLogPaused = !execLogPaused
      try { prefs().edit().putBoolean("flutter.exec_log_paused", execLogPaused).apply() } catch (_: Throwable) {}
      btnStartStop.text = if (execLogPaused) I18n.logStart(lang) else I18n.logStop(lang)
    }
    btnClear.setOnClickListener {
      // (요청) 화면정리: 로그를 지우고 다시 시작(=중지 해제)
      execLogPaused = false
      try { prefs().edit().putBoolean("flutter.exec_log_paused", execLogPaused).apply() } catch (_: Throwable) {}
      synchronized(execLogLock) { execLogLines.clear() }
      btnStartStop.text = I18n.logStop(lang)
      refreshExecLogTextOnUi()
    }
    btnClose.setOnClickListener {
      removeExecLogOverlay(fromCloseButton = true)
    }

    execLogRoot = panel
    execLogLp = lp
    execLogTv = tvLog
    execLogScroll = sv
    execLogBtnStartStop = btnStartStop
    try {
      wmLocal.addView(panel, lp)
    } catch (_: Throwable) {
      execLogRoot = null
      execLogLp = null
      execLogTv = null
      execLogScroll = null
      execLogBtnStartStop = null
      return
    }
    // 최초 상태 반영(작게/크게)
    try { applyMinimizeUi() } catch (_: Throwable) {}
    refreshExecLogTextOnUi()
  }

  private fun removeExecLogOverlay(fromCloseButton: Boolean) {
    val root = execLogRoot ?: run {
      if (fromCloseButton) {
        // 닫기 버튼 경로에서도 체크를 끈다
        try { prefs().edit().putBoolean("flutter.exec_log_enabled", false).apply() } catch (_: Throwable) {}
        execLogEnabled = false
        // 설정창 체크 해제(열려있으면)
        try { setExecLogEnabled(false, updatePanelCheckbox = true) } catch (_: Throwable) {}
      }
      return
    }
    execLogRoot = null
    execLogLp = null
    execLogTv = null
    execLogScroll = null
    execLogBtnStartStop = null
    try { wm?.removeView(root) } catch (_: Throwable) {}
    if (fromCloseButton) {
      // 닫기 시 설정창의 로그보기 체크도 해제
      try {
        execLogEnabled = false
        prefs().edit().putBoolean("flutter.exec_log_enabled", false).apply()
      } catch (_: Throwable) {}
      try {
        val pr = screenSettingsRoot
        if (pr != null) {
          val cb = pr.findViewById<CheckBox>(R.id.cbLogView)
          cb.setOnCheckedChangeListener(null)
          cb.isChecked = false
          cb.setOnCheckedChangeListener { _, isChecked -> setExecLogEnabled(isChecked, updatePanelCheckbox = false) }
        }
      } catch (_: Throwable) {}
    }
  }

  private var screenReceiver: BroadcastReceiver? = null
  private var shareChooserReceiver: BroadcastReceiver? = null

  @Volatile
  private var captureReady: Boolean = false

  // -------- 이미지 템플릿 캐시(image_module) --------
  private data class TemplateImage(
    val w: Int,
    val h: Int,
    val gray: ByteArray, // size w*h, 0..255
    val lastModified: Long,
  )
  private val templateCacheLock = Any()
  // (메모리) accessOrder=true로 LRU 동작 + 총 바이트 상한으로 OOM 방지
  private val templateCache = LinkedHashMap<String, TemplateImage>(64, 0.75f, true)
  @Volatile private var templateCacheBytes: Long = 0L
  private val templateCacheMaxBytes: Long = 12L * 1024L * 1024L // 12MB

  // -------- 알림/벨소리(이미지모듈 클릭방식=소리내기) --------
  private val ringtoneLock = Any()
  private var playingRingtone: Ringtone? = null
  private var stopRingtoneRunnable: Runnable? = null
  private val ringtoneUiH by lazy { Handler(Looper.getMainLooper()) }

  private fun playCurrentRingtoneOnce(durationMs: Long = 1400L) {
    try {
      val uri =
        try {
          RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
        } catch (_: Throwable) {
          null
        }
          ?: try { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) } catch (_: Throwable) { null }
          ?: try { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) } catch (_: Throwable) { null }
      if (uri == null) return

      ringtoneUiH.post {
        synchronized(ringtoneLock) {
          try { playingRingtone?.stop() } catch (_: Throwable) {}
          playingRingtone = null
          try {
            val r = RingtoneManager.getRingtone(applicationContext, uri) ?: return@synchronized
            playingRingtone = r
            try { r.play() } catch (_: Throwable) {}

            val stop = Runnable {
              synchronized(ringtoneLock) {
                try { playingRingtone?.stop() } catch (_: Throwable) {}
                playingRingtone = null
              }
            }
            stopRingtoneRunnable?.let { try { ringtoneUiH.removeCallbacks(it) } catch (_: Throwable) {} }
            stopRingtoneRunnable = stop
            val d = durationMs.coerceIn(200L, 8000L)
            ringtoneUiH.postDelayed(stop, d)
          } catch (_: Throwable) {}
        }
      }
    } catch (_: Throwable) {}
  }

  private fun vibrateOnce(durationMs: Long = 380L) {
    try {
      // (요청) 진동을 더 강하게: 기본 지속시간을 늘리고(짧게 2펄스),
      // API26+에서는 최대 진폭(255)을 사용한다.
      val d = durationMs.coerceIn(60L, 6000L)
      val vib =
        try {
          if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vm?.defaultVibrator
          } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
          }
        } catch (_: Throwable) {
          null
        }
      if (vib == null) return

      if (Build.VERSION.SDK_INT >= 26) {
        try {
          // 2펄스(강) 패턴: 진동-짧은쉼-진동
          val on1 = (d * 0.55).toLong().coerceAtLeast(80L)
          val gap = 55L
          val on2 = (d - on1).coerceAtLeast(120L)
          val timings = longArrayOf(0L, on1, gap, on2)
          val amps = intArrayOf(0, 255, 0, 255)
          vib.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        } catch (_: Throwable) {}
      } else {
        @Suppress("DEPRECATION")
        try {
          // 구버전은 강도 조절이 어려워 2펄스 패턴으로 체감 강도 상승
          val on1 = (d * 0.55).toLong().coerceAtLeast(80L)
          val gap = 55L
          val on2 = (d - on1).coerceAtLeast(120L)
          vib.vibrate(longArrayOf(0L, on1, gap, on2), -1)
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  private fun clearTemplateCache(reason: String) {
    try {
      synchronized(templateCacheLock) {
        templateCache.clear()
        templateCacheBytes = 0L
      }
      dbg("templateCache cleared reason=$reason")
    } catch (_: Throwable) {}
  }

  // ---------------- 디버그 로그(분석용) ----------------
  private val debugLogLock = Any()
  private var debugLogFile: File? = null
  private val debugLogMaxBytes: Long = 512L * 1024L

  private fun dbg(msg: String, t: Throwable? = null) {
    try {
      val line =
        "${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} " +
          "[${Thread.currentThread().name}] $msg"
      try {
        if (t == null) Log.i(TAG, line) else Log.e(TAG, line, t)
      } catch (_: Throwable) {}

      // 파일 누적 저장(권한 불필요: 앱 전용 external files)
      val f = debugLogFile ?: return
      synchronized(debugLogLock) {
        try {
          if (f.exists() && f.length() > debugLogMaxBytes) {
            val bak = File(f.parentFile, f.nameWithoutExtension + ".1.log")
            try {
              if (bak.exists()) bak.delete()
            } catch (_: Throwable) {}
            try {
              f.renameTo(bak)
            } catch (_: Throwable) {}
          }
          f.parentFile?.mkdirs()
          f.appendText(line + "\n")
          if (t != null) {
            val st = Log.getStackTraceString(t)
            f.appendText(st + "\n")
          }
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  // 접근성 dispatchGesture 성공/실패 카운터를 실행 중 알림에 표시
  private val gestureStatsHandler by lazy { Handler(Looper.getMainLooper()) }
  private val gestureStatsRunnable =
    object : Runnable {
      override fun run() {
        if (!macroRunning) return
        try {
          val st = AutoClickAccessibilityService.getGestureStats()
          val fails = st.cancelled + st.immediateFail + st.timeout
          val reason = st.lastFailReason?.let { " $it" } ?: ""
          val last = try { AutoClickAccessibilityService.getLastAction() } catch (_: Throwable) { null }
          val lastTxt = last?.let { " | $it" } ?: ""
          // 실패 유형을 더 잘 보이게: D/OK/FAIL + lastReason
          updateNotification("실행중 D:${st.dispatched} OK:${st.completed} FAIL:${fails}${reason}$lastTxt")
        } catch (_: Throwable) {}
        gestureStatsHandler.postDelayed(this, 1000L)
      }
    }

  private fun isPointInsideToolbar(rawX: Float, rawY: Float): Boolean {
    val root = toolbarRoot ?: return false
    val lp = toolbarLp ?: return false
    val w = (root.width.takeIf { it > 0 } ?: dpToPx(220f)).coerceAtLeast(1)
    val h = (root.height.takeIf { it > 0 } ?: toolbarHeightPx).coerceAtLeast(1)
    val x = rawX.roundToInt()
    val y = rawY.roundToInt()
    return x in lp.x..(lp.x + w) && y in lp.y..(lp.y + h)
  }

  private val toolbarHeightPx: Int by lazy { dpToPx(48f) }

  @Volatile
  private var overlayOpacityPercent: Int = 85 // 30~150

  // 회전/화면 크기 변경 감지용
  @Volatile
  private var lastScreenW: Int = 0
  @Volatile
  private var lastScreenH: Int = 0
  // (주의) "유리(마커)는 고정 / 밑그림만 회전" 요구:
  // 회전 시 pct로 재배치하지 않고, 회전 변환으로 같은 물리 위치를 유지한다.
  @Volatile private var markerMapScreenW: Int = 0
  @Volatile private var markerMapScreenH: Int = 0
  @Volatile private var markerMapRotation: Int = Surface.ROTATION_0
  private var markerMapUsable: Rect = Rect()
  private var displayListener: DisplayListener? = null

  // Display 변경 이벤트는 기기/OS에 따라 매우 자주 발생할 수 있어(특히 캡처/가상디스플레이),
  // 툴바/패널을 매번 재배치하면 "깜빡임"처럼 보인다. 따라서 디바운스 + 동일 상태면 skip.
  private val displayChangedHandler by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var lastDisplayChangedAtMs: Long = 0L
  @Volatile private var lastDisplayAppliedKey: String? = null
  @Volatile private var displayChangedScheduled: Boolean = false
  private val displayChangedRunnable =
    object : Runnable {
      override fun run() {
        try {
          val now = android.os.SystemClock.uptimeMillis()
          // 마지막 이벤트 이후 조금 더 들어오면 기다렸다가 한 번만 처리
          if (now - lastDisplayChangedAtMs < 220L) {
            // 이미 스케줄되어 있으므로 재스케줄만 하고 빠진다(메인 스레드 부하 최소화)
            displayChangedHandler.postDelayed(this, 180L)
            return
          }

          // 상태 키(회전/usable/screen)가 이전과 같으면 UI 재배치 스킵(깜빡임 방지)
          val screen = getScreenSize()
          val rot = currentRotation()
          val usable = getUsableRectPx()
          val key =
            "rot=$rot|screen=${screen.width}x${screen.height}|usable=${usable.left},${usable.top},${usable.right},${usable.bottom}"
          if (lastDisplayAppliedKey == key) return
          lastDisplayAppliedKey = key

          try {
            // 마커가 숨김 상태면 굳이 refreshMarkerViews까지 돌릴 필요가 없다(불필요한 updateViewLayout 감소)
            remapMarkersForRotationIfNeeded(refreshAfter = shouldShowMarkersNow())
          } catch (_: Throwable) {}

          // (요청) 스크립터/도움말 등 텍스트 창은 회전 시 화면 밖으로 나가거나 닫힌 것처럼 보일 수 있어
          // 표시 중이면 usable 기준으로 크기/위치만 최소한 보정한다(툴바/패널은 무깜빡임 유지).
          try { updateSettingsTextOverlayLayout() } catch (_: Throwable) {}

          // (중요) 메뉴바 "완전 무깜빡임"을 위해,
          // display 변경 이벤트에서는 툴바/패널을 자동 재배치하지 않는다.
          // (일부 기기에서 MediaProjection/VirtualDisplay로 onDisplayChanged가 과도하게 발생하며,
          //  그때마다 updateViewLayout을 하면 깜빡임이 생김)
          // 툴바 위치/방향 자동정렬은 사용자 조작(드래그/열기) 또는 명시적 호출에서만 수행한다.

          // measured size가 0인 타이밍은 resetToolbarToDefaultPosition() 내부에서만 1회 post 재시도한다.
        } catch (_: Throwable) {}
        // 처리 완료(또는 예외) 후 다음 이벤트를 받을 수 있게 플래그 해제
        displayChangedScheduled = false
      }
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    try { CrashLogger.install(this, "ScreenCaptureService") } catch (_: Throwable) {}
    instance = this
    wm = try { getSystemService(WINDOW_SERVICE) as? WindowManager } catch (_: Throwable) { null }
    // (요청) "시작 시 화면공유 권한이 있을 때만" 기능이 보이도록:
    // 이전 실행에서 남은 flutter.capture_ready=true 잔상을 제거한다.
    // 실제 캡처 시작(startProjection) 성공 시점에만 true로 다시 세팅된다.
    captureReady = false
    try { prefs().edit().putBoolean("flutter.capture_ready", false).apply() } catch (_: Throwable) {}
    // (요청) 화면설정의 "객체 크기"는 시작 시 항상 0%(기본 크기)로 리셋한다.
    // 기존 실행에서 사용자가 조절한 값이 남아있더라도, 매크로 불러오기 시 파일에 저장된 값으로
    // 다시 덮어써 적용되도록 "시작 기본값"을 고정한다.
    try {
      markerScalePercent = 100
      prefs().edit().putInt("flutter.marker_scale_percent", 100).apply()
    } catch (_: Throwable) {}
    // 이미지 픽커 "임시(draft)" 세션: 앱 재시작/불러오기 시 이전 draft 무시용
    try { imagePickerDraftSessionId = Random.nextLong() } catch (_: Throwable) { imagePickerDraftSessionId = System.currentTimeMillis() }
    try { ensureNotifChannel() } catch (t: Throwable) { try { dbg("ensureNotifChannel failed", t) } catch (_: Throwable) {} }
    try {
      debugLogFile = File(getExternalFilesDir(null), "autoclick_debug.log")
      dbg("service onCreate, logFile=${debugLogFile?.absolutePath}")
    } catch (_: Throwable) {}
    // 최초 화면 크기 기록(회전 감지)
    try {
      val s = getScreenSize()
      lastScreenW = s.width
      lastScreenH = s.height
    } catch (_: Throwable) {}
    try {
      val s = getScreenSize()
      markerMapScreenW = s.width
      markerMapScreenH = s.height
      markerMapRotation = currentRotation()
      markerMapUsable = getUsableRectPx()
    } catch (_: Throwable) {}
    try { registerDisplayListener() } catch (t: Throwable) { try { dbg("registerDisplayListener failed", t) } catch (_: Throwable) {} }
    try { registerScreenReceiver() } catch (t: Throwable) { try { dbg("registerScreenReceiver failed", t) } catch (_: Throwable) {} }
    try { loadUiPrefs() } catch (t: Throwable) { try { dbg("loadUiPrefs failed", t) } catch (_: Throwable) {} }
    try { loadExecLogPrefs() } catch (t: Throwable) { try { dbg("loadExecLogPrefs failed", t) } catch (_: Throwable) {} }
    // (설정) 서비스 시작 시 로그보기 ON이면 창 복구
    if (execLogEnabled) {
      Handler(Looper.getMainLooper()).post { try { showExecLogOverlay() } catch (_: Throwable) {} }
    }
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    // (메모리) 장시간 재생 시 OOM 방지: 시스템이 메모리 압박을 알려오면 캐시를 적극 정리
    try {
      if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
        clearTemplateCache("onTrimMemory(level=$level)")
      }
      if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
        synchronized(frameLock) {
          frameInfo = null
          frameBytes = ByteArray(0)
        }
      }
      try { dbg("onTrimMemory level=$level cacheCleared=${level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW}") } catch (_: Throwable) {}
    } catch (_: Throwable) {}
  }

  override fun onLowMemory() {
    super.onLowMemory()
    // (메모리) 시스템이 심각한 메모리 부족을 알리면 캐시/프레임을 즉시 해제
    try { clearTemplateCache("onLowMemory") } catch (_: Throwable) {}
    try {
      synchronized(frameLock) {
        frameInfo = null
        frameBytes = ByteArray(0)
      }
    } catch (_: Throwable) {}
    try { dbg("onLowMemory cleared templateCache + frameBytes") } catch (_: Throwable) {}
  }

  private fun currentRotation(): Int {
    return try {
      val wmLocal = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
      @Suppress("DEPRECATION")
      wmLocal.defaultDisplay.rotation
    } catch (_: Throwable) {
      Surface.ROTATION_0
    }
  }

  private fun registerDisplayListener() {
    if (displayListener != null) return
    val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
    val l =
      object : DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
          lastDisplayChangedAtMs = android.os.SystemClock.uptimeMillis()
          // (중요) onDisplayChanged가 매우 자주 들어오면 removeCallbacks/postDelayed를 매번 하면
          // 메인 스레드가 바빠져 버튼 터치(중지 등)가 씹힐 수 있다.
          // 따라서 "한 번만" 스케줄하고, runnable이 마지막 이벤트 시각을 보고 알아서 재시도한다.
          if (!displayChangedScheduled) {
            displayChangedScheduled = true
            displayChangedHandler.postDelayed(displayChangedRunnable, 220L)
          }
        }
      }
    displayListener = l
    try {
      dm.registerDisplayListener(l, Handler(Looper.getMainLooper()))
    } catch (_: Throwable) {}
  }

  private fun unregisterDisplayListener() {
    val l = displayListener ?: return
    displayListener = null
    try {
      val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
      dm.unregisterDisplayListener(l)
    } catch (_: Throwable) {}
  }

  private fun toNatural(x: Int, y: Int, rot: Int, natW: Int, natH: Int): Pair<Int, Int> {
    return when (rot) {
      Surface.ROTATION_0 -> Pair(x, y)
      Surface.ROTATION_90 -> Pair(y, (natH - 1 - x).coerceAtLeast(0))
      Surface.ROTATION_180 -> Pair((natW - 1 - x).coerceAtLeast(0), (natH - 1 - y).coerceAtLeast(0))
      Surface.ROTATION_270 -> Pair((natW - 1 - y).coerceAtLeast(0), x)
      else -> Pair(x, y)
    }
  }

  private fun fromNatural(x: Int, y: Int, rot: Int, natW: Int, natH: Int): Pair<Int, Int> {
    return when (rot) {
      Surface.ROTATION_0 -> Pair(x, y)
      Surface.ROTATION_90 -> Pair((natH - 1 - y).coerceAtLeast(0), x)
      Surface.ROTATION_180 -> Pair((natW - 1 - x).coerceAtLeast(0), (natH - 1 - y).coerceAtLeast(0))
      Surface.ROTATION_270 -> Pair(y, (natW - 1 - x).coerceAtLeast(0))
      else -> Pair(x, y)
    }
  }

  private fun remapMarkersForRotationIfNeeded(refreshAfter: Boolean) {
    val oldRot = markerMapRotation
    val oldW = markerMapScreenW
    val oldH = markerMapScreenH
    val oldU = Rect(markerMapUsable)

    val newScreen = getScreenSize()
    val newRot = currentRotation()
    val newU = getUsableRectPx()

    if (oldW <= 0 || oldH <= 0) {
      markerMapScreenW = newScreen.width
      markerMapScreenH = newScreen.height
      markerMapRotation = newRot
      markerMapUsable = newU
      return
    }

    // (중요) usable(insets) 변화는 회전 매핑 트리거로 쓰지 않는다.
    // 기기/OS에 따라 네비게이션바/IME 등으로 usable이 자주 바뀌는데,
    // 그때마다 remap+save를 하면 "왕복 회전 후 원복 안됨" 같은 드리프트가 생길 수 있다.
    val changed = (newRot != oldRot) || (newScreen.width != oldW) || (newScreen.height != oldH)
    if (!changed) return

    // natW/natH는 "자연 방향" 기준 크기. old 회전 상태에서 역산.
    val newW = newScreen.width.coerceAtLeast(1)
    val newH = newScreen.height.coerceAtLeast(1)
    val maxX = (newW - 1).coerceAtLeast(0)
    val maxY = (newH - 1).coerceAtLeast(0)

    // (중요) 회전 매핑을 "마지막 상태"에서 누적 변환하면,
    // 중간 회전에서의 clamp(경계) 때문에 왕복 시 원복이 불가능해지고 드리프트가 누적될 수 있다.
    // => 항상 "기준(base) 화면모드"에서 현재 화면으로 1회 변환한다.
    val p0 = prefs()
    val baseRaw = try { p0.getString(MARKERS_BASE_KEY, null) } catch (_: Throwable) { null }
    val baseRot = try { p0.getInt(MARKER_BASE_ROT_KEY, oldRot) } catch (_: Throwable) { oldRot }
    val baseW0 = try { p0.getInt(MARKER_BASE_W_KEY, oldW) } catch (_: Throwable) { oldW }
    val baseH0 = try { p0.getInt(MARKER_BASE_H_KEY, oldH) } catch (_: Throwable) { oldH }
    val baseW = baseW0.coerceAtLeast(1)
    val baseH = baseH0.coerceAtLeast(1)

    // 기준(base) 좌표 맵: index -> (xPx,yPx)
    val basePos = HashMap<Int, Pair<Int, Int>>()
    if (!baseRaw.isNullOrBlank()) {
      try {
        val a = JSONArray(baseRaw)
        for (i in 0 until a.length()) {
          val o = a.optJSONObject(i) ?: continue
          val idx = o.optInt("index", Int.MIN_VALUE)
          if (idx == Int.MIN_VALUE) continue
          basePos[idx] = Pair(o.optInt("xPx", 0), o.optInt("yPx", 0))
        }
      } catch (_: Throwable) {}
    }

    val natWBase = if (baseRot == Surface.ROTATION_0 || baseRot == Surface.ROTATION_180) baseW else baseH
    val natHBase = if (baseRot == Surface.ROTATION_0 || baseRot == Surface.ROTATION_180) baseH else baseW
    val natWNew = if (newRot == Surface.ROTATION_0 || newRot == Surface.ROTATION_180) newW else newH
    val natHNew = if (newRot == Surface.ROTATION_0 || newRot == Surface.ROTATION_180) newH else newW

    synchronized(markersLock) {
      for (m in markersCache) {
        // base(screen) -> natural(base) -> natural(new, scale) -> screen(new)
        val b = basePos[m.index]
        val baseAbsX = (b?.first ?: m.xPx).coerceIn(0, (baseW - 1).coerceAtLeast(0))
        val baseAbsY = (b?.second ?: m.yPx).coerceIn(0, (baseH - 1).coerceAtLeast(0))
        val (nxBase, nyBase) = toNatural(baseAbsX, baseAbsY, baseRot, natWBase, natHBase)
        val fx = if (natWBase <= 1) 0.0 else (nxBase.toDouble() / (natWBase - 1).toDouble()).coerceIn(0.0, 1.0)
        val fy = if (natHBase <= 1) 0.0 else (nyBase.toDouble() / (natHBase - 1).toDouble()).coerceIn(0.0, 1.0)
        val nxScaled = (fx * (natWNew - 1).toDouble()).roundToInt().coerceIn(0, (natWNew - 1).coerceAtLeast(0))
        val nyScaled = (fy * (natHNew - 1).toDouble()).roundToInt().coerceIn(0, (natHNew - 1).coerceAtLeast(0))
        val (newAbsX, newAbsY) = fromNatural(nxScaled, nyScaled, newRot, natWNew, natHNew)

        val nx = newAbsX.coerceIn(0, maxX)
        val ny = newAbsY.coerceIn(0, maxY)
        m.xPx = nx
        m.yPx = ny
        m.xPct = (nx.toDouble() / newW.toDouble()).coerceIn(0.0, 1.0)
        m.yPct = (ny.toDouble() / newH.toDouble()).coerceIn(0.0, 1.0)
      }
      // remap 결과는 "새 화면모드" 기준으로 확정되므로 base도 함께 갱신한다.
      saveMarkersToPrefs(markersCache, updateBase = true)
    }

    markerMapScreenW = newScreen.width
    markerMapScreenH = newScreen.height
    markerMapRotation = newRot
    markerMapUsable = newU

    if (refreshAfter) {
      refreshMarkerViews()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 시스템에 의해 서비스가 재시작될 때 intent가 null로 들어올 수 있음(START_STICKY).
    // 이 경우 툴바는 복원하되, MediaProjection은 사용자가 다시 허용해야 하므로 여기서 시작하지 않는다.
    if (intent == null) {
      Handler(Looper.getMainLooper()).post { showOverlay() }
      return START_STICKY
    }

    when (intent?.action) {
      ACTION_STOP -> {
        stopEverything()
        stopSelf()
        return START_NOT_STICKY
      }

      ACTION_OPEN_PANEL -> {
        Handler(Looper.getMainLooper()).post {
          showOverlay()
          callimgsetup()
        }
        return START_STICKY
      }

      ACTION_TOOL_TOGGLE_MACRO -> {
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            toggleMacroRunning()
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_ADD_MARKER -> {
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            addMarkerDefault()
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_TOGGLE_EDIT_MODE -> {
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            toggleMarkerEditMode()
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_TOGGLE_OBJECTS_VISIBLE -> {
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            toggleObjectsVisible()
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_SET_EDIT_MODE -> {
        val v = intent.getBooleanExtra(EXTRA_BOOL_VALUE, false)
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            setMarkerEditModeInternal(v)
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_SET_OBJECTS_VISIBLE -> {
        val v = intent.getBooleanExtra(EXTRA_BOOL_VALUE, true)
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            setObjectsVisibleInternal(v)
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_DELETE_ALL_MARKERS -> {
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            deleteAllMarkers()
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_RESTART_PROJECTION -> {
        Handler(Looper.getMainLooper()).post {
          try {
            showOverlay()
            requestRestartFromOverlay()
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_TOOL_OPEN_FAV1, ACTION_TOOL_OPEN_FAV2, ACTION_TOOL_OPEN_FAV3 -> {
        val which =
          when (intent.action) {
            ACTION_TOOL_OPEN_FAV1 -> 1
            ACTION_TOOL_OPEN_FAV2 -> 2
            else -> 3
          }
        Handler(Looper.getMainLooper()).post {
          try {
            // (요청) 즐겨 버튼 클릭 시: 설정된 메크로를 불러오며 기존 마커는 로드된 것으로 교체된다.
            val name = prefs().getString("flutter.fav$which", "")?.trim().orEmpty()
            if (name.isBlank()) return@post
            val ok = loadMacroFromFile(name)
            if (ok) {
              try { AutoClickAccessibilityService.requestRefreshToolbarLayout() } catch (_: Throwable) {}
            }
          } catch (_: Throwable) {}
        }
        return START_STICKY
      }

      ACTION_START_BASIC -> {
        // (요청) 화면공유(MediaProjection) 권한 묻는 것 없이 "기본 시작"
        // - 캡처 기능은 비활성 상태로 두되, 오버레이(툴바/마커)는 바로 사용 가능해야 한다.
        // (중요) 기본 시작에서는 캡처가 없으므로 captureReady/pref를 확실히 OFF로 유지한다.
        captureReady = false
        try { prefs().edit().putBoolean("flutter.capture_ready", false).apply() } catch (_: Throwable) {}
        Handler(Looper.getMainLooper()).post { showOverlay() }
        return START_STICKY
      }

      ACTION_START -> {
        try {
          val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
          val data = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
          if (resultCode == 0 || data == null) {
            Log.w(TAG, "Missing media projection result data")
            // startForegroundService()로 들어온 케이스에서 여기서 그냥 살아있으면
            // "did not then call startForeground" 크래시가 날 수 있어 즉시 종료한다.
            try {
              stopEverything(deleteMarkers = false)
            } catch (_: Throwable) {}
            try {
              stopSelf()
            } catch (_: Throwable) {}
            return START_NOT_STICKY
          }

          // FGS는 반드시 빠르게 시작(5초 제한) + MediaProjection API가 요구하는
          // "mediaProjection 타입"을 먼저 만족시켜야 getMediaProjection()이 성공한다.
          // (안전) 일부 기기/상황에서 알림 PendingIntent/권한 이슈로 startForeground가 예외를 던질 수 있어 보호한다.
          try {
            ensureNotifChannel()
          } catch (_: Throwable) {}
          try {
            startForegroundCompat(buildNotification("시스템 색상 측정 준비 중"))
          } catch (t: Throwable) {
            // FGS 시작 실패 -> 캡처 시작을 진행하면 더 큰 크래시로 이어질 수 있으므로 여기서 중단하고 툴바만 남긴다.
            try { Log.e(TAG, "startForeground failed", t) } catch (_: Throwable) {}
            try { updateNotification("시작 실패: ${t.javaClass.simpleName}") } catch (_: Throwable) {}
            try { stopProjection() } catch (_: Throwable) {}
            captureReady = false
            try { prefs().edit().putBoolean("flutter.capture_ready", false).apply() } catch (_: Throwable) {}
            Handler(Looper.getMainLooper()).post { showOverlay() }
            // startForegroundService()로 시작된 경우, startForeground가 실패했으면
            // 5초 내 stopSelf() 하지 않으면 "did not then call startForeground" 크래시가 날 수 있다.
            try {
              stopEverything(deleteMarkers = false)
            } catch (_: Throwable) {}
            try {
              stopSelf()
            } catch (_: Throwable) {}
            return START_NOT_STICKY
          }

          // MediaProjection/오버레이 구성은 예외 가능성이 높아 보호
          startProjection(resultCode, data)
          captureReady = true
          showOverlay()
          return START_STICKY
        } catch (t: Throwable) {
          Log.e(TAG, "Failed to start picker", t)
          try {
            updateNotification("시작 실패: ${t.javaClass.simpleName}")
          } catch (_: Throwable) {}
          // 실패하더라도 메뉴바는 남겨서 다시 시작 가능하게 한다.
          try {
            stopProjection()
          } catch (_: Throwable) {}
          captureReady = false
          Handler(Looper.getMainLooper()).post { showOverlay() }
          return START_STICKY
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(NOTIF_ID, notification, ServiceInfoCompat.MEDIA_PROJECTION)
    } else {
      startForeground(NOTIF_ID, notification)
    }
  }

  private fun buildNotification(content: String): Notification {
    // (안전) 일부 환경에서 launchIntent가 null일 수 있어 NPE 방지
    val launchIntent =
      packageManager.getLaunchIntentForPackage(packageName)
        ?: Intent(this, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    val pi =
      try {
        PendingIntent.getActivity(
          this,
          0,
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
      } catch (_: Throwable) {
        null
      }
    val b = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .setContentTitle("오토클릭짱")
      .setContentText(content)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      // (요청) 알림 표시를 최소화(무음/낮은 우선순위)
      .setSilent(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setVisibility(NotificationCompat.VISIBILITY_SECRET)
    if (pi != null) {
      try {
        b.setContentIntent(pi)
      } catch (_: Throwable) {}
    }
    return b.build()
  }

  private fun ensureNotifChannel() {
    if (Build.VERSION.SDK_INT < 26) return
    try {
      val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
      val ch = NotificationChannel(
        NOTIF_CHANNEL_ID,
        "오토클릭짱 상태",
        // (요청) 알림을 최소로(소리/진동/팝업 없음)
        NotificationManager.IMPORTANCE_MIN
      )
      ch.description = "오토클릭짱 실행 상태/오류 표시"
      ch.setShowBadge(false)
      nm.createNotificationChannel(ch)
    } catch (_: Throwable) {}
  }

  // (제거됨) 화면 디버그 오버레이 관련 함수들

  private fun startProjection(resultCode: Int, data: Intent) {
    stopProjection()

    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val mp = mgr.getMediaProjection(resultCode, data)
    if (mp == null) {
      throw IllegalStateException("MediaProjection is null")
    }
    mediaProjection = mp
    captureReady = true
    try {
      prefs().edit().putBoolean("flutter.capture_ready", true).apply()
    } catch (_: Throwable) {}
    // (요청) 화면저장(캡처) 권한이 설정되면 메뉴툴바 이동 아이콘(드래그 핸들) 색상을 빨간색으로 갱신
    try { AutoClickAccessibilityService.requestSyncMoveHandleTint() } catch (_: Throwable) {}
    try { syncMoveIconTintFromPrefs() } catch (_: Throwable) {}
    // 캡처가 활성화되면 숨겨졌던 color_module 마커를 다시 표시
    try {
      Handler(Looper.getMainLooper()).post { try { refreshMarkerViews() } catch (_: Throwable) {} }
    } catch (_: Throwable) {}

    // (안전장치) 시작 직후 메뉴 툴바가 안 뜨는 경우가 있어 강제 표시
    try {
      AutoClickAccessibilityService.requestForceShowToolbar()
    } catch (_: Throwable) {}

    // Android 최신 버전: 캡처 시작(createVirtualDisplay) 전에 콜백 등록이 필요
    val cb =
      projectionCallback ?: object : MediaProjection.Callback() {
        override fun onStop() {
          Log.i(TAG, "MediaProjection stopped by system/user")
          dbg("MediaProjection onStop()")
          // 화면 꺼짐/권한 회수 등으로 프로젝션이 중지될 수 있음.
          // 메뉴바(툴바)는 유지하고, 캡처 리소스만 정리해 사용자가 다시 시작할 수 있게 한다.
          try {
            stopProjection()
          } catch (_: Throwable) {}
          captureReady = false
          Handler(Looper.getMainLooper()).post {
            // (요청) 실행 중(특히 단독실행) 화면공유 권한이 해제되면:
            // - 매크로를 강제 종료하지 않고 "일반모드"로 계속 실행
            // - 단독실행의 soloVerify/goto는 즉시 무효화(순서대로 진행)
            try {
              soloAbortRequested = false
              soloGotoRequestedIndex = 0
            } catch (_: Throwable) {}
            removeTouchOverlay()
            removeCoordInputOverlay()
            showOverlay()
            try {
              updateNotification("캡처 해제됨: 일반모드로 계속")
            } catch (_: Throwable) {}
            // (요청) 시간이 지나면서 캡처가 끊기는 경우가 있어, 가능하면 즉시 재승인 플로우로 유도한다.
            // 단, 화면이 꺼져 있거나 반복 호출되면 방해가 되므로:
            // - interactive(화면 켜짐)일 때만
            // - 15초 이내에는 1번만
            try {
              // (변경) 실행 중에는 흐름을 끊지 않도록 자동 재승인 플로우는 생략한다.
              if (macroRunning) return@post
              val now = android.os.SystemClock.uptimeMillis()
              if (now - lastAutoRestartRequestAtMs >= 15000L) {
                lastAutoRestartRequestAtMs = now
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val interactive = try { pm?.isInteractive != false } catch (_: Throwable) { true }
                if (interactive) {
                  requestRestartFromOverlay()
                }
              }
            } catch (_: Throwable) {}
          }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
          Log.i(TAG, "Captured content resized to ${width}x$height")
          // 회전/해상도 변경 시: "새 VirtualDisplay 생성"은 최신 Android에서 금지될 수 있으므로
          // 기존 VirtualDisplay에 surface/size를 재설정한다.
          // (중요) 회전 이벤트가 연속으로 들어오면 재구성이 겹쳐서 캡처가 끊기거나 "종료"처럼 보일 수 있어
          // 디바운스로 1번만 처리한다.
          try {
            lastResizeEventAtMs = android.os.SystemClock.uptimeMillis()
            resizeDebounceHandler.removeCallbacks(resizeDebounceRunnable)
            resizeDebounceHandler.postDelayed(resizeDebounceRunnable, 520L)
          } catch (_: Throwable) {
            ensureCaptureConfiguredToScreen()
          }
        }
      }.also { projectionCallback = it }

    try {
      mp.registerCallback(cb, Handler(Looper.getMainLooper()))
    } catch (t: Throwable) {
      Log.e(TAG, "registerCallback failed", t)
      throw t
    }

    val (w, h, density) = getScreenSize()
    dbg("startProjection screen=${w}x$h density=$density sdk=${Build.VERSION.SDK_INT}")
    imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

    // 캡처 프레임 복사/보관은 메인 스레드에서 하지 않기
    imageThread = HandlerThread("ColorPickerCapture").apply { start() }
    imageHandler = Handler(imageThread!!.looper)

    try {
      virtualDisplay = mediaProjection?.createVirtualDisplay(
        "ColorPickerCapture",
        w,
        h,
        density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader?.surface,
        null,
        null
      )
      if (virtualDisplay == null) {
        throw IllegalStateException("VirtualDisplay is null")
      }
    } catch (t: Throwable) {
      Log.e(TAG, "createVirtualDisplay failed", t)
      throw t
    }

    imageReader?.setOnImageAvailableListener(
      { reader ->
        var img: Image? = null
        try {
          img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
          val plane = img.planes[0]
          val buffer = plane.buffer
          val rowStride = plane.rowStride
          val pixelStride = plane.pixelStride

          val needed = buffer.remaining()
          synchronized(frameLock) {
            if (frameBytes.size != needed) {
              frameBytes = ByteArray(needed)
            }
            buffer.get(frameBytes, 0, needed)
            frameInfo = FrameInfo(
              width = img.width,
              height = img.height,
              rowStride = rowStride,
              pixelStride = pixelStride
            )
          }
        } catch (_: Throwable) {
          // ignore
        } finally {
          try {
            img?.close()
          } catch (_: Throwable) {}
        }
      },
      imageHandler
    )
  }

  private fun ensureCaptureConfiguredToScreen() {
    if (resizeInProgress) {
      // 진행 중에 또 회전/리사이즈 이벤트가 오면 끝난 후 한 번 더 재시도
      resizePending = true
      return
    }
    val vd = virtualDisplay ?: return
    val handler = imageHandler ?: return

    val screen = getScreenSize()
    val screenChanged =
      (lastScreenW != 0 && lastScreenH != 0) && (screen.width != lastScreenW || screen.height != lastScreenH)
    lastScreenW = screen.width
    lastScreenH = screen.height
    val reader = imageReader ?: return
    val cw = reader.width
    val ch = reader.height

    if (cw == screen.width && ch == screen.height) {
      // 캡처 크기/좌표계는 정상. 오버레이만 재배치해도 됨.
      if (screenChanged) {
        // 회전/크기 변경 시: "유리(마커) 고정"을 위해 회전 변환으로 좌표를 보정
        try {
          remapMarkersForRotationIfNeeded(refreshAfter = false)
        } catch (_: Throwable) {}
      }
      try { if (screenChanged) resetToolbarToDefaultPosition() } catch (_: Throwable) {}
      try { clampToolbarToUsable() } catch (_: Throwable) {}
      try { updateTouchOverlayLayout() } catch (_: Throwable) {}
      try { updatePanelLayout() } catch (_: Throwable) {}
      try { updateColorPanelLayout() } catch (_: Throwable) {}
      try { updateScreenSettingsLayout() } catch (_: Throwable) {}
      try { updateSettingsMenuOverlayLayout() } catch (_: Throwable) {}
      try { updateSettingsTextOverlayLayout() } catch (_: Throwable) {}
      try { updateMacroOverlayLayout(macroSaveRoot, macroSaveLp) } catch (_: Throwable) {}
      try { updateMacroOverlayLayout(macroOpenRoot, macroOpenLp) } catch (_: Throwable) {}
      try { refreshMarkerViews() } catch (_: Throwable) {}
      resizeFailStreak = 0
      return
    }

    resizeInProgress = true
    try {
      dbg("Screen size changed $cw x $ch -> ${screen.width} x ${screen.height}. Reconfiguring VD (setSurface+resize).")

      // 새 ImageReader 준비(새 surface)
      val newReader = ImageReader.newInstance(screen.width, screen.height, PixelFormat.RGBA_8888, 2)

      // 새 리스너 먼저 설정 (프레임 저장 로직은 동일)
      newReader.setOnImageAvailableListener(
        { r ->
          var img: Image? = null
          try {
            img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = img.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val needed = buffer.remaining()
            synchronized(frameLock) {
              if (frameBytes.size != needed) frameBytes = ByteArray(needed)
              buffer.get(frameBytes, 0, needed)
              frameInfo =
                FrameInfo(
                  width = img.width,
                  height = img.height,
                  rowStride = rowStride,
                  pixelStride = pixelStride
                )
            }
          } catch (_: Throwable) {
            // ignore
          } finally {
            try {
              img?.close()
            } catch (_: Throwable) {}
          }
        },
        handler
      )

      // VirtualDisplay에 새 surface 연결 + resize
      vd.surface = newReader.surface
      vd.resize(screen.width, screen.height, screen.densityDpi)

      // 기존 reader 정리 후 교체
      try {
        reader.setOnImageAvailableListener(null, null)
      } catch (_: Throwable) {}
      try {
        reader.close()
      } catch (_: Throwable) {}

      imageReader = newReader

      synchronized(frameLock) {
        frameInfo = null
        frameBytes = ByteArray(0)
      }

      try { if (screenChanged) resetToolbarToDefaultPosition() } catch (_: Throwable) {}
      try { clampToolbarToUsable() } catch (_: Throwable) {}
      try { updateTouchOverlayLayout() } catch (_: Throwable) {}
      try { updatePanelLayout() } catch (_: Throwable) {}
      try { updateColorPanelLayout() } catch (_: Throwable) {}
      try { updateScreenSettingsLayout() } catch (_: Throwable) {}
      try { updateSettingsMenuOverlayLayout() } catch (_: Throwable) {}
      try { updateSettingsTextOverlayLayout() } catch (_: Throwable) {}
      try { updateMacroOverlayLayout(macroSaveRoot, macroSaveLp) } catch (_: Throwable) {}
      try { updateMacroOverlayLayout(macroOpenRoot, macroOpenLp) } catch (_: Throwable) {}
      if (screenChanged) {
        try {
          remapMarkersForRotationIfNeeded(refreshAfter = false)
        } catch (_: Throwable) {}
      }
      try { refreshMarkerViews() } catch (_: Throwable) {}
      resizeFailStreak = 0
    } catch (t: Throwable) {
      dbg("Failed to reconfigure virtual display", t)
      // (중요) 회전 중 일시 실패가 잦아, 여기서 캡처를 꺼버리면 "종료"처럼 보인다.
      // 먼저 짧게 재시도하고, 연속 실패가 일정 횟수를 넘으면 그때만 캡처를 정리한다.
      resizeFailStreak = (resizeFailStreak + 1).coerceAtMost(20)
      // 실패해도 캡처를 끄지 않고(=stopProjection 금지) 안정화 후 계속 재시도한다.
      try {
        resizeDebounceHandler.removeCallbacks(resizeDebounceRunnable)
        resizeDebounceHandler.postDelayed(resizeDebounceRunnable, 650L)
      } catch (_: Throwable) {}
    } finally {
      resizeInProgress = false
      if (resizePending) {
        resizePending = false
        try {
          resizeDebounceHandler.removeCallbacks(resizeDebounceRunnable)
          resizeDebounceHandler.postDelayed(resizeDebounceRunnable, 520L)
        } catch (_: Throwable) {}
      }
    }
  }

  private fun resetToolbarToDefaultPosition() {
    val root = toolbarRoot ?: return
    val lp = toolbarLp ?: return
    // 요구사항: 시작/회전 시 기본 위치
    // - 세로모드: 좌측 벽 중앙 정렬
    // - 가로모드: (변경) 좌측 벽 중앙 정렬(회전 시 항상 동일)
    val usable = getUsableRectPx()
    val isPortrait = usable.height() >= usable.width()

    // (요청) 세로모드에서는 툴바도 세로로(아이콘을 위→아래로) 배치
    applyToolbarOrientation(isPortrait)

    val sx = (root.scaleX.takeIf { it > 0f } ?: 1f).coerceAtLeast(0.5f)
    val rawW = if (root.width > 0) root.width else root.measuredWidth
    val rawH = if (root.height > 0) root.height else root.measuredHeight
    val viewW = (if (rawW > 0) (rawW * sx).roundToInt() else dpToPx(320f)).coerceAtLeast(1)
    val viewH = (if (rawH > 0) rawH else toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
    // (요청) 회전 시에도 항상 "좌측 벽 + 세로 중앙"으로 스냅
    lp.x = usable.left
    lp.y = (usable.top + (usable.height() - viewH) / 2).coerceAtLeast(usable.top)

    // 안전 클램프
    val minX = usable.left
    val maxX = (usable.right - viewW).coerceAtLeast(minX)
    val minY = usable.top
    val maxY = (usable.bottom - viewH).coerceAtLeast(minY)
    lp.x = lp.x.coerceIn(minX, maxX)
    lp.y = lp.y.coerceIn(minY, maxY)
    try {
      wm?.updateViewLayout(root, lp)
    } catch (_: Throwable) {}

    // 회전/레이아웃 직후에는 measured size가 0일 수 있어, 한 번 더 재정렬한다.
    if (rawW == 0 || rawH == 0) {
      try {
        root.post {
          try {
            resetToolbarToDefaultPosition()
          } catch (_: Throwable) {}
        }
      } catch (_: Throwable) {}
    }
  }

  private fun applyToolbarOrientation(isPortrait: Boolean) {
    val root = toolbarRoot ?: return
    val row = root.findViewById<LinearLayout?>(R.id.toolbarRow) ?: return
    try {
      row.orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
      row.gravity = Gravity.CENTER

      val gap = dpToPx(6f)
      val btns =
        listOf<View?>(
          playBtn,
          plusBtn,
          editToggleBtn,
          trashBtn,
          objectToggleBtn,
          settingsBtn,
          closeBtn,
          moveBtn,
        ).filterNotNull()

      for ((i, b) in btns.withIndex()) {
        val lp = (b.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(b.layoutParams)
        if (isPortrait) {
          lp.marginStart = 0
          lp.topMargin = if (i == 0) 0 else gap
        } else {
          lp.topMargin = 0
          lp.marginStart = if (i == 0) 0 else gap
        }
        b.layoutParams = lp
      }

      root.requestLayout()
    } catch (_: Throwable) {}
  }

  private fun clampToolbarToUsable() {
    val root = toolbarRoot ?: return
    val lp = toolbarLp ?: return
    val usable = getUsableRectPx()

    val sx = (root.scaleX.takeIf { it > 0f } ?: 1f).coerceAtLeast(0.5f)
    val rawW = if (root.width > 0) root.width else root.measuredWidth
    val rawH = if (root.height > 0) root.height else root.measuredHeight
    val viewW = (if (rawW > 0) (rawW * sx).roundToInt() else dpToPx(320f)).coerceAtLeast(1)
    val viewH = (if (rawH > 0) rawH else toolbarHeightPx).coerceAtLeast(toolbarHeightPx)

    val minX = usable.left
    val maxX = (usable.right - viewW).coerceAtLeast(minX)
    val minY = usable.top
    val maxY = (usable.bottom - viewH).coerceAtLeast(minY)

    lp.x = lp.x.coerceIn(minX, maxX)
    lp.y = lp.y.coerceIn(minY, maxY)

    try {
      wm?.updateViewLayout(root, lp)
    } catch (_: Throwable) {}

    // 회전 직후 width/height가 0인 경우가 있어, 레이아웃 후 한 번 더 클램프
    if (rawW == 0 || rawH == 0) {
      Handler(Looper.getMainLooper()).post {
        try {
          clampToolbarToUsable()
        } catch (_: Throwable) {}
      }
    }
  }

  private fun stopProjection() {
    try {
      imageReader?.setOnImageAvailableListener(null, null)
    } catch (_: Throwable) {}
    try {
      imageReader?.close()
    } catch (_: Throwable) {}
    imageReader = null

    try {
      virtualDisplay?.release()
    } catch (_: Throwable) {}
    virtualDisplay = null

    try {
      val mp = mediaProjection
      val cb = projectionCallback
      if (mp != null && cb != null) {
        mp.unregisterCallback(cb)
      }
    } catch (_: Throwable) {}

    try {
      mediaProjection?.stop()
    } catch (_: Throwable) {}
    mediaProjection = null
    captureReady = false
    try {
      prefs().edit().putBoolean("flutter.capture_ready", false).apply()
    } catch (_: Throwable) {}
    // (요청) 캡처가 해제되면 메뉴툴바 이동 아이콘(드래그 핸들) 색상을 기본색으로 복구
    try { AutoClickAccessibilityService.requestSyncMoveHandleTint() } catch (_: Throwable) {}
    try { syncMoveIconTintFromPrefs() } catch (_: Throwable) {}
    // 캡처가 비활성화되면 color_module 마커를 즉시 숨김
    try {
      Handler(Looper.getMainLooper()).post { try { refreshMarkerViews() } catch (_: Throwable) {} }
    } catch (_: Throwable) {}

    synchronized(frameLock) {
      frameInfo = null
      frameBytes = ByteArray(0)
    }

    try {
      imageThread?.quitSafely()
    } catch (_: Throwable) {}
    imageThread = null
    imageHandler = null

    // (메모리) 캡처가 꺼진 상태에서는 템플릿 캐시를 유지할 이유가 없으므로 해제
    clearTemplateCache("stopProjection")
  }

  private fun showOverlay() {
    if (toolbarRoot != null) return

    // (요청) 메뉴바는 접근성(TYPE_ACCESSIBILITY_OVERLAY)에서 띄우므로
    // ScreenCaptureService에서는 기존 TYPE_APPLICATION_OVERLAY 툴바를 만들지 않는다.
    try {
      val useAsToolbar = prefs().getBoolean("flutter.use_accessibility_toolbar", true)
      if (useAsToolbar) return
    } catch (_: Throwable) {}

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_floating_toolbar, null)
    toolbarRoot = root

    // 아이콘 버튼 정의(요구사항)
    // 1) 재생: 자동 실행 ON/OFF (길게 누르면 화면공유 재시작)
    playBtn = root.findViewById(R.id.btnPlay)
    playBtn?.setOnClickListener { toggleMacroRunning() }
    playBtn?.setOnLongClickListener {
      requestRestartFromOverlay()
      true
    }
    // 2) 마커추가
    plusBtn = root.findViewById(R.id.btnPlus)
    plusBtn?.setOnClickListener { addMarkerDefault() }
    // 3) 편집모드(마커 이동 모드)
    editToggleBtn = root.findViewById<ImageButton>(R.id.btnPencil)
    updateEditToggleIcon()
    editToggleBtn?.setOnClickListener { toggleMarkerEditMode() }
    // 4) 마커 전체 삭제
    trashBtn = root.findViewById(R.id.btnTrash)
    trashBtn?.setOnClickListener { deleteAllMarkers() }
    // 5) 객체보기 토글(마커 표시/숨김)
    objectToggleBtn = root.findViewById<ImageButton>(R.id.btnGear)
    updateObjectToggleIcon()
    objectToggleBtn?.setOnClickListener { toggleObjectsVisible() }
    // 6) 설정(색상 패널)
    settingsBtn = root.findViewById(R.id.btnSettings)
    settingsBtn?.setOnClickListener { callimgsetup() }
    // 6) 종료
    closeBtn = root.findViewById(R.id.btnClose)
    closeBtn?.setOnClickListener {
      stopEverything()
      stopSelf()
    }

    // 툴바 오버레이(이동 가능한 플로팅 메뉴)
    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        // 오버레이 자체가 MediaProjection 캡처에 포함되면 "아래 화면"이 아니라 "오버레이 색"을 찍게 됨.
        // FLAG_SECURE로 오버레이 창을 캡처(스크린샷/화면녹화/MediaProjection) 대상에서 제외.
        WindowManager.LayoutParams.FLAG_SECURE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    toolbarLp = lp

    applyOverlayOpacity()

    // 툴바 이동: 종료 옆 "무브" 아이콘에서 드래그
    moveBtn = root.findViewById(R.id.btnMove)
    // 캡처(화면저장) 권한 상태에 따라 이동 아이콘 색상 반영
    try { syncMoveIconTintFromPrefs() } catch (_: Throwable) {}

    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    val dragListener =
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var isDragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val wmLocal = wm ?: return false
          val lpLocal = toolbarLp ?: return false

          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              isDragging = false
              return true
            }

            MotionEvent.ACTION_MOVE -> {
              val dxF = event.rawX - downX
              val dyF = event.rawY - downY
              if (!isDragging) {
                if (kotlin.math.abs(dxF) < touchSlop && kotlin.math.abs(dyF) < touchSlop) return true
                isDragging = true
              }

              val dx = dxF.roundToInt()
              val dy = dyF.roundToInt()

              val screen = getScreenSize()
              val sx = (toolbarRoot?.scaleX ?: 1f).coerceAtLeast(0.5f)
              val viewW = (((toolbarRoot?.width ?: root.width) * sx).roundToInt()).coerceAtLeast(1)
              val viewH = (toolbarRoot?.height ?: root.height).coerceAtLeast(toolbarHeightPx)

              // 요구사항: 네비게이션바/카메라홀(컷아웃) 무시하고 전체 화면 사용
              val minX = 0
              val maxX = (screen.width - viewW).coerceAtLeast(minX)
              val minY = 0
              val maxY = (screen.height - viewH).coerceAtLeast(minY)

              lpLocal.x = (startX + dx).coerceIn(minX, maxX)
              lpLocal.y = (startY + dy).coerceIn(minY, maxY)

              try {
                wmLocal.updateViewLayout(root, lpLocal)
              } catch (_: Throwable) {}

              updateTouchOverlayLayout()
              updatePanelLayout()
              updateColorPanelLayout()
              updateScreenSettingsLayout()
              updateMacroOverlayLayout(macroSaveRoot, macroSaveLp)
              updateMacroOverlayLayout(macroOpenRoot, macroOpenLp)
              return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
              val wasDragging = isDragging
              isDragging = false
              if (wasDragging) return true

              // 무브 더블탭: 메뉴바 최소화/복구 토글
              val now = android.os.SystemClock.uptimeMillis()
              val dt = now - lastMoveTapAtMs
              val dtx = kotlin.math.abs(event.rawX - lastMoveTapX)
              val dty = kotlin.math.abs(event.rawY - lastMoveTapY)
              val doubleTimeout = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(180L)
              // (중요) 시간 + 위치가 둘 다 가까울 때만 더블탭으로 인정한다.
              // 그래야 드래그 직후/다른 조작 직후의 "가짜 더블탭"이 줄어든다.
              val nearSameSpot = dtx <= (touchSlop * 2) && dty <= (touchSlop * 2)
              lastMoveTapAtMs = now
              lastMoveTapX = event.rawX
              lastMoveTapY = event.rawY

              if (dt in 1L..doubleTimeout && nearSameSpot) {
                toolbarMinimizedManual = !toolbarMinimizedManual
                try {
                  setToolbarButtonsLockedForRunning(macroRunning)
                  clampToolbarToUsable()
                  updateTouchOverlayLayout()
                } catch (_: Throwable) {}
              }
              return true
            }
          }
          return false
        }
      }

    moveBtn?.setOnTouchListener(dragListener)

    try {
      wm?.addView(root, lp)
    } catch (_: Throwable) {
      // 오버레이 추가 실패(권한/토큰 이슈 등) 시 리소스 정리
      toolbarRoot = null
      toolbarLp = null
      // (중요) 회전/전환 중 일시적으로 addView가 실패할 수 있다.
      // 여기서 서비스를 종료하면 "오류로 종료"처럼 보이므로, 잠깐 후 재시도한다.
      try {
        Handler(Looper.getMainLooper()).postDelayed({ try { showOverlay() } catch (_: Throwable) {} }, 700L)
      } catch (_: Throwable) {}
      return
    }

    // 시작 시 기본 위치 적용(세로: 좌측 중앙 / 가로: 상단 중앙)
    // 측정 전에는 오차가 있을 수 있어 post로 한 번 더 적용한다.
    try {
      resetToolbarToDefaultPosition()
      root.post {
        try {
          resetToolbarToDefaultPosition()
          clampToolbarToUsable()
          updateTouchOverlayLayout()
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    updatePlayStopIcon()
    setToolbarButtonsLockedForRunning(macroRunning)

    // 회전/인셋 변화에도 화면 밖으로 안 나가게 즉시 클램프
    clampToolbarToUsable()

    // 기본은 색상 샘플링 모드(전체 화면 터치 레이어를 추가)
    if (!passThroughEnabled) {
      showTouchOverlay()
    }

    // 마커 복원/표시
    ensureMarkersLoadedAndShown()
    applyMarkersVisibility()
  }

  private fun loadUiPrefs() {
    try {
      val p = prefs()
      overlayOpacityPercent = p.getInt("flutter.toolbar_opacity_percent", overlayOpacityPercent).coerceIn(30, 150)
      toolbarScaleXPercent = p.getInt("flutter.toolbar_scale_x_percent", 100).coerceIn(50, 200)
      markerScalePercent = p.getInt("flutter.marker_scale_percent", 100).coerceIn(50, 200)
      clickPressMsGlobal = p.getInt("flutter.click_press_ms", 90).coerceIn(10, 500)
      execProbabilityPercent = p.getInt("flutter.exec_probability_percent", 80).coerceIn(0, 100)
      randomDelayPctGlobal = p.getInt("flutter.random_delay_pct", 50).coerceIn(0, 100)
      imageVerifyThirdIntervalMs = p.getInt("flutter.image_verify_third_interval_ms", 120).coerceIn(0, 1000)
      touchVizEnabled = p.getBoolean("flutter.touch_viz_enabled", false)
      debugAdbEnabled = p.getBoolean("flutter.debug_adb_enabled", false)
    } catch (_: Throwable) {}
  }

  private fun applyToolbarScaleX() {
    val sx = (toolbarScaleXPercent.coerceIn(50, 200) / 100f)
    toolbarRoot?.scaleX = sx
  }

  private fun updateEditToggleIcon() {
    try {
      editToggleBtn?.setImageResource(
        if (markerEditMode) R.drawable.cp_edit_on else R.drawable.cp_edit_off
      )
    } catch (_: Throwable) {}
  }

  private fun persistToggleStatesToPrefs() {
    try {
      prefs().edit()
        .putBoolean("flutter.marker_edit_mode", markerEditMode)
        .putBoolean("flutter.objects_visible", objectsVisible)
        .apply()
    } catch (_: Throwable) {}
  }

  private fun toggleObjectsVisible() {
    // (중요) 토글 시점에 디바운스 저장이 아직 반영되지 않았으면,
    // 이후 어떤 갱신(refresh/reload)에서 예전 좌표로 "점프"처럼 보일 수 있다.
    // 토글 직전에 현재 markersCache 좌표를 prefs에 즉시 flush해 고정한다.
    // (주의) 여기서 오버레이 중심 재계산(sync)을 하면, 창 크기/패딩/측정 타이밍 때문에
    // 좌표가 미세하게 "밀리는" 현상이 생길 수 있어 flush만 한다.
    try {
      flushMarkersSaveNow()
    } catch (_: Throwable) {}
    objectsVisible = !objectsVisible
    updateObjectToggleIcon()
    applyMarkersVisibility()
    persistToggleStatesToPrefs()
    // (중요) 마커 오버레이가 툴바보다 위로 올라가면(추가/갱신 순서 등)
    // 툴바 버튼을 눌러도 마커가 터치를 먼저 받아 "자동 클릭"처럼 보일 수 있다.
    // 객체보기 토글 직후에는 툴바를 최상단으로 고정한다.
    try {
      bringToolbarToFrontSafe()
    } catch (_: Throwable) {}
    updateNotification(if (objectsVisible) "객체보기 ON" else "객체보기 OFF")
  }

  private fun setObjectsVisibleInternal(visible: Boolean) {
    if (objectsVisible == visible) return
    objectsVisible = visible
    updateObjectToggleIcon()
    applyMarkersVisibility()
    persistToggleStatesToPrefs()
    try {
      bringToolbarToFrontSafe()
    } catch (_: Throwable) {}
    updateNotification(if (objectsVisible) "객체보기 ON" else "객체보기 OFF")
  }

  private fun updateObjectToggleIcon() {
    try {
      objectToggleBtn?.setImageResource(
        if (objectsVisible) R.drawable.cp_eye_open else R.drawable.cp_eye_off
      )
    } catch (_: Throwable) {}
  }

  private fun shouldShowMarkersNow(): Boolean {
    return objectsVisible && markersHiddenCount == 0
  }

  private fun toggleMacroRunning() {
    // (요청) 재생 버튼을 누를 때마다 캡처(화면저장) 권한 상태를 다시 확인하여
    // 이동 아이콘 색상을 즉시 반영(접근성 메뉴툴바/구형 플로팅 툴바 모두)
    try { AutoClickAccessibilityService.requestSyncMoveHandleTint() } catch (_: Throwable) {}
    try { syncMoveIconTintFromPrefs() } catch (_: Throwable) {}
    try {
      if (macroRunning) {
        requestStop("user_toggle_stop")
      } else {
        startMacroRunning()
      }
    } catch (t: Throwable) {
      try {
        updateNotification("재생 오류: ${t.javaClass.simpleName}")
      } catch (_: Throwable) {}
      try {
        requestStop("toggleMacroRunning_exception", t)
      } catch (_: Throwable) {}
    }
  }

  private fun startMacroRunning() {
    if (macroRunning) return
    if (!AutoClickAccessibilityService.isReady()) {
      updateNotification("접근성 서비스가 필요합니다")
      return
    }

    val t0 = android.os.SystemClock.uptimeMillis()
    dbg("startMacroRunning enter")

    // (중요) 실행 중에는 사용자가 밑에앱을 조작/터치할 수 있어야 한다.
    // passThroughEnabled 값과 무관하게, "터치 차단 레이어/모달 패널"이 남아있으면
    // 접근성 제스처가 그 오버레이에 맞아 밑에앱에 전달되지 않는 문제가 발생할 수 있어
    // 실행 시작 시점에 안전하게 전부 정리한다.
    passThroughEnabled = true
    pickOnceArmed = false
    try { removeTouchOverlay() } catch (_: Throwable) {}
    try { removeCoordInputOverlay() } catch (_: Throwable) {}
    try { removeColorPanel() } catch (_: Throwable) {}
    try { removeColorPickerOverlay() } catch (_: Throwable) {}
    try { removeColorModulePickerOverlay() } catch (_: Throwable) {}
    try { removeScreenSettingsOverlay() } catch (_: Throwable) {}
    try { removeMacroSaveOverlay() } catch (_: Throwable) {}
    try { removeMacroOpenOverlay() } catch (_: Throwable) {}
    try { refreshColorPickerUiIfOpen() } catch (_: Throwable) {}
    dbg("startMacroRunning overlay cleanup done")
    try {
      dbg(
        "startMacroRunning overlay check passThrough=$passThroughEnabled pickOnce=$pickOnceArmed touchTop=${touchTopRoot != null} touchBottom=${touchBottomRoot != null}"
      )
    } catch (_: Throwable) {}
    try {
      if (touchVizEnabled) ensureTouchVizOverlay() else removeTouchVizOverlay()
    } catch (_: Throwable) {}

    // (ATX2) 재생 시작마다 module 방향 런타임 상태(커서/랜덤 bag) 초기화
    try {
      synchronized(moduleDirLock) { moduleDirRuntime.clear() }
    } catch (_: Throwable) {}

    // 실행 시작 시 편집모드 ON이면 자동 OFF
    if (markerEditMode) {
      markerEditMode = false
      updateEditToggleIcon()
      for ((_, lp) in markerLps) applyMarkerEditFlags(lp)
      for ((idx, v) in markerViews) {
        val lp = markerLps[idx] ?: continue
        try { wm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
      }
      updateSwipeLinkLines()
      // (중요) 접근성 메뉴툴바는 prefs 기반으로 아이콘을 동기화하므로,
      // 여기서도 반드시 prefs에 OFF 상태를 저장해야 한다.
      persistToggleStatesToPrefs()
    }

    // (요청) 재생 시작 시 객체보기 ON이면 자동 OFF로 만든 뒤 재생한다.
    // 실행 중엔 화면을 가리거나 터치를 가로채면 안 되므로 기본 OFF 처리.
    try {
      if (objectsVisible) {
        setObjectsVisibleInternal(false)
      }
    } catch (_: Throwable) {}

    // 실행 직전 위치 동기화 + 최신 prefs 재로드
    // (중요) ensureMarkersLoadedAndShown() 내부의 refreshMarkerViews()가 "저장 좌표"로 재배치하므로
    // 동기화보다 먼저 호출하면 예전 좌표로 점프할 수 있다.
    if (markerViews.isEmpty()) {
      val tA = android.os.SystemClock.uptimeMillis()
      ensureMarkersLoadedAndShown()
      dbg("startMacroRunning ensureMarkersLoaded dt=${android.os.SystemClock.uptimeMillis() - tA}ms")
    }
    val tB = android.os.SystemClock.uptimeMillis()
    syncMarkerPositionsToPrefs()
    dbg("startMacroRunning syncMarkerPositions dt=${android.os.SystemClock.uptimeMillis() - tB}ms")

    // (중요) 시작 딜레이를 줄이기 위해, 실행 시작 시점에 prefs 재로드+refreshMarkerViews()를
    // 강제하지 않는다. 좌표는 위의 syncMarkerPositionsToPrefs()에서 markersCache에 반영되며,
    // 매크로 실행은 저장된 xPx/yPx만 사용한다.
    // 다만, Flutter/설정창 등에서 prefs가 변경되었는데 캐시가 갱신되지 않은 상태면
    // solo_main 같은 신규 타입이 실행 루프에서 "없는 것"으로 보일 수 있어,
    // 실행 직전에 prefs를 캐시에만 재로드한다(뷰 재배치 없음).
    try {
      reloadMarkersFromPrefsIntoCacheOnly()
      dbg("startMacroRunning markersCache reloaded for runner")
    } catch (_: Throwable) {}
    dbg("startMacroRunning prep done dt=${android.os.SystemClock.uptimeMillis() - t0}ms")

    pausedTotalMs = 0L
    pauseBeganAtMs = 0L
    macroRunning = true
    try { prefs().edit().putBoolean("flutter.macro_running", true).apply() } catch (_: Throwable) {}
    try { AutoClickAccessibilityService.requestRefreshToolbarLayout() } catch (_: Throwable) {}
    dbg("macro start")

    // 실행 중에는 마커 오버레이가 터치를 가로채면 안된다.
    // (사용자 터치 + 접근성 제스처 모두 밑에 앱으로 전달되어야 함)
    try {
      for ((idx, v) in markerViews) {
        val lp = markerLps[idx] ?: continue
        applyMarkerEditFlags(lp)
        try {
          wm?.updateViewLayout(v, lp)
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    // 통계 리셋 + 알림 갱신 루프 시작(실제 클릭/스와이프 성공 여부 확인용)
    try {
      AutoClickAccessibilityService.resetGestureStats()
    } catch (_: Throwable) {}
    updateNotification("실행 시작")
    updatePlayStopIcon()
    setToolbarButtonsLockedForRunning(true)
    try {
      gestureStatsHandler.removeCallbacks(gestureStatsRunnable)
      gestureStatsHandler.postDelayed(gestureStatsRunnable, 250L)
    } catch (_: Throwable) {}
    startOrderedLoop()
    startIndependentLoops()
    startSoloLoop()
  }

  private fun reloadMarkersFromPrefsIntoCacheOnly() {
    synchronized(markersLock) {
      val loaded = loadMarkersFromPrefs()
      ensureUniqueMarkerIndexes(loaded)
      // 저장까지 해두면(중복 index/0 정리 등) 이후 설정/표시가 안정적이다.
      saveMarkersToPrefs(loaded)
      markersCache = loaded
    }

    // 진단용: 시작 시 종류 개수 요약
    try {
      val snap = synchronized(markersLock) { markersCache.map { it.copy() } }
      val total = snap.size
      val byKind = snap.groupBy { it.kind }.mapValues { it.value.size }
      val soloM = snap.count { it.kind == "solo_main" || it.kind == "solo" }
      val soloI = snap.count { it.kind == "solo_item" }
      dbg("markersCache summary total=$total byKind=$byKind soloMain=$soloM soloItem=$soloI")
    } catch (_: Throwable) {}
  }

  private fun stopMacroRunning() {
    if (!macroRunning) return
    macroRunning = false
    try { prefs().edit().putBoolean("flutter.macro_running", false).apply() } catch (_: Throwable) {}
    try { AutoClickAccessibilityService.requestRefreshToolbarLayout() } catch (_: Throwable) {}
    dbg("macro stop")

    // 실행 종료 후 마커 오버레이 터치 가능 상태 복원(편집/설정 등)
    try {
      for ((idx, v) in markerViews) {
        val lp = markerLps[idx] ?: continue
        applyMarkerEditFlags(lp)
        try {
          wm?.updateViewLayout(v, lp)
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    try {
      gestureStatsHandler.removeCallbacks(gestureStatsRunnable)
    } catch (_: Throwable) {}
    synchronized(pauseLock) { pauseLock.notifyAll() }
    try {
      orderedThread?.interrupt()
    } catch (_: Throwable) {}
    try {
      soloThread?.interrupt()
    } catch (_: Throwable) {}
    try {
      independentSupervisorThread?.interrupt()
    } catch (_: Throwable) {}
    independentSupervisorThread = null
    for ((_, t) in independentThreads) {
      try {
        t.interrupt()
      } catch (_: Throwable) {}
    }
    independentThreads.clear()
    try {
      val st = AutoClickAccessibilityService.getGestureStats()
      val fails = st.cancelled + st.immediateFail
      updateNotification("실행 중지  OK:${st.completed}  FAIL:${fails}")
    } catch (_: Throwable) {
      updateNotification("실행 중지")
    }
    updatePlayStopIcon()
    setToolbarButtonsLockedForRunning(false)
    try {
      removeTouchVizOverlay()
    } catch (_: Throwable) {}
  }

  // ---------------- 실행 좌표 시각화(디버그용, 터치 비가로채기) ----------------
  private var touchVizView: TouchVizView? = null
  private var touchVizLp: WindowManager.LayoutParams? = null
  private var touchVizSizePx: Int = 0

  private class TouchVizView(ctx: Context) : View(ctx) {
    private val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 터치 표시 색상: 파란색(반투명)
        color = Color.argb(190, 0, 168, 255)
      }
    private val paint2 =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(230, 255, 255, 255)
      }

    @Volatile private var xPx: Float = -1f
    @Volatile private var yPx: Float = -1f
    @Volatile private var untilMs: Long = 0L

    fun showAt(x: Int, y: Int, durationMs: Long = 90L) {
      xPx = x.toFloat()
      yPx = y.toFloat()
      untilMs = android.os.SystemClock.uptimeMillis() + durationMs.coerceAtLeast(40L)
      postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
      val now = android.os.SystemClock.uptimeMillis()
      if (now > untilMs) return
      if (xPx < 0f || yPx < 0f) return
      val r = 18f
      // 이 뷰는 "작은 창"으로만 띄워서, 화면 전체를 가리지 않게 한다.
      // (일부 앱의 filterTouchesWhenObscured 대응)
      val cx = width / 2f
      val cy = height / 2f
      canvas.drawCircle(cx, cy, r, paint)
      canvas.drawCircle(cx, cy, r, paint2)
      // 다음 프레임까지 유지
      postInvalidateOnAnimation()
    }
  }

  private fun ensureTouchVizOverlay() {
    if (touchVizView != null) return
    val wmLocal = wm ?: return
    val v = TouchVizView(this)
    val size = dpToPx(54f).coerceAtLeast(36)
    touchVizSizePx = size
    val lp =
      WindowManager.LayoutParams(
        size,
        size,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      )
    lp.gravity = Gravity.TOP or Gravity.START
    try {
      wmLocal.addView(v, lp)
      touchVizView = v
      touchVizLp = lp
    } catch (_: Throwable) {}
  }

  private fun removeTouchVizOverlay() {
    val v = touchVizView ?: return
    touchVizView = null
    touchVizLp = null
    try {
      wm?.removeView(v)
    } catch (_: Throwable) {}
  }

  private fun showTouchVizAt(screenX: Int, screenY: Int) {
    if (!macroRunning) return
    if (!touchVizEnabled) return
    ensureTouchVizOverlay()
    try {
      val v = touchVizView ?: return
      val lp = touchVizLp ?: return
      val (w, h, _) = getScreenSize()
      val size = (touchVizSizePx.takeIf { it > 0 } ?: dpToPx(54f)).coerceAtLeast(36)
      val cx = screenX.coerceIn(0, (w - 1).coerceAtLeast(0))
      val cy = screenY.coerceIn(0, (h - 1).coerceAtLeast(0))
      lp.x = (cx - size / 2).coerceIn(-size, w)
      lp.y = (cy - size / 2).coerceIn(-size, h)
      try { wm?.updateViewLayout(v, lp) } catch (_: Throwable) {}
      v.showAt(cx, cy)
    } catch (_: Throwable) {}
  }

  private fun requestStop(reason: String, t: Throwable? = null) {
    dbg("requestStop reason=$reason", t)
    try {
      stopMacroRunning()
    } catch (_: Throwable) {}
  }

  private fun updatePlayStopIcon() {
    try {
      if (macroRunning) {
        playBtn?.setImageResource(android.R.drawable.ic_media_pause)
      } else {
        playBtn?.setImageResource(R.drawable.cp_play)
      }
    } catch (_: Throwable) {}
  }

  private fun setToolbarButtonsLockedForRunning(locked: Boolean) {
    fun apply(v: View?, enabled: Boolean) {
      if (v == null) return
      try {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1.0f else 0.25f
      } catch (_: Throwable) {}
    }

    val minimized = locked || toolbarMinimizedManual
    val minimizedChanged = (lastToolbarMinimizedApplied == null || lastToolbarMinimizedApplied != minimized)
    lastToolbarMinimizedApplied = minimized

    // (요청) 재생 중(locked) 또는 수동 최소화 상태에서는
    // 메뉴바 길이를 줄여 "중지(재생버튼)" + "이동"만 보이게 한다.
    fun setVisible(v: View?, visible: Boolean) {
      if (v == null) return
      try {
        v.visibility = if (visible) View.VISIBLE else View.GONE
      } catch (_: Throwable) {}
    }
    setVisible(playBtn, true)
    setVisible(moveBtn, true)
    setVisible(plusBtn, !minimized)
    setVisible(editToggleBtn, !minimized)
    setVisible(trashBtn, !minimized)
    setVisible(objectToggleBtn, !minimized)
    setVisible(settingsBtn, !minimized)
    setVisible(closeBtn, !minimized)

    // 실행 중엔 재생/정지 + 이동만 허용
    apply(plusBtn, !locked)
    apply(editToggleBtn, !locked)
    apply(trashBtn, !locked)
    apply(objectToggleBtn, !locked)
    apply(settingsBtn, !locked)
    apply(closeBtn, !locked)
    apply(moveBtn, true) // 재생 중/최소화에도 이동은 허용
    apply(playBtn, true)

    // visibility 변경 후 wrap_content 재측정 유도
    try {
      val root = toolbarRoot
      val lp = toolbarLp
      if (root != null && lp != null) {
        root.requestLayout()
        wm?.updateViewLayout(root, lp)
        // (중요) 최소화/복구 직후엔 measured size가 0/이전값인 타이밍이 있어서
        // post로 한 번 더 재측정하면 "재생 때처럼" 길이가 확실히 줄어든다.
        if (minimizedChanged) {
          root.post {
            try {
              root.requestLayout()
              wm?.updateViewLayout(root, lp)
            } catch (_: Throwable) {}
          }
        }
      }
    } catch (_: Throwable) {}
  }

  private data class StopCond(val mode: String, val timeSec: Int, val cycles: Int)

  private fun loadStopCond(): StopCond {
    val p = prefs()
    val mode = p.getString("flutter.stop_mode", "infinite") ?: "infinite"
    val timeSec = p.getInt("flutter.stop_time_sec", 0).coerceAtLeast(0)
    val cycles = p.getInt("flutter.stop_cycles", 1).coerceAtLeast(1)
    return StopCond(mode, timeSec, cycles)
  }

  private fun sleepPausable(ms: Long) {
    if (ms <= 0L) return
    var remain = ms
    while (macroRunning && remain > 0L) {
      synchronized(pauseLock) {
        while (macroRunning && pauseCount > 0) {
          try {
            pauseLock.wait(250L)
          } catch (_: InterruptedException) {
            return
          }
        }
      }
      if (!macroRunning) return
      val chunk = minOf(250L, remain)
      val start = System.currentTimeMillis()
      try {
        Thread.sleep(chunk)
      } catch (_: InterruptedException) {
        return
      }
      val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0L)
      remain -= elapsed
    }
  }

  // 랜덤지연(%) 규칙:
  // totalDelay = baseDelay + random(0 .. baseDelay * jitterPct/100)
  private fun delayWithJitterMs(delayMs: Int, jitterPct: Int): Long {
    val base = delayMs.coerceAtLeast(0).toLong()
    val pct = jitterPct.coerceIn(0, 100).toLong()
    val extraMax = ((base * pct) / 100L).coerceAtLeast(0L)
    val extra = if (extraMax <= 0L) 0L else Random.nextLong(extraMax + 1L)
    return (base + extra).coerceAtLeast(0L)
  }

  // (요청) 랜덤지연은 "AI탐지방어"가 체크된 마커에만 적용
  private fun aiDefenseEligibleForRandomDelay(m: Marker): Boolean {
    // 제외: 단독 메인/서브, 스와이프 sub(링), 색상모듈/이미지모듈
    when (m.kind) {
      "swipe_to" -> return false
      "solo_main", "solo", "solo_item" -> return false
      "color_module", "image_module" -> return false
    }
    return true
  }

  private fun jitterPctForMarkerDelay(m: Marker): Int {
    // AI탐지방어 체크된 경우에만 랜덤지연 적용(전역 %)
    val enabled = aiDefenseEligibleForRandomDelay(m) && (m.randomClickUse == true)
    return if (enabled) randomDelayPctGlobal.coerceIn(0, 100) else 0
  }

  private fun applyGlobalRandomDelayPctToAllMarkers(pct: Int) {
    val v = pct.coerceIn(0, 100)
    try {
      ensureMarkersLoadedAndShown()
      synchronized(markersLock) {
        for (m in markersCache) m.jitterPct = v
      }
      // 저장까지 수행(메크로 파일에도 동일 %가 들어가도록)
      saveMarkersToPrefs(markersCache, updateBase = true)
    } catch (_: Throwable) {}
  }

  // 클릭 누름(ms): 마커별 pressMs가 있으면 우선, 없으면 전역값 사용
  private fun pressMsForMarkerClick(m: Marker): Long {
    // (요청) 마커설정창에서 누름(ms)을 숨김 처리했으므로, 클릭/스와이프 누름은 전역값만 사용
    return clickPressMsGlobal.coerceIn(10, 500).toLong()
  }

  private fun clickOnceForMarker(m: Marker, x: Int, y: Int, delayMs: Long = 0L): Boolean {
    return AutoClickAccessibilityService.click(x, y, pressMs = pressMsForMarkerClick(m), delayMs = delayMs)
  }

  private fun clickMaybeDoubleForMarker(m: Marker, x: Int, y: Int, delayMs: Long = 0L): Boolean {
    val ok1 = clickOnceForMarker(m, x, y, delayMs = delayMs)
    if (!ok1) return false
    // 스와이프 계열은 제외(요구사항)
    if (m.kind == "swipe" || m.kind == "swipe_to") return true
    if (!m.doubleClick) return true
    // 더블클릭 간격(너무 길면 2번 클릭으로 인식 안 될 수 있어 짧게 유지)
    // (주의) 메인스레드에서 sleep을 하면 UI가 멈출 수 있어, 메인스레드에서는 postDelayed로 처리한다.
    val gapMs = 80L
    return if (Looper.myLooper() == Looper.getMainLooper()) {
      if (delayMs <= 0L) {
        try {
          Handler(Looper.getMainLooper()).postDelayed({ try { clickOnceForMarker(m, x, y, delayMs = 0L) } catch (_: Throwable) {} }, gapMs)
        } catch (_: Throwable) {}
        true
      } else {
        // 첫 클릭이 지연 스케줄인 경우, 두 번째 클릭은 pressMs+gap 뒤로 지연을 추가해 순서를 보장
        val press = pressMsForMarkerClick(m).coerceAtLeast(1L)
        clickOnceForMarker(m, x, y, delayMs = delayMs + press + gapMs)
      }
    } else {
      if (delayMs <= 0L) {
        sleepPausable(gapMs)
        clickOnceForMarker(m, x, y, delayMs = 0L)
      } else {
        val press = pressMsForMarkerClick(m).coerceAtLeast(1L)
        clickOnceForMarker(m, x, y, delayMs = delayMs + press + gapMs)
      }
    }
  }

  // (요청) 메뉴툴바의 "이동" 아이콘 색상:
  // - 화면저장(캡처) 권한이 있으면 빨간색
  // - 없으면 기본색
  private fun syncMoveIconTintFromPrefs() {
    try {
      val ready = prefs().getBoolean("flutter.capture_ready", false)
      val c = if (ready) Color.parseColor("#EF4444") else Color.parseColor("#111827")
      val b = moveBtn as? ImageButton
      if (b != null) {
        try {
          b.imageTintList = android.content.res.ColorStateList.valueOf(c)
        } catch (_: Throwable) {
          try {
            @Suppress("DEPRECATION")
            b.setColorFilter(c)
          } catch (_: Throwable) {}
        }
      }
    } catch (_: Throwable) {}
  }

  private fun markerScreenCenterPx(m: Marker): Pair<Int, Int> {
    // 오버레이 윈도우 중심(px) 우선
    val lp = markerLps[m.index]
    val v = markerViews[m.index]
    if (lp != null && v != null) {
      val w = (lp.width.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
      val h = (lp.height.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
      return Pair(lp.x + w / 2, lp.y + h / 2)
    }

    // 저장값(screen 기준 중심)
    val (w, h, _) = getScreenSize()
    val x = m.xPx.coerceIn(0, (w - 1).coerceAtLeast(0))
    val y = m.yPx.coerceIn(0, (h - 1).coerceAtLeast(0))
    return Pair(x, y)
  }

  private fun updateMoveIconTintFromCaptureReady() {
    try {
      val ready = prefs().getBoolean("flutter.capture_ready", false)
      val c = if (ready) Color.parseColor("#EF4444") else Color.parseColor("#111827")
      val b = (moveBtn as? ImageButton)
      if (b != null) {
        try {
          b.imageTintList = android.content.res.ColorStateList.valueOf(c)
        } catch (_: Throwable) {
          b.setColorFilter(c)
        }
      }
    } catch (_: Throwable) {}
  }

  private fun readArgbAtScreen(screenX: Int, screenY: Int): Int? {
    if (!captureReady) return null
    ensureCaptureConfiguredToScreen()
    val localInfo: FrameInfo
    val r: Int
    val g: Int
    val b: Int
    val a: Int

    val (w, h, _) = getScreenSize()
    val clampedScreenX = screenX.coerceIn(0, (w - 1).coerceAtLeast(0))
    val clampedScreenY = screenY.coerceIn(0, (h - 1).coerceAtLeast(0))

    synchronized(frameLock) {
      val info = frameInfo ?: return null
      localInfo = info
      val sx = if (w == 0) 0f else (clampedScreenX.toFloat() * localInfo.width.toFloat() / w.toFloat())
      val sy = if (h == 0) 0f else (clampedScreenY.toFloat() * localInfo.height.toFloat() / h.toFloat())
      val x = sx.roundToInt().coerceIn(0, localInfo.width - 1)
      val y = sy.roundToInt().coerceIn(0, localInfo.height - 1)
      val offset = y * localInfo.rowStride + x * localInfo.pixelStride
      if (offset + 3 >= frameBytes.size) return null
      r = frameBytes[offset].toInt() and 0xFF
      g = frameBytes[offset + 1].toInt() and 0xFF
      b = frameBytes[offset + 2].toInt() and 0xFF
      a = frameBytes[offset + 3].toInt() and 0xFF
    }

    return (a shl 24) or (r shl 16) or (g shl 8) or b
  }

  private fun markerScreenCenterPxFromStored(m: Marker, usable: Rect): Pair<Int, Int> {
    // 호환을 위해 시그니처는 유지하되, 좌표계는 screen 기준을 사용한다.
    val (w, h, _) = getScreenSize()
    val x = m.xPx.coerceIn(0, (w - 1).coerceAtLeast(0))
    val y = m.yPx.coerceIn(0, (h - 1).coerceAtLeast(0))
    return Pair(x, y)
  }

  private fun isColorMatch(mColor: Marker): Boolean {
    if (mColor.colorR < 0 || mColor.colorG < 0 || mColor.colorB < 0) return false
    // 실행 스레드에서 오버레이 맵(markerLps/markerViews)을 읽으면 동시성 문제로 중단될 수 있어
    // 저장된 px 기준으로만 계산한다.
    val usable = getUsableRectPx()
    val (x, y) = markerScreenCenterPxFromStored(mColor, usable)
    val argb = readArgbAtScreen(x, y) ?: return false
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return r == mColor.colorR && g == mColor.colorG && b == mColor.colorB
  }

  private fun loadTemplateImage(fileName: String): TemplateImage? {
    val name = fileName.trim()
    if (name.isBlank()) return null
    val f = File(atxImgDir(), name)
    if (!f.exists() || !f.isFile) return null
    val lm = try { f.lastModified() } catch (_: Throwable) { 0L }
    synchronized(templateCacheLock) {
      val cached = templateCache[name]
      if (cached != null && cached.lastModified == lm) return cached
    }
    // (메모리) decodeFile()로 원본을 그대로 올리면(특히 고해상도 PNG) 피크 메모리가 크게 튈 수 있어
    // 먼저 bounds를 읽고 inSampleSize로 1차 다운스케일 decode 한다.
    val maxDim = 512
    fun calcSampleSize(w: Int, h: Int, maxDim: Int): Int {
      var s = 1
      var ww = w.coerceAtLeast(1)
      var hh = h.coerceAtLeast(1)
      while ((ww / s) > maxDim || (hh / s) > maxDim) {
        s *= 2
      }
      return s.coerceAtLeast(1)
    }
    val bounds =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = true
      }
    try {
      BitmapFactory.decodeFile(f.absolutePath, bounds)
    } catch (_: Throwable) {}
    val wB = bounds.outWidth
    val hB = bounds.outHeight
    if (wB <= 0 || hB <= 0) return null
    val sample = calcSampleSize(wB, hB, maxDim)
    val opts =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sample
        try {
          inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
          inDither = false
        } catch (_: Throwable) {}
        try { inScaled = false } catch (_: Throwable) {}
      }
    val bmp0 =
      try {
        BitmapFactory.decodeFile(f.absolutePath, opts)
      } catch (_: Throwable) {
        null
      } ?: return null
    // (메모리) inSampleSize가 거칠게 떨어져 maxDim을 약간 초과할 수 있어, 마지막으로 한 번만 스케일 보정
    val bmp =
      try {
        val w0 = bmp0.width.coerceAtLeast(1)
        val h0 = bmp0.height.coerceAtLeast(1)
        if (w0 <= maxDim && h0 <= maxDim) {
          bmp0
        } else {
          val s = maxOf(w0.toFloat() / maxDim.toFloat(), h0.toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
          val nw = (w0.toFloat() / s).roundToInt().coerceAtLeast(1)
          val nh = (h0.toFloat() / s).roundToInt().coerceAtLeast(1)
          val scaled = Bitmap.createScaledBitmap(bmp0, nw, nh, true)
          try { bmp0.recycle() } catch (_: Throwable) {}
          scaled
        }
      } catch (_: Throwable) {
        try { bmp0.recycle() } catch (_: Throwable) {}
        return null
      }
    val w = bmp.width.coerceAtLeast(1)
    val h = bmp.height.coerceAtLeast(1)
    // (메모리) IntArray(w*h)를 만들지 않고, 1줄씩 읽어 gray로 변환(피크 메모리 감소)
    val gray = ByteArray(w * h)
    val row = IntArray(w)
    try {
      for (yy in 0 until h) {
        bmp.getPixels(row, 0, w, 0, yy, w, 1)
        val base = yy * w
        for (xx in 0 until w) {
          val c = row[xx]
          val r = (c shr 16) and 0xFF
          val g = (c shr 8) and 0xFF
          val b = (c) and 0xFF
          val y = (r * 30 + g * 59 + b * 11) / 100
          gray[base + xx] = y.coerceIn(0, 255).toByte()
        }
      }
    } catch (_: Throwable) {
      return null
    } finally {
      try { bmp.recycle() } catch (_: Throwable) {}
    }
    val t = TemplateImage(w = w, h = h, gray = gray, lastModified = lm)
    synchronized(templateCacheLock) {
      val prev = templateCache.put(name, t)
      if (prev != null) templateCacheBytes -= prev.gray.size.toLong().coerceAtLeast(0L)
      templateCacheBytes += t.gray.size.toLong()
      // 캐시가 과도하게 커지지 않게 상한(개수+총 바이트)
      while (templateCache.size > 32 || templateCacheBytes > templateCacheMaxBytes) {
        val it = templateCache.entries.iterator()
        if (!it.hasNext()) break
        val e = it.next()
        templateCacheBytes -= e.value.gray.size.toLong().coerceAtLeast(0L)
        it.remove()
      }
    }
    return t
  }

  private fun verifyTemplateAtCenter(tpl: TemplateImage, centerScreenX: Int, centerScreenY: Int): Float {
    if (!captureReady) return 0f
    val screen = getScreenSize()
    val sw = screen.width.coerceAtLeast(1)
    val sh = screen.height.coerceAtLeast(1)
    val w = tpl.w.coerceAtLeast(1)
    val h = tpl.h.coerceAtLeast(1)
    val leftS = (centerScreenX - w / 2).coerceIn(0, (sw - w).coerceAtLeast(0))
    val topS = (centerScreenY - h / 2).coerceIn(0, (sh - h).coerceAtLeast(0))
    val grid = 64

    synchronized(frameLock) {
      val info = frameInfo ?: return 0f
      val bytes = frameBytes
      if (bytes.isEmpty()) return 0f
      val fw = info.width.coerceAtLeast(1)
      val fh = info.height.coerceAtLeast(1)
      val rowStride = info.rowStride
      val pixelStride = info.pixelStride
      var sum = 0L
      var cnt = 0
      for (gy in 0 until grid) {
        val ty = (gy * (h - 1)) / (grid - 1).coerceAtLeast(1)
        val sy = topS + (gy * (h - 1)) / (grid - 1).coerceAtLeast(1)
        val fy = ((sy.toFloat() * fh.toFloat() / sh.toFloat()).roundToInt()).coerceIn(0, fh - 1)
        val base = fy * rowStride
        for (gx in 0 until grid) {
          val tx = (gx * (w - 1)) / (grid - 1).coerceAtLeast(1)
          val sx = leftS + (gx * (w - 1)) / (grid - 1).coerceAtLeast(1)
          val fx = ((sx.toFloat() * fw.toFloat() / sw.toFloat()).roundToInt()).coerceIn(0, fw - 1)
          val off = base + fx * pixelStride
          if (off + 2 >= bytes.size) continue
          val r = bytes[off].toInt() and 0xFF
          val g = bytes[off + 1].toInt() and 0xFF
          val b = bytes[off + 2].toInt() and 0xFF
          val y = (r * 30 + g * 59 + b * 11) / 100
          val tGray = tpl.gray[ty * w + tx].toInt() and 0xFF
          sum += kotlin.math.abs(y - tGray).toLong()
          cnt++
        }
      }
      if (cnt <= 0) return 0f
      val mad = (sum.toFloat() / cnt.toFloat()).coerceIn(0f, 255f)
      return (1f - (mad / 255f)).coerceIn(0f, 1f)
    }
  }

  private fun matchTemplateNear(
    tpl: TemplateImage,
    centerScreenX: Int,
    centerScreenY: Int,
    searchRadiusPx: Int,
    minScore: Float,
  ): Pair<Int, Int>? {
    if (!captureReady) return null
    val screen = getScreenSize()
    val (sw, sh) = screen.width to screen.height
    val w = tpl.w
    val h = tpl.h
    if (w <= 0 || h <= 0) return null
    val radius = searchRadiusPx.coerceAtLeast(0)
    val step = maxOf(4, minOf(w, h) / 16)
    val grid = 16

    fun scoreAt(leftS: Int, topS: Int, info: FrameInfo, bytes: ByteArray): Float {
      val fw = info.width.coerceAtLeast(1)
      val fh = info.height.coerceAtLeast(1)
      val rowStride = info.rowStride
      val pixelStride = info.pixelStride
      var sum = 0L
      var cnt = 0
      for (gy in 0 until grid) {
        val ty = (gy * (h - 1)) / (grid - 1).coerceAtLeast(1)
        val sy = topS + (gy * (h - 1)) / (grid - 1).coerceAtLeast(1)
        val syC = sy.coerceIn(0, (sh - 1).coerceAtLeast(0))
        val fy = ((syC.toFloat() * fh.toFloat() / sh.toFloat()).roundToInt()).coerceIn(0, fh - 1)
        val base = fy * rowStride
        for (gx in 0 until grid) {
          val tx = (gx * (w - 1)) / (grid - 1).coerceAtLeast(1)
          val sx = leftS + (gx * (w - 1)) / (grid - 1).coerceAtLeast(1)
          val sxC = sx.coerceIn(0, (sw - 1).coerceAtLeast(0))
          val fx = ((sxC.toFloat() * fw.toFloat() / sw.toFloat()).roundToInt()).coerceIn(0, fw - 1)
          val off = base + fx * pixelStride
          if (off + 2 >= bytes.size) continue
          val r = bytes[off].toInt() and 0xFF
          val g = bytes[off + 1].toInt() and 0xFF
          val b = bytes[off + 2].toInt() and 0xFF
          val y = (r * 30 + g * 59 + b * 11) / 100
          val tGray = tpl.gray[ty * w + tx].toInt() and 0xFF
          sum += kotlin.math.abs(y - tGray).toLong()
          cnt++
        }
      }
      if (cnt <= 0) return 0f
      val mad = (sum.toFloat() / cnt.toFloat()).coerceIn(0f, 255f)
      return (1f - (mad / 255f)).coerceIn(0f, 1f)
    }

    synchronized(frameLock) {
      val info = frameInfo ?: return null
      val bytes = frameBytes
      if (bytes.isEmpty()) return null

      var bestScore = -1f
      var bestCx = centerScreenX
      var bestCy = centerScreenY

      for (dy in -radius..radius step step) {
        for (dx in -radius..radius step step) {
          val cx = (centerScreenX + dx).coerceIn(0, (sw - 1).coerceAtLeast(0))
          val cy = (centerScreenY + dy).coerceIn(0, (sh - 1).coerceAtLeast(0))
          val left = (cx - w / 2).coerceIn(0, (sw - w).coerceAtLeast(0))
          val top = (cy - h / 2).coerceIn(0, (sh - h).coerceAtLeast(0))
          val s = scoreAt(left, top, info, bytes)
          if (s > bestScore) {
            bestScore = s
            bestCx = cx
            bestCy = cy
          }
        }
      }

      return if (bestScore >= minScore) Pair(bestCx, bestCy) else null
    }
  }

  private data class TemplateMatchDebug(val cx: Int, val cy: Int, val score: Float)

  private fun findBestTemplateInRegion(
    tpl: TemplateImage,
    leftS: Int,
    topS: Int,
    rightS: Int,
    bottomS: Int,
  ): TemplateMatchDebug? {
    if (!captureReady) return null
    val screen = getScreenSize()
    val sw = screen.width.coerceAtLeast(1)
    val sh = screen.height.coerceAtLeast(1)
    val w = tpl.w.coerceAtLeast(1)
    val h = tpl.h.coerceAtLeast(1)
    val l0 = leftS.coerceIn(0, sw - 1)
    val t0 = topS.coerceIn(0, sh - 1)
    val r0 = rightS.coerceIn(l0 + 1, sw)
    val b0 = bottomS.coerceIn(t0 + 1, sh)

    val l = l0.coerceIn(0, (sw - w).coerceAtLeast(0))
    val t = t0.coerceIn(0, (sh - h).coerceAtLeast(0))
    val r = r0.coerceIn(l + 1, sw)
    val b = b0.coerceIn(t + 1, sh)

    val step = maxOf(4, minOf(w, h) / 16)
    val grid = 16

    fun scoreAt(leftS2: Int, topS2: Int, info: FrameInfo, bytes: ByteArray): Float {
      val fw = info.width.coerceAtLeast(1)
      val fh = info.height.coerceAtLeast(1)
      val rowStride = info.rowStride
      val pixelStride = info.pixelStride
      var sum = 0L
      var cnt = 0
      for (gy in 0 until grid) {
        val ty = (gy * (h - 1)) / (grid - 1).coerceAtLeast(1)
        val sy = topS2 + (gy * (h - 1)) / (grid - 1).coerceAtLeast(1)
        val syC = sy.coerceIn(0, sh - 1)
        val fy = ((syC.toFloat() * fh.toFloat() / sh.toFloat()).roundToInt()).coerceIn(0, fh - 1)
        val base = fy * rowStride
        for (gx in 0 until grid) {
          val tx = (gx * (w - 1)) / (grid - 1).coerceAtLeast(1)
          val sx = leftS2 + (gx * (w - 1)) / (grid - 1).coerceAtLeast(1)
          val sxC = sx.coerceIn(0, sw - 1)
          val fx = ((sxC.toFloat() * fw.toFloat() / sw.toFloat()).roundToInt()).coerceIn(0, fw - 1)
          val off = base + fx * pixelStride
          if (off + 2 >= bytes.size) continue
          val r = bytes[off].toInt() and 0xFF
          val g = bytes[off + 1].toInt() and 0xFF
          val b = bytes[off + 2].toInt() and 0xFF
          val y = (r * 30 + g * 59 + b * 11) / 100
          val tGray = tpl.gray[ty * w + tx].toInt() and 0xFF
          sum += kotlin.math.abs(y - tGray).toLong()
          cnt++
        }
      }
      if (cnt <= 0) return 0f
      val mad = (sum.toFloat() / cnt.toFloat()).coerceIn(0f, 255f)
      return (1f - (mad / 255f)).coerceIn(0f, 1f)
    }

    fun scoreAtDense(leftS2: Int, topS2: Int, info: FrameInfo, bytes: ByteArray): Float {
      // 2차 검증용(오탐 방지): 더 촘촘한 샘플링
      val fw = info.width.coerceAtLeast(1)
      val fh = info.height.coerceAtLeast(1)
      val rowStride = info.rowStride
      val pixelStride = info.pixelStride
      val grid2 = 32
      var sum = 0L
      var cnt = 0
      for (gy in 0 until grid2) {
        val ty = (gy * (h - 1)) / (grid2 - 1).coerceAtLeast(1)
        val sy = topS2 + (gy * (h - 1)) / (grid2 - 1).coerceAtLeast(1)
        val syC = sy.coerceIn(0, sh - 1)
        val fy = ((syC.toFloat() * fh.toFloat() / sh.toFloat()).roundToInt()).coerceIn(0, fh - 1)
        val base = fy * rowStride
        for (gx in 0 until grid2) {
          val tx = (gx * (w - 1)) / (grid2 - 1).coerceAtLeast(1)
          val sx = leftS2 + (gx * (w - 1)) / (grid2 - 1).coerceAtLeast(1)
          val sxC = sx.coerceIn(0, sw - 1)
          val fx = ((sxC.toFloat() * fw.toFloat() / sw.toFloat()).roundToInt()).coerceIn(0, fw - 1)
          val off = base + fx * pixelStride
          if (off + 2 >= bytes.size) continue
          val r = bytes[off].toInt() and 0xFF
          val g = bytes[off + 1].toInt() and 0xFF
          val b = bytes[off + 2].toInt() and 0xFF
          val y = (r * 30 + g * 59 + b * 11) / 100
          val tGray = tpl.gray[ty * w + tx].toInt() and 0xFF
          sum += kotlin.math.abs(y - tGray).toLong()
          cnt++
        }
      }
      if (cnt <= 0) return 0f
      val mad = (sum.toFloat() / cnt.toFloat()).coerceIn(0f, 255f)
      return (1f - (mad / 255f)).coerceIn(0f, 1f)
    }

    synchronized(frameLock) {
      val info = frameInfo ?: return null
      val bytes = frameBytes
      if (bytes.isEmpty()) return null

      var bestScore = -1f
      var bestCx = l + w / 2
      var bestCy = t + h / 2

      var y = t
      while (y <= (b - h).coerceAtLeast(t)) {
        var x = l
        while (x <= (r - w).coerceAtLeast(l)) {
          val s = scoreAt(x, y, info, bytes)
          if (s > bestScore) {
            bestScore = s
            bestCx = x + w / 2
            bestCy = y + h / 2
          }
          x += step
        }
        y += step
      }

      if (bestScore < 0f) return null

      // 2차 검증/미세보정: best 주변을 작은 스텝으로 재탐색(오탐 줄이고 좌표 정확도 개선)
      val refineR = step.coerceIn(6, 24)
      var refineBestScore = -1f
      var refineBestCx = bestCx
      var refineBestCy = bestCy

      val startLeft = (bestCx - w / 2).coerceIn(l, (r - w).coerceAtLeast(l))
      val startTop = (bestCy - h / 2).coerceIn(t, (b - h).coerceAtLeast(t))

      val leftMin = (startLeft - refineR).coerceIn(l, (r - w).coerceAtLeast(l))
      val leftMax = (startLeft + refineR).coerceIn(l, (r - w).coerceAtLeast(l))
      val topMin = (startTop - refineR).coerceIn(t, (b - h).coerceAtLeast(t))
      val topMax = (startTop + refineR).coerceIn(t, (b - h).coerceAtLeast(t))

      var yy = topMin
      while (yy <= topMax) {
        var xx = leftMin
        while (xx <= leftMax) {
          val s2 = scoreAtDense(xx, yy, info, bytes)
          if (s2 > refineBestScore) {
            refineBestScore = s2
            refineBestCx = xx + w / 2
            refineBestCy = yy + h / 2
          }
          xx += 2
        }
        yy += 2
      }

      // refine가 실패하면 coarse를 사용(안정성)
      val outCx = if (refineBestScore >= 0f) refineBestCx else bestCx
      val outCy = if (refineBestScore >= 0f) refineBestCy else bestCy
      val outScore = if (refineBestScore >= 0f) refineBestScore else bestScore

      return TemplateMatchDebug(outCx, outCy, outScore)
    }
  }

  private fun blinkMarker(index: Int) {
    // (중요) 실행 스레드에서 markerViews를 직접 읽으면 동시성 문제로 중단될 수 있어
    // lookup + UI 변경은 모두 메인 스레드에서 처리한다.
    val blink = if (index > 0) Color.parseColor("#EF4444") else Color.parseColor("#22C55E")
    Handler(Looper.getMainLooper()).post {
      try {
        val v = markerViews[index] as? MarkerBubbleView ?: return@post
        v.blinkColor = blink
        Handler(Looper.getMainLooper()).postDelayed({ try { v.blinkColor = null } catch (_: Throwable) {} }, 160L)
      } catch (_: Throwable) {}
    }
  }

  private fun fireMarkerOnce(m: Marker, snapshot: List<Marker> = markersSnapshot()): Boolean {
    // 링은 실행 대상이 아님
    if (m.kind == "color" || m.kind == "swipe_to") return true

    // (요청) AI탐지방어(랜덤 실행):
    // - 화면설정의 실행확률%를 기준으로, 체크된 마커만 랜덤하게 "실행/스킵"한다.
    // - 제외: 단독 메인/서브, 스와이프 sub(링), 색상모듈
    val aiEligible =
      m.kind != "color_module" &&
        m.kind != "solo_main" &&
        m.kind != "solo_item" &&
        m.kind != "swipe_to" &&
        m.kind != "color"
    if (aiEligible && m.randomClickUse) {
      val p = execProbabilityPercent.coerceIn(0, 100)
      if (p < 100) {
        val roll = Random.nextInt(100)
        if (roll >= p) return true
      }
    }

    // (디버깅) 실행(시도)하는 마커 정보 스트리밍
    try { adbStreamMarkerMeta(m, phase = "try") } catch (_: Throwable) {}

    // 7) 이미지모듈: 템플릿 매칭 후, 만족하면 클릭
    if (m.kind == "image_module") {
      if (!captureReady) return true
      val tpl = loadTemplateImage(m.imageTemplateFile) ?: return true
      val usable = getUsableRectPx()
      val wU = usable.width().coerceAtLeast(1)
      val hU = usable.height().coerceAtLeast(1)
      val sxU0 =
        (if (m.imageStartXPx >= 0) m.imageStartXPx else (m.xPx - usable.left))
          .coerceIn(0, (wU - 1).coerceAtLeast(0))
      val syU0 =
        (if (m.imageStartYPx >= 0) m.imageStartYPx else (m.yPx - usable.top))
          .coerceIn(0, (hU - 1).coerceAtLeast(0))
      val exU0 =
        // (요청) 이미지모듈 검색영역 기본값: 200x200 -> 500x500
        (if (m.imageEndXPx >= 0) m.imageEndXPx else (sxU0 + 500))
          .coerceIn(0, (wU - 1).coerceAtLeast(0))
      val eyU0 =
        // (요청) 이미지모듈 검색영역 기본값: 200x200 -> 500x500
        (if (m.imageEndYPx >= 0) m.imageEndYPx else (syU0 + 500))
          .coerceIn(0, (hU - 1).coerceAtLeast(0))
      val sxU = minOf(sxU0, exU0)
      val syU = minOf(syU0, eyU0)
      val exU = maxOf(sxU0, exU0)
      val eyU = maxOf(syU0, eyU0)
      val leftS = (usable.left + sxU).coerceIn(usable.left, usable.right - 1)
      val topS = (usable.top + syU).coerceIn(usable.top, usable.bottom - 1)
      val rightS = (usable.left + exU).coerceIn(usable.left + 1, usable.right)
      val bottomS = (usable.top + eyU).coerceIn(usable.top + 1, usable.bottom)
      val acc = m.imageAccuracyPct.coerceIn(50, 100)
      // (주의) 현재 매칭 점수는 단순 MAD 기반(샘플링)이라 0.90 같은 높은 기준은 쉽게 실패할 수 있다.
      // 사용자는 50~100을 조절하지만, 내부 minScore는 과도하게 높아지지 않게 매핑한다(디버그 표시로 원인 확인 가능).
      val minScore = (0.55f + (acc - 50).coerceIn(0, 50) / 50f * 0.30f).coerceIn(0.55f, 0.85f)
      val best = findBestTemplateInRegion(tpl, leftS, topS, rightS, bottomS) ?: run {
        try { updateImageMatchDebugInPrefs(m.index, xU = -1, yU = -1, score = -1f, minScore = minScore) } catch (_: Throwable) {}
        return true
      }
      val okMatch = best.score >= minScore

      // (요청) 검색 성공 시 "찾은 이미지 중앙좌표(usable px)"를 prefs에 저장해서 설정창에서 확인 가능하게 한다.
      val hitCenterXU = (best.cx - usable.left).coerceIn(0, (wU - 1).coerceAtLeast(0))
      val hitCenterYU = (best.cy - usable.top).coerceIn(0, (hU - 1).coerceAtLeast(0))
      try { updateImageFoundCenterInPrefs(m.index, hitCenterXU, hitCenterYU) } catch (_: Throwable) {}
      try { updateImageMatchDebugInPrefs(m.index, hitCenterXU, hitCenterYU, best.score, minScore) } catch (_: Throwable) {}
      if (!okMatch) return true

      // (요청) 이미지 3차 검증 -> 10차 검증으로 확장
      // - 단발성 false-negative 때문에 "이미지 있는데 통과"가 나지 않게, 다수결(과반)로 판정한다.
      // - 첫 2회는 40ms 간격, 이후는 설정된 인터벌을 적용한다.
      val verifyCount = 10
      val scores = FloatArray(verifyCount)
      val iv = imageVerifyThirdIntervalMs.coerceIn(0, 1000).toLong()
      for (k in 0 until verifyCount) {
        if (k == 1) {
          sleepPausable(40L)
        } else if (k >= 2) {
          if (iv > 0L) sleepPausable(iv)
        }
        scores[k] = verifyTemplateAtCenter(tpl, best.cx, best.cy)
      }
      val okVotes = scores.count { it >= minScore }
      // 과반(>=6)만 만족하면 "진짜로 있다"로 본다.
      if (okVotes < (verifyCount / 2 + 1)) return true

      // 조건 만족 -> 클릭 실행(마커 위치 클릭)
      blinkMarker(m.index)
      dbg("fire kind=image_module idx=${m.index} tpl=${m.imageTemplateFile} acc=$acc score=${best.score} min=$minScore hit=(${best.cx},${best.cy})")
      val (mx, my) = try { markerScreenCenterPx(m) } catch (_: Throwable) { markerScreenCenterPxFromStored(m, usable) }
      val clickMode = m.imageClickMode
      val clickModeName =
        when (clickMode) {
          1 -> "template_center"
          2 -> "ringtone"
          3 -> "vibrate"
          else -> "marker_center"
        }
      val (x, y) =
        if (clickMode == 1) {
          Pair(best.cx, best.cy) // screen px
        } else {
          Pair(mx, my) // screen px
        }
      // (디버깅) 클릭 좌표/모드/영역/차이값을 반드시 기록해서 "창 닫힘" 같은 오동작 원인을 추적한다.
      run {
        val screen = getScreenSize()
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        val pkg = try { AutoClickAccessibilityService.getLastEventPackage() } catch (_: Throwable) { null }
        val activePkg = try { AutoClickAccessibilityService.getLastActiveWindowPackage() } catch (_: Throwable) { null }
        val dx = x - best.cx
        val dy = y - best.cy
        dbg(
          "image_module preClick idx=${m.index} mode=$clickMode($clickModeName) chosen=($x,$y) " +
            "markerCenter=($mx,$my) tplCenter=(${best.cx},${best.cy}) d=($dx,$dy) " +
            "searchRect=[$leftS,$topS,$rightS,$bottomS] usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}] " +
            "screen=${screen.width}x${screen.height} okVotes=$okVotes/$verifyCount score=${"%.3f".format(best.score)} min=${"%.3f".format(minScore)} " +
            "stats=D:${st?.dispatched} OK:${st?.completed} C:${st?.cancelled} IF:${st?.immediateFail} T:${st?.timeout} reason=${st?.lastFailReason} pkg=$pkg activePkg=$activePkg"
        )
      }
      val ok =
        withGlobalPause {
          if (clickMode == 2) {
            // (요청) 3) 소리내기: 클릭 대신 현재 설정된 벨소리를 재생
            try { adbStream("ACT cat=7 kind=image_module idx=${m.index} ringtone() tpl=${m.imageTemplateFile} mode=$clickModeName") } catch (_: Throwable) {}
            try { playCurrentRingtoneOnce(1400L) } catch (_: Throwable) {}
            true
          } else if (clickMode == 3) {
            // (요청) 4) 진동하기: 클릭 대신 짧게 1회 진동
            try { adbStream("ACT cat=7 kind=image_module idx=${m.index} vibrate() tpl=${m.imageTemplateFile} mode=$clickModeName") } catch (_: Throwable) {}
            try { vibrateOnce(520L) } catch (_: Throwable) {}
            true
          } else {
            try { adbStream("ACT cat=7 kind=image_module idx=${m.index} tap($x,$y) tpl=${m.imageTemplateFile} mode=$clickModeName") } catch (_: Throwable) {}
            clickMaybeDoubleForMarker(m, x, y)
          }
        }
      if (ok) {
        // (요청) 실제 클릭 성공한 경우에만 로그 기록
        try {
          appendExecLog(
            7,
            (
              if (clickMode == 2) "image_module idx=${m.index} ringtone() tpl=${m.imageTemplateFile} score=${"%.3f".format(best.score)} "
              else if (clickMode == 3) "image_module idx=${m.index} vibrate() tpl=${m.imageTemplateFile} score=${"%.3f".format(best.score)} "
              else "image_module idx=${m.index} tap($x,$y) tpl=${m.imageTemplateFile} score=${"%.3f".format(best.score)} "
            ) +
              "v10=[${scores.joinToString(",") { "%.3f".format(it) }}] ok=$okVotes/$verifyCount min=${"%.3f".format(minScore)}"
          )
        } catch (_: Throwable) {}
        if (clickMode != 2 && clickMode != 3) showTouchVizAt(x, y)

        // (디버깅) 클릭 직후 이미지가 "여전히 있다"로 나오면 좌표/타겟이 잘못됐을 가능성이 높다.
        // - 프레임 타이밍 이슈를 줄이기 위해 짧게 재검 후 maxScore로 판정한다.
        // - (요청) 5회 이하면 최소 5회는 검증한다.
        // (소리/진동) 모드에서는 클릭이 없으므로 post-verify는 생략
        if (clickMode != 2 && clickMode != 3) try {
          val postVerifyCount = 5
          val postScores = FloatArray(postVerifyCount)
          for (k in 0 until postVerifyCount) {
            if (k == 0) {
              sleepPausable(120L)
            } else {
              sleepPausable(40L)
            }
            postScores[k] = verifyTemplateAtCenter(tpl, best.cx, best.cy)
          }
          var postMax = -1f
          for (s in postScores) postMax = maxOf(postMax, s)
          val stillFound = postMax >= minScore
          if (stillFound) {
            dbg(
              "image_module WARN stillFoundAfterClick idx=${m.index} chosen=($x,$y) mode=$clickMode($clickModeName) " +
                "post5=[${postScores.joinToString(",") { "%.3f".format(it) }}] max=${"%.3f".format(postMax)} min=${"%.3f".format(minScore)}"
            )
            try {
              appendExecLog(
                7,
                "image_module WARN stillFoundAfterClick idx=${m.index} chosen=($x,$y) mode=$clickModeName " +
                  "postMax=${"%.3f".format(postMax)} min=${"%.3f".format(minScore)}"
              )
            } catch (_: Throwable) {}
          } else {
            dbg(
              "image_module postVerifyGone idx=${m.index} chosen=($x,$y) mode=$clickMode($clickModeName) " +
                "postMax=${"%.3f".format(postMax)} min=${"%.3f".format(minScore)}"
            )
          }
        } catch (_: Throwable) {}
      }
      return ok
    }

    // 색상모듈(color_module)은 "조건 만족 시에만" 클릭해야 하므로,
    // 조건 확인 전에는 blink/log를 하지 않는다.
    if (m.kind != "color_module") {
      blinkMarker(m.index)
      // 너무 로그가 커지지 않게 최소 정보만
      dbg("fire kind=${m.kind} idx=${m.index} xPx=${m.xPx} yPx=${m.yPx} to=${m.toIndex} swipeMode=${m.swipeMode}")
    }

    // 6) 색상모듈: 지정 좌표에서 색상 비교 후, 만족하면 클릭
    if (m.kind == "color_module") {
      val usable = getUsableRectPx()
      val wU = usable.width().coerceAtLeast(1)
      val hU = usable.height().coerceAtLeast(1)
      val cxU =
        (if (m.colorCheckXPx >= 0) m.colorCheckXPx else (m.xPx - usable.left))
          .coerceIn(0, (wU - 1).coerceAtLeast(0))
      val cyU =
        (if (m.colorCheckYPx >= 0) m.colorCheckYPx else (m.yPx - usable.top))
          .coerceIn(0, (hU - 1).coerceAtLeast(0))
      val screenX = (usable.left + cxU).coerceIn(usable.left, usable.right - 1)
      val screenY = (usable.top + cyU).coerceIn(usable.top, usable.bottom - 1)

      val wantR = m.colorR
      val wantG = m.colorG
      val wantB = m.colorB
      // 색상값이 없으면 아무것도 안 함(안전)
      if (wantR !in 0..255 || wantG !in 0..255 || wantB !in 0..255) return true

      val argb = readArgbAtScreen(screenX, screenY) ?: return true
      val r = (argb shr 16) and 0xFF
      val g = (argb shr 8) and 0xFF
      val b = argb and 0xFF

      val acc = m.colorAccuracyPct.coerceIn(50, 100)
      val tol = (((100 - acc) / 100f) * 255f).roundToInt().coerceIn(0, 255)
      val okColor =
        kotlin.math.abs(r - wantR) <= tol &&
          kotlin.math.abs(g - wantG) <= tol &&
          kotlin.math.abs(b - wantB) <= tol

      if (!okColor) return true

      // 조건 만족 -> 클릭 실행
      blinkMarker(m.index)
      dbg(
        "fire kind=color_module idx=${m.index} check=($screenX,$screenY) rgb=($r,$g,$b) " +
          "want=($wantR,$wantG,$wantB) acc=$acc tol=$tol"
      )
      // (요청) 색상 체크는 check좌표로 하고, 실제 클릭은 "마커(객체) 위치"를 클릭한다.
      // (중요) "표시 위치"와 "실제 클릭 위치"가 어긋나지 않도록,
      // 클릭 좌표는 저장값이 아니라 "현재 오버레이 윈도우 중심(실제 표시)"을 우선 사용한다.
      val (x, y) =
        try {
          markerScreenCenterPx(m)
        } catch (_: Throwable) {
          markerScreenCenterPxFromStored(m, usable)
        }
      // (요청) 색상모듈은 "단독 실행"처럼 동작:
      // 조건 만족으로 실제 클릭을 수행하는 순간에는 다른 모든 마커를 pause 시키고,
      // 클릭이 끝나면 다시 재개한다.
      val ok =
        withGlobalPause {
          clickMaybeDoubleForMarker(m, x, y)
        }
      if (!ok) {
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        dbg("gesture click returned false, lastReason=${st?.lastFailReason}")
      } else {
        // (요청) 실제 클릭 성공한 경우에만 로그 기록
        try {
          appendExecLog(
            6,
            "color_module idx=${m.index} tap($x,$y) check=($screenX,$screenY) rgb=($r,$g,$b) want=($wantR,$wantG,$wantB) tol=$tol"
          )
        } catch (_: Throwable) {}
        // (중요) 클릭 위치 알림(파란 점)은 "클릭 이후"에만 표시해야
        // 타겟 앱이 overlay로 인해 터치를 무시(filterTouchesWhenObscured)하는 문제를 피할 수 있다.
        showTouchVizAt(x, y)
        // 성공인데도 실제 반응이 없는 케이스 진단용(좌표/타겟앱 차단/오버레이 등)
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        val pkg = try { AutoClickAccessibilityService.getLastEventPackage() } catch (_: Throwable) { null }
        val activePkg = try { AutoClickAccessibilityService.getLastActiveWindowPackage() } catch (_: Throwable) { null }
        dbg(
          "gesture click ok (color_module) at screen=($x,$y) usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}] " +
            "stats=D:${st?.dispatched} OK:${st?.completed} C:${st?.cancelled} IF:${st?.immediateFail} T:${st?.timeout} reason=${st?.lastFailReason} pkg=$pkg activePkg=$activePkg"
        )
      }
      return ok
    }

    // 스와이프 체인(toIndex)
    if (m.toIndex != 0) {
      val usable = getUsableRectPx()
      val points = ArrayList<Pair<Int, Int>>()
      points.add(markerScreenCenterPxFromStored(m, usable))
      var cur = m.toIndex
      var lastHoldMs = 0L
      var guard = 0
      while (cur != 0 && guard++ < 24) {
        val node = snapshot.firstOrNull { it.index == cur } ?: break
        if (node.kind != "swipe_to") break
        points.add(markerScreenCenterPxFromStored(node, usable))
        lastHoldMs = node.moveUpMs.toLong().coerceAtLeast(0L)
        cur = node.toIndex
      }
      // 스와이프는 너무 짧으면 cancelled가 잦아 최소 시간을 올림
      val dur = pressMsForMarkerClick(m).coerceIn(120L, 3000L)
      val run = {
        try {
          val p0 = points.firstOrNull()
          val p1 = points.lastOrNull()
          appendExecLog(3, "swipe(chain) idx=${m.index} points=${points.size} from=${p0} to=${p1} dur=${dur}ms hold=${lastHoldMs}ms")
        } catch (_: Throwable) {}
        try {
          val p0 = points.firstOrNull()
          val p1 = points.lastOrNull()
          if (p0 != null && p1 != null) {
            adbStream("ACT cat=3 kind=swipe idx=${m.index} from=(${p0.first},${p0.second}) to=(${p1.first},${p1.second}) dur=${dur}ms hold=${lastHoldMs}ms")
          } else {
            adbStream("ACT cat=3 kind=swipe idx=${m.index} points=${points.size} dur=${dur}ms hold=${lastHoldMs}ms")
          }
        } catch (_: Throwable) {}
        val ok = AutoClickAccessibilityService.swipePathPx(points, moveDurationMs = dur, holdMs = lastHoldMs, delayMs = 0L)
        if (!ok) {
          val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
          val reason = st?.lastFailReason ?: "unknown"
          dbg("gesture swipePath returned false, points=${points.size} dur=$dur hold=$lastHoldMs reason=$reason")
        }
        ok
      }
      return if (m.soloExec) withGlobalPause { run() } else run()
    }

    // 스와이프(메인)인데 체인이 없으면 기본 스와이프 실행(오른쪽으로)
    if (m.kind == "swipe") {
      val (w, h, _) = getScreenSize()
      val usable = getUsableRectPx()
      val (sx, sy) = markerScreenCenterPxFromStored(m, usable)
      val toX = (sx + dpToPx(220f)).coerceIn(0, (w - 1).coerceAtLeast(0))
      val dur = pressMsForMarkerClick(m).coerceIn(120L, 3000L)
      try {
        appendExecLog(3, "swipe idx=${m.index} from=($sx,$sy) to=($toX,$sy) dur=${dur}ms")
      } catch (_: Throwable) {}
      try {
        adbStream("ACT cat=3 kind=swipe idx=${m.index} from=($sx,$sy) to=($toX,$sy) dur=${dur}ms")
      } catch (_: Throwable) {}
      val ok = AutoClickAccessibilityService.swipe(
        fromX = sx,
        fromY = sy,
        toX = toX,
        toY = sy,
        durationMs = dur,
        delayMs = 0L
      )
      if (!ok) {
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        val reason = st?.lastFailReason ?: "unknown"
        dbg("gesture swipe returned false, from=($sx,$sy) to=($toX,$sy) reason=$reason")
      }
      return ok
    }

    // 방향모듈 (modulePattern/moduleDirMode 지원)
    if (m.kind == "module") {
      val usable = getUsableRectPx()
      val (sx, sy) = markerScreenCenterPxFromStored(m, usable)
      val dist = (if (m.moduleLenPx > 0) m.moduleLenPx else dpToPx(220f)).coerceIn(30, 5000)
      val dur = pressMsForMarkerClick(m).coerceIn(80L, 5000L)
      val hold = m.moduleMoveUpMs.toLong().coerceIn(0L, 600000L)
      try {
        val pat = m.modulePattern.coerceIn(0, 10)
        val mode = m.moduleDirMode.coerceIn(0, 1)
        appendExecLog(5, "module idx=${m.index} at=($sx,$sy) dist=${dist}px dur=${dur}ms hold=${hold}ms pattern=$pat dirMode=$mode")
      } catch (_: Throwable) {}

      fun dirsForPattern(pattern: Int): List<String> {
        return when (pattern.coerceIn(0, 10)) {
          0 -> listOf("U", "R", "D", "L") // 시계
          1 -> listOf("U", "L", "D", "R") // 반시계
          2 -> listOf("U", "D", "L", "R") // 상하좌우
          3 -> listOf("L", "R", "U", "D") // 좌우상하
          4 -> listOf("L", "R") // 좌우
          5 -> listOf("U", "D") // 상하
          6 -> listOf("U") // 상
          7 -> listOf("D") // 하
          8 -> listOf("L") // 좌
          9 -> listOf("R") // 우
          else -> listOf("U", "R", "D", "L") // 10=랜덤(런타임에서 별도 처리)
        }
      }

      fun nextDirsForTick(): List<String> {
        val mode = m.moduleDirMode.coerceIn(0, 1)
        val pattern = m.modulePattern.coerceIn(0, 10)

        // mode 1: 전방향(한 번에 여러 방향)
        if (mode == 1) {
          return if (pattern == 10) {
            listOf("U", "R", "D", "L").shuffled()
          } else {
            dirsForPattern(pattern)
          }
        }

        // mode 0: 한방향씩(커서/랜덤bag)
        if (pattern == 10) {
          synchronized(moduleDirLock) {
            val rt = moduleDirRuntime.getOrPut(m.index) { ModuleDirRuntime() }
            if (rt.bag.isEmpty()) {
              rt.bag.addAll(listOf("U", "R", "D", "L").shuffled())
            }
            return listOf(rt.bag.removeAt(0))
          }
        }

        val seq = dirsForPattern(pattern)
        synchronized(moduleDirLock) {
          val rt = moduleDirRuntime.getOrPut(m.index) { ModuleDirRuntime() }
          val pos = (rt.cursor % seq.size).coerceAtLeast(0)
          rt.cursor = (rt.cursor + 1) % seq.size
          return listOf(seq[pos])
        }
      }

      fun dxdyForDir(dir: String): Pair<Int, Int>? {
        return when (dir) {
          "TAP" -> null
          "U" -> Pair(0, -dist)
          "D" -> Pair(0, dist)
          "L" -> Pair(-dist, 0)
          "R" -> Pair(dist, 0)
          "UL" -> Pair(-dist, -dist)
          "UR" -> Pair(dist, -dist)
          "DL" -> Pair(-dist, dist)
          "DR" -> Pair(dist, dist)
          else -> Pair(dist, 0)
        }
      }

      val dirs = nextDirsForTick()
      val run = {
        for (d in dirs) {
          val dxdy = dxdyForDir(d.ifBlank { "R" })
          if (dxdy == null) {
            try { adbStream("ACT cat=5 kind=module idx=${m.index} tap($sx,$sy) dir=TAP") } catch (_: Throwable) {}
            val ok = clickMaybeDoubleForMarker(m, sx, sy)
            if (!ok) {
              val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
              dbg("gesture click returned false (module tap), lastReason=${st?.lastFailReason}")
              break
            }
          } else {
            // (중요) usable rect 바깥(네비게이션바/컷아웃)으로 나가지 않게 클램프
            val tx = (sx + dxdy.first).coerceIn(usable.left, usable.right - 1)
            val ty = (sy + dxdy.second).coerceIn(usable.top, usable.bottom - 1)
            try { adbStream("ACT cat=5 kind=module idx=${m.index} swipe from=($sx,$sy) to=($tx,$ty) dur=${dur}ms hold=${hold}ms") } catch (_: Throwable) {}
            val ok = AutoClickAccessibilityService.swipePathPx(
              listOf(Pair(sx, sy), Pair(tx, ty)),
              moveDurationMs = dur,
              holdMs = hold,
              delayMs = 0L
            )
            if (!ok) {
              val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
              val reason = st?.lastFailReason ?: "unknown"
              dbg("gesture swipePath returned false (module), from=($sx,$sy) to=($tx,$ty) dur=$dur hold=$hold reason=$reason")
              break
            }
          }
          // 비동기 보정(ATX2 스타일)
          try {
            Thread.sleep(dur + 40L + hold)
          } catch (_: InterruptedException) {
            break
          }
        }
      }

      // 모듈 실행 중에는 다른 마커 pause(명세)
      return withModuleRunning { run(); true }
    }

    // 색상 조건부 실행(독립 클릭 등)
    if (m.useColor && m.colorIndex != 0) {
      val colorMarker = snapshot.firstOrNull { it.index == m.colorIndex && it.kind == "color" } ?: return true
      if (!isColorMatch(colorMarker)) return true
    }

    val usable = getUsableRectPx()
    val (x, y) = markerScreenCenterPxFromStored(m, usable)
    val isSolo = (m.kind == "solo_main" || m.kind == "solo_item" || m.kind == "solo")
    val soloVerifyEligible = isSolo && m.soloVerifyUse && m.soloVerifyTemplateFile.isNotBlank()
    var soloVerifyCaptureOk = captureReady
    var soloVerifyWaitedMs = 0
    // (중요) 단독실행 중 captureReady가 일시적으로 false가 되면(회전/재구성/프레임 지연),
    // "이미지 없음" 로직을 타지 못하고 그냥 클릭만 진행될 수 있다.
    // -> 짧게 대기/재시도 후에도 준비가 안 되면 안전하게 "재개(=점프/종료)"로 처리한다.
    if (soloVerifyEligible && !soloVerifyCaptureOk) {
      val stepMs = 80
      val tries = 6 // 약 480ms
      for (i in 0 until tries) {
        if (captureReady) {
          soloVerifyCaptureOk = true
          break
        }
        try {
          sleepPausable(stepMs.toLong())
        } catch (_: Throwable) {
          // ignore
        }
        soloVerifyWaitedMs += stepMs
      }
      soloVerifyCaptureOk = captureReady
    }
    val doSoloVerify = soloVerifyEligible && soloVerifyCaptureOk

    // (디버깅) 단독실행에서 "이미지검사(클릭실행확인)가 안 돈다"를 로그로 확정하기 위해
    // 게이트 조건을 반드시 기록한다. (soloVerify 루프로 진입하지 못한 경우에 특히 중요)
    if (isSolo) {
      val expectSoloVerify = m.soloVerifyUse || m.soloVerifyTemplateFile.isNotBlank()
      if (expectSoloVerify) {
        try {
          dbg(
            "soloVerify gate idx=${m.index} kind=${m.kind} " +
              "captureReady=$captureReady waited=${soloVerifyWaitedMs}ms captureOk=$soloVerifyCaptureOk " +
              "soloVerifyUse=${m.soloVerifyUse} tplBlank=${m.soloVerifyTemplateFile.isBlank()} willRun=$doSoloVerify"
          )
        } catch (_: Throwable) {}
      }
    }

    fun logClickOk(cx: Int, cy: Int) {
      try {
        val cat = markerCat(m.kind)
        if (cat != 0) appendExecLog(cat, "${m.kind} idx=${m.index} tap($cx,$cy) press=${pressMsForMarkerClick(m)}ms")
      } catch (_: Throwable) {}
      // (디버깅) 실제 클릭 성공 시점 스트리밍
      try {
        val cat = markerCat(m.kind)
        adbStream("OK cat=$cat kind=${m.kind} idx=${m.index} tap($cx,$cy) press=${pressMsForMarkerClick(m)}ms")
      } catch (_: Throwable) {}
      try { showTouchVizAt(cx, cy) } catch (_: Throwable) {}
    }

    // (중요) soloVerify가 필요한데 capture가 준비되지 않으면,
    // 임의 클릭으로 진행하지 말고 "이미지 없음"에 준해서 재개(goto)/단독종료 처리한다.
    if (soloVerifyEligible && !soloVerifyCaptureOk) {
      // (요청) 실행 중 화면공유 권한이 "해제"된 경우(mediaProjection==null):
      // 일반모드로 계속 실행해야 하므로 goto 없이 순서대로 진행(=verify/goto 무효화 후 클릭)
      val captureRevoked = (!captureReady && (mediaProjection == null))
      if (captureRevoked) {
        try {
          dbg(
            "soloVerify captureRevoked idx=${m.index} waited=${soloVerifyWaitedMs}ms -> fallback plain click (no goto)"
          )
        } catch (_: Throwable) {}
        try {
          soloAbortRequested = false
          soloGotoRequestedIndex = 0
        } catch (_: Throwable) {}
        try { adbStream("ACT cat=${markerCat(m.kind)} kind=${m.kind} idx=${m.index} tap($x,$y) press=${pressMsForMarkerClick(m)}ms (captureRevoked)") } catch (_: Throwable) {}
        val ok = clickMaybeDoubleForMarker(m, x, y)
        if (ok) logClickOk(x, y)
        return ok
      }

      val gotoTarget = try { m.soloVerifyGotoOnStopMissing } catch (_: Throwable) { 0 }
      try {
        dbg(
          "soloVerify captureNotReady idx=${m.index} waited=${soloVerifyWaitedMs}ms " +
            "goto=$gotoTarget -> resumeOrAbort"
        )
      } catch (_: Throwable) {}
      if (gotoTarget != 0 && gotoTarget != m.index) {
        soloGotoRequestedIndex = gotoTarget
        soloAbortRequested = false
      } else {
        soloGotoRequestedIndex = 0
        soloAbortRequested = true
      }
      return true
    }

    if (!doSoloVerify) {
      try { adbStream("ACT cat=${markerCat(m.kind)} kind=${m.kind} idx=${m.index} tap($x,$y) press=${pressMsForMarkerClick(m)}ms") } catch (_: Throwable) {}
      val ok = clickMaybeDoubleForMarker(m, x, y)
      if (!ok) {
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        dbg("gesture click returned false, lastReason=${st?.lastFailReason}")
      } else {
        // (요청) 실제 클릭 성공한 경우에만 로그 기록
        logClickOk(x, y)
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        val pkg = try { AutoClickAccessibilityService.getLastEventPackage() } catch (_: Throwable) { null }
        val activePkg = try { AutoClickAccessibilityService.getLastActiveWindowPackage() } catch (_: Throwable) { null }
        dbg(
          "gesture click ok at screen=($x,$y) usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}] " +
            "stats=D:${st?.dispatched} OK:${st?.completed} C:${st?.cancelled} IF:${st?.immediateFail} T:${st?.timeout} reason=${st?.lastFailReason} pkg=$pkg activePkg=$activePkg"
        )
      }
      return ok
    }

    // ---- solo verify(클릭실행확인) ----
    // (요청) soloVerify 진행 중 화면공유 권한이 해제되면 일반모드로 전환(verify/goto 무효화)
    if (!captureReady || mediaProjection == null) {
      try {
        dbg("soloVerify aborted(captureLost) idx=${m.index} -> fallback plain click (no goto)")
      } catch (_: Throwable) {}
      try {
        soloAbortRequested = false
        soloGotoRequestedIndex = 0
      } catch (_: Throwable) {}
      try { adbStream("ACT cat=${markerCat(m.kind)} kind=${m.kind} idx=${m.index} tap($x,$y) press=${pressMsForMarkerClick(m)}ms (captureLost)") } catch (_: Throwable) {}
      val ok = clickMaybeDoubleForMarker(m, x, y)
      if (ok) logClickOk(x, y)
      return ok
    }

    // (추가 요청) goto를 선택한 경우에만 "실행전 클릭"을 연결한다.
    // solo_item index는 음수일 수 있으므로, 0만 "단독 종료"로 취급한다.
    // (변경) 실행확인 방식은 1가지로 고정이므로, goto도 "없으면 재개" 1개만 사용한다.
    val gotoEnabled = (m.soloVerifyGotoOnStopMissing != 0)
    // (요청) 좌표선택 이동 범위를 "화면 전체(네비바/컷아웃 포함)"로 확장했으므로,
    // preClick 좌표도 usable 내부로 강제 클램프하지 않는다.
    val hasPreClickCoord = !(m.soloPreClickXPx == -1 && m.soloPreClickYPx == -1)
    if (gotoEnabled && m.soloPreClickUse && hasPreClickCoord) {
      val screen0 = getScreenSize()
      val wS0 = screen0.width.coerceAtLeast(1)
      val hS0 = screen0.height.coerceAtLeast(1)
      val pxU = m.soloPreClickXPx
      val pyU = m.soloPreClickYPx
      val pxSRaw = (usable.left + pxU)
      val pySRaw = (usable.top + pyU)
      val pxS = pxSRaw.coerceIn(0, (wS0 - 1).coerceAtLeast(0))
      val pyS = pySRaw.coerceIn(0, (hS0 - 1).coerceAtLeast(0))
      try {
        dbg(
          "soloVerify preClickTap idx=${m.index} gotoEnabled=true " +
            "atUsable=($pxU,$pyU) atScreenRaw=($pxSRaw,$pySRaw) atScreen=($pxS,$pyS) " +
            "screen=${wS0}x${hS0} usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}]"
        )
      } catch (_: Throwable) {}
      val okPre = clickMaybeDoubleForMarker(m, pxS, pyS)
      if (!okPre) {
        val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
        dbg("gesture click returned false (soloPreClick), lastReason=${st?.lastFailReason}")
      } else {
        // (요청) 실제 클릭 성공한 경우에만 로그 기록
        logClickOk(pxS, pyS)
      }
      try {
        Thread.sleep(1000L)
      } catch (_: InterruptedException) {
        soloAbortRequested = true
        soloGotoRequestedIndex = 0
        return okPre
      }
    }

    // (변경) 실행확인 방식 1가지로 통일:
    // - 이미지가 있으면: 1회 클릭하고 다음 단독sub로 진행
    // - 이미지가 없으면: 재개(goto)로 선택된 단독sub로 점프(없으면 단독 종료)
    val tpl =
      loadTemplateImage(m.soloVerifyTemplateFile) ?: run {
        // (요청) "재개" 의미: 단독 구간을 즉시 종료해서 다른 마커가 다시 돌도록 한다.
        soloAbortRequested = true
        soloGotoRequestedIndex = 0
        return true
      }

    val wU = usable.width().coerceAtLeast(1)
    val hU = usable.height().coerceAtLeast(1)
    val sxU0 = (if (m.soloVerifyStartXPx >= 0) m.soloVerifyStartXPx else 0).coerceIn(0, (wU - 1).coerceAtLeast(0))
    val syU0 = (if (m.soloVerifyStartYPx >= 0) m.soloVerifyStartYPx else 0).coerceIn(0, (hU - 1).coerceAtLeast(0))
    val exU0 = (if (m.soloVerifyEndXPx >= 0) m.soloVerifyEndXPx else (wU - 1)).coerceIn(0, (wU - 1).coerceAtLeast(0))
    val eyU0 = (if (m.soloVerifyEndYPx >= 0) m.soloVerifyEndYPx else (hU - 1)).coerceIn(0, (hU - 1).coerceAtLeast(0))
    val sxU = minOf(sxU0, exU0)
    val syU = minOf(syU0, eyU0)
    val exU = maxOf(sxU0, exU0)
    val eyU = maxOf(syU0, eyU0)
    val leftS = (usable.left + sxU).coerceIn(usable.left, usable.right - 1)
    val topS = (usable.top + syU).coerceIn(usable.top, usable.bottom - 1)
    val rightS = (usable.left + exU).coerceIn(usable.left + 1, usable.right)
    val bottomS = (usable.top + eyU).coerceIn(usable.top + 1, usable.bottom)
    val acc = m.soloVerifyAccuracyPct.coerceIn(50, 100)
    val minScore = (0.55f + (acc - 50).coerceIn(0, 50) / 50f * 0.30f).coerceIn(0.55f, 0.85f)

    // (변경) 실행확인 방식은 1가지로 고정(mode 변수 불필요)
    val screen = getScreenSize()
    val wS = screen.width.coerceAtLeast(1)
    val hS = screen.height.coerceAtLeast(1)
    // (요청) 클릭 좌표는 "마커 원 내부(반지름까지)"에서만 생성한다.
    // markerSizePx는 현재 UI 스케일을 반영한 마커 지름(px)이다.
    val radiusPx = (markerSizePx / 2).coerceAtLeast(4)
    var leftWalkDistPx = 0
    var switchedToUniform = false

    fun foundNow(): Triple<Boolean, Float, TemplateMatchDebug?> {
      // (요청) 템플릿 매칭을 5번 확인해서 흔들림을 줄인다.
      // 판정은 다수결(3/5 이상)로 결정하고, best score는 로깅용으로 유지한다.
      fun matchOnce(): TemplateMatchDebug? {
        return try { findBestTemplateInRegion(tpl, leftS, topS, rightS, bottomS) } catch (_: Throwable) { null }
      }

      val tries = 5
      val intervalMs = 40L
      val ms = ArrayList<TemplateMatchDebug?>(tries)
      for (k in 0 until tries) {
        ms.add(matchOnce())
        if (k != tries - 1) {
          try {
            Thread.sleep(intervalMs)
          } catch (_: InterruptedException) {
            val best = ms.maxByOrNull { it?.score ?: -1f }
            val s = best?.score ?: -1f
            val hit = ms.count { (it?.score ?: -1f) >= minScore }
            val ok = hit >= ((tries / 2) + 1)
            return Triple(ok, s, best)
          }
        }
      }

      val hit = ms.count { (it?.score ?: -1f) >= minScore }
      val ok = hit >= ((tries / 2) + 1) // 3/5
      val best = ms.maxByOrNull { it?.score ?: -1f }
      val s = best?.score ?: -1f
      return Triple(ok, s, best)
    }

    val (found, score, best) = try { foundNow() } catch (_: Throwable) { Triple(false, -1f, null) }
    if (!found) {
      // 이미지가 없으면: 재개(goto)로 점프(없으면 단독 종료)
      val gotoTarget = try { m.soloVerifyGotoOnStopMissing } catch (_: Throwable) { 0 }
      try {
        dbg(
          "soloVerify resume idx=${m.index} found=false score=${"%.3f".format(score)} " +
            "best=(${best?.cx ?: -1},${best?.cy ?: -1}) acc=$acc min=$minScore " +
            "goto=$gotoTarget searchRect=[$leftS,$topS,$rightS,$bottomS] usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}]"
        )
      } catch (_: Throwable) {}
      if (gotoTarget != 0 && gotoTarget != m.index) {
        soloGotoRequestedIndex = gotoTarget
        soloAbortRequested = false
      } else {
        soloGotoRequestedIndex = 0
        soloAbortRequested = true
      }
      // 재개는 클릭이 아니라 점프/종료이므로 true 반환(매크로 유지)
      return true
    }

    // 이미지가 있으면: 1회 클릭하고 다음 단독sub로 진행
    fun nextDxDyInCircle(): Pair<Int, Int> {
      // phase 1: left walk
      if (!switchedToUniform) {
        val step = Random.nextInt(3, 9) // 3..8
        leftWalkDistPx = (leftWalkDistPx + step).coerceAtMost(radiusPx)
        val dx = -leftWalkDistPx
        val dy = 0
        if (leftWalkDistPx >= radiusPx) switchedToUniform = true
        return Pair(dx, dy)
      }
      // phase 2: uniform in disk (angle+radius)
      val rMin = if (radiusPx >= 3) 3.0 else 0.0
      val rMax = radiusPx.toDouble()
      val u = Random.nextDouble()
      val r =
        if (rMax <= rMin) rMax
        else kotlin.math.sqrt(u * (rMax * rMax - rMin * rMin) + rMin * rMin)
      val theta = Random.nextDouble() * 2.0 * Math.PI
      var dx = kotlin.math.round(r * kotlin.math.cos(theta)).toInt()
      var dy = kotlin.math.round(r * kotlin.math.sin(theta)).toInt()
      // 반올림으로 원 밖으로 나가는 경우 보정
      val d2 = (dx * dx + dy * dy).toDouble()
      val r2 = (radiusPx * radiusPx).toDouble()
      if (d2 > r2 && d2 > 0.0) {
        val s = kotlin.math.sqrt(r2 / d2)
        dx = kotlin.math.round(dx.toDouble() * s).toInt()
        dy = kotlin.math.round(dy.toDouble() * s).toInt()
      }
      return Pair(dx, dy)
    }

    val (dx, dy) = nextDxDyInCircle()
    val cx = (x + dx).coerceIn(0, (wS - 1).coerceAtLeast(0))
    val cy = (y + dy).coerceIn(0, (hS - 1).coerceAtLeast(0))
    try {
      dbg(
        "soloVerify clickOnce idx=${m.index} found=true score=${"%.3f".format(score)} " +
          "best=(${best?.cx ?: -1},${best?.cy ?: -1}) acc=$acc min=$minScore radius=$radiusPx " +
          "phase=${if (switchedToUniform) "uniform" else "left"} d=($dx,$dy) at=($cx,$cy) " +
          "searchRect=[$leftS,$topS,$rightS,$bottomS]"
      )
    } catch (_: Throwable) {}
    val ok = clickMaybeDoubleForMarker(m, cx, cy)
    if (ok) logClickOk(cx, cy)
    else {
      val st = try { AutoClickAccessibilityService.getGestureStats() } catch (_: Throwable) { null }
      dbg("gesture click returned false (soloVerify clickOnce), lastReason=${st?.lastFailReason}")
    }
    return ok
  }

  private fun startOrderedLoop() {
    orderedThread =
      Thread {
        try {
          val cond = loadStopCond()
          dbg("orderedLoop start stopMode=${cond.mode} timeSec=${cond.timeSec} cycles=${cond.cycles}")
          val startedAt = android.os.SystemClock.elapsedRealtime()
          var cycle = 0
          while (macroRunning) {
            // stop 조건
            if (cond.mode == "time" && cond.timeSec > 0) {
              val now = android.os.SystemClock.elapsedRealtime()
              val pausedSoFar =
                pausedTotalMs +
                  if (pauseCount > 0 && pauseBeganAtMs > 0L) (now - pauseBeganAtMs).coerceAtLeast(0L) else 0L
              val activeElapsedMs = (now - startedAt - pausedSoFar).coerceAtLeast(0L)
              if (activeElapsedMs >= cond.timeSec.toLong() * 1000L) break
            }
            if ((cond.mode == "cycle" || cond.mode == "cycles") && cycle >= cond.cycles) break

            val snap = markersSnapshot()
            val ordered =
              snap
                // 순번실행: click + swipe(메인, swipeMode=0) + index>0 만 순서대로 실행
                .filter {
                  (it.kind == "click" && it.index > 0) ||
                    (it.kind == "swipe" && it.index > 0 && it.swipeMode == 0)
                    // module 순번: index>0 && moduleSoloExec!=true
                    || (it.kind == "module" && it.index > 0 && !it.moduleSoloExec)
                }
                .sortedBy { it.index }

            // (중요) 순번 마커가 없으면 busy loop/cycle 폭주로 인해 stop_mode=cycle에서
            // solo가 실행되기 전에 매크로가 종료될 수 있다. 또한 pause 중에는 여기서도 멈춰야 한다.
            if (ordered.isEmpty()) {
              sleepPausable(200L)
              continue
            }

            for (m in ordered) {
              if (!macroRunning) break
              fireMarkerOnce(m, snap)
              sleepPausable(delayWithJitterMs(m.delayMs, jitterPctForMarkerDelay(m)))
            }
            cycle++
          }
          // 종료 처리
          if (macroRunning) {
            // stop 조건 도달
            Handler(Looper.getMainLooper()).post { requestStop("ordered_stop_condition") }
          }
        } catch (t: Throwable) {
          Handler(Looper.getMainLooper()).post {
            try {
              val msg = t.message?.take(60)?.let { " $it" } ?: ""
              updateNotification("실행 오류: ${t.javaClass.simpleName}$msg")
            } catch (_: Throwable) {}
            try {
              requestStop("ordered_exception", t)
            } catch (_: Throwable) {}
          }
        }
      }.apply { start() }
  }

  private fun startIndependentLoops() {
    // 독립실행: 순번과 무관하게, 각 마커가 설정된 지연시간마다 실행
    // - 대상: kind=="independent" + swipe(메인, swipeMode=1)
    // 기존 구현은 start 시점 스냅샷만 사용해, 이후 마커 변경/추가 시 반영되지 않는 문제가 있었음.
    // 슈퍼바이저가 주기적으로 최신 목록을 보고 워커를 upsert 한다.
    if (independentSupervisorThread != null) return

    fun isIndependentKind(m: Marker): Boolean {
      val isIndependent = (m.kind == "independent")
      val isIndependentSwipe = (m.kind == "swipe" && m.swipeMode == 1)
      val isIndependentModule = (m.kind == "module" && (m.index < 0 || m.moduleSoloExec))
      val isColorModule = (m.kind == "color_module")
      val isImageModule = (m.kind == "image_module")
      // (요청) 이미지모듈은 색상모듈처럼 "조건 만족 시 클릭"이므로 독립 루프에서 주기 실행되게 한다.
      return isIndependent || isIndependentSwipe || isIndependentModule || isColorModule || isImageModule
    }

    fun startWorker(idx: Int) {
      if (independentThreads.containsKey(idx)) return
      val t =
        Thread {
          try {
            while (macroRunning) {
              // pause 중에는 실행/타이머 감소 모두 멈춤(남은 시간 보존)
              synchronized(pauseLock) {
                while (macroRunning && pauseCount > 0) {
                  try {
                    pauseLock.wait(250L)
                  } catch (_: InterruptedException) {
                    return@Thread
                  }
                }
              }
              if (!macroRunning) break

              val cur =
                synchronized(markersLock) {
                  markersCache.firstOrNull { it.index == idx }?.copy()
                } ?: break

              if (!isIndependentKind(cur)) break

              fireMarkerOnce(cur)
              sleepPausable(delayWithJitterMs(cur.delayMs, jitterPctForMarkerDelay(cur)))
            }
          } catch (t: Throwable) {
            Handler(Looper.getMainLooper()).post {
              try {
                val msg = t.message?.take(60)?.let { " $it" } ?: ""
                updateNotification("실행 오류: ${t.javaClass.simpleName}$msg")
              } catch (_: Throwable) {}
              try {
                requestStop("independent_exception idx=$idx", t)
              } catch (_: Throwable) {}
            }
          } finally {
            synchronized(independentThreads) {
              independentThreads.remove(idx)
            }
          }
        }.apply { start() }

      independentThreads[idx] = t
    }

    independentSupervisorThread =
      Thread {
        try {
          while (macroRunning) {
            val snap = markersSnapshot()
            val want = snap.filter { isIndependentKind(it) }.map { it.index }.toSet()

            // start missing
            for (idx in want) startWorker(idx)

            // stop removed
            val running = independentThreads.keys.toList()
            for (idx in running) {
              if (!want.contains(idx)) {
                try {
                  independentThreads[idx]?.interrupt()
                } catch (_: Throwable) {}
                independentThreads.remove(idx)
              }
            }

            sleepPausable(500L)
          }
        } catch (_: Throwable) {
          // ignore
        }
      }.apply { start() }
  }

  private fun startSoloLoop() {
    soloThread =
      Thread {
        try {
          // ATX2 동등: pause 중에는 타이머가 줄지 않아야 하므로 "남은 시간(remain)" 기반으로 스케줄
          val remainToNext = LinkedHashMap<Int, Long>() // soloMainIndex -> remainMs (다른 마커 pause 중에는 감소하지 않음)
          dbg("soloLoop start")
          var lastEmptyLogAt = 0L
          var lastDueLogAt = 0L
          val executedOnce = HashSet<Int>()
          val preDelaySeen = LinkedHashMap<Int, Int>() // 첫 실행 전 pre-delay 변경 보정용

          fun parseSoloItemOrder(label: String): Pair<String, Int?> {
            // 예: A1, A10, B2 ...
            val s = label.trim()
            if (s.isEmpty()) return Pair("", null)
            var i = s.length - 1
            while (i >= 0 && s[i].isDigit()) i--
            val head = s.substring(0, i + 1)
            val tail = s.substring(i + 1)
            val num = tail.toIntOrNull()
            return Pair(head.uppercase(), num)
          }

          while (macroRunning) {
            // 1) 최신 solo_main 목록 수집(라벨 정렬)
            val mains =
              synchronized(markersLock) {
                markersCache
                  .filter { (it.kind == "solo_main" || it.kind == "solo") && it.soloLabel.isNotBlank() }
                  .sortedBy { it.soloLabel.trim().uppercase() }
                  .map { it.copy() }
              }

            if (mains.isEmpty()) {
              val now = android.os.SystemClock.uptimeMillis()
              if (now - lastEmptyLogAt > 4000L) {
                dbg("soloLoop mains=0 (단독메인 없음/미인식)")
                lastEmptyLogAt = now
              }
              sleepPausable(300L)
              continue
            }

            // 2) 신규 메인 초기화 + preDelay 변경 보정(첫 실행 전만)
            for (m in mains) {
              val idx = m.index
              if (!remainToNext.containsKey(idx)) {
                val pre = m.soloStartDelayMs.coerceAtLeast(0)
                // 요구: 단독실행은 독립실행처럼 "실행전 지연(preDelay)"이 다른 마커와 무관하게 흐르다가
                // due 시점에만 전체 pause 후 실행되어야 한다.
                remainToNext[idx] = pre.toLong()
                preDelaySeen[idx] = pre
              } else if (!executedOnce.contains(idx)) {
                // 첫 실행 전 preDelay 변경은 즉시 반영(남은 시간 보정)
                val pre = m.soloStartDelayMs.coerceAtLeast(0)
                val oldPre = preDelaySeen[idx] ?: pre
                if (oldPre != pre) {
                  val remain = remainToNext[idx] ?: pre.toLong()
                  remainToNext[idx] = (remain + (pre - oldPre).toLong()).coerceAtLeast(0L)
                  preDelaySeen[idx] = pre
                }
              }
            }

            // 제거된 메인 정리
            val alive = mains.map { it.index }.toSet()
            val drop = remainToNext.keys.filter { it !in alive }
            for (k in drop) {
              remainToNext.remove(k)
              preDelaySeen.remove(k)
              executedOnce.remove(k)
            }

            // 3) due 선택(라벨 순으로 remain<=0)
            val due = mains.firstOrNull { (remainToNext[it.index] ?: 0L) <= 0L }
            if (due == null) {
              // due 없음: pause가 아닐 때만 step 감소
              val step = 50L
              synchronized(pauseLock) {
                if (pauseCount == 0) {
                  for ((k, v) in remainToNext) {
                    remainToNext[k] = (v - step).coerceAtLeast(0L)
                  }
                }
              }
              sleepPausable(step)
              continue
            }

            val now2 = android.os.SystemClock.uptimeMillis()
            if (now2 - lastDueLogAt > 800L) {
              dbg(
                "soloLoop due=${due.soloLabel} idx=${due.index} preDelayMs=${due.soloStartDelayMs} combo=${due.soloComboCount} delayMs=${due.delayMs} jitter=${due.jitterPct}"
              )
              lastDueLogAt = now2
            }

            // 4) 실행
            // 요구: 단독 실행이 트리거되면 모든 마커를 중지(pause)하고,
            // 실행전 지연(preDelay) 후 단독메인->단독sub를 순서대로 실행,
            // 마지막 sub 이후에는 중지했던 마커들이 "남은 시간" 그대로 이어서 실행되어야 한다.
            // pauseCount + sleepPausable() 구조로 타 마커의 남은 시간이 자동 보존된다.
            withGlobalPause {
              // (요청) solo verify에서 "재개"가 발생하면:
              // - 기본: 단독 구간을 즉시 종료한다.
              // - 추가: 모드별 goto 콤보가 설정되어 있으면 해당 단독 sub로 점프한다.
              soloAbortRequested = false
              soloGotoRequestedIndex = 0
              // (중요) preDelay는 remainToNext에서 독립적으로 카운트다운한다.
              // 여기서는 "실행 구간"만 pause 한다(다른 마커 남은 시간 보존).
              fireMarkerOnce(due)
              if (soloAbortRequested) return@withGlobalPause

              val items =
                synchronized(markersLock) {
                  markersCache
                    .filter { it.kind == "solo_item" && it.parentIndex == due.index && it.soloLabel.isNotBlank() }
                    .map { it.copy() }
                }.sortedWith { a, b ->
                  val (ha, na) = parseSoloItemOrder(a.soloLabel)
                  val (hb, nb) = parseSoloItemOrder(b.soloLabel)
                  val c1 = ha.compareTo(hb)
                  if (c1 != 0) return@sortedWith c1
                  when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null && nb == null -> -1
                    na == null && nb != null -> 1
                    else -> a.soloLabel.compareTo(b.soloLabel)
                  }
                }

              val combo = due.soloComboCount.coerceIn(1, 10)
              var done = 0
              var p = 0

              // 메인(solo_main)에서 goto가 발생한 경우, sub 시작 지점을 점프한다.
              val g0 = soloGotoRequestedIndex
              if (g0 != 0) {
                val jump = items.indexOfFirst { it.index == g0 }
                soloGotoRequestedIndex = 0
                if (jump >= 0) {
                  p = jump
                  try { dbg("soloLoop goto(main) dueIdx=${due.index} target=$g0 pos=$jump") } catch (_: Throwable) {}
                } else {
                  // 타겟이 없으면 안전하게 종료(기존 재개)
                  try { dbg("soloLoop goto(main) targetNotFound dueIdx=${due.index} target=$g0") } catch (_: Throwable) {}
                  soloAbortRequested = true
                }
              }

              while (p < items.size) {
                if (!macroRunning) break
                if (soloAbortRequested) break
                if (done >= combo) break

                val it = items[p]
                try {
                  Thread.sleep(delayWithJitterMs(it.delayMs, jitterPctForMarkerDelay(it))) // 단독 콤보는 pausable 아님(요구사항)
                } catch (_: InterruptedException) {
                  return@withGlobalPause
                }

                fireMarkerOnce(it)
                if (soloAbortRequested) break

                done++

                val g = soloGotoRequestedIndex
                if (g != 0) {
                  val jump = items.indexOfFirst { s -> s.index == g }
                  soloGotoRequestedIndex = 0
                  if (jump > p) {
                    // "선택된 단독sub로 바로 보내기"(나머지 스킵)
                    try { dbg("soloLoop goto(sub) dueIdx=${due.index} fromIdx=${it.index} toIdx=$g fromPos=$p toPos=$jump") } catch (_: Throwable) {}
                    p = jump
                    continue
                  }
                }

                // 다음 sub로 진행
                p++
                try {
                  Thread.sleep(20L)
                } catch (_: InterruptedException) {
                  return@withGlobalPause
                }
              }
            }

            // 5) 다음 실행까지 남은 시간 재설정(독립 딜레이 기반)
            executedOnce.add(due.index)
            val mainWait = delayWithJitterMs(due.delayMs, jitterPctForMarkerDelay(due))
            val pre = due.soloStartDelayMs.toLong().coerceAtLeast(0L)
            remainToNext[due.index] = (mainWait + pre).coerceAtLeast(0L)
          }
        } catch (t: Throwable) {
          Handler(Looper.getMainLooper()).post {
            try {
              val msg = t.message?.take(60)?.let { " $it" } ?: ""
              updateNotification("실행 오류: ${t.javaClass.simpleName}$msg")
            } catch (_: Throwable) {}
            try {
              requestStop("solo_exception", t)
            } catch (_: Throwable) {}
          }
        }
      }.apply { start() }
  }

  private fun applyMarkersVisibility() {
    val visible = shouldShowMarkersNow()
    for ((_, v) in markerViews) {
      v.visibility = if (visible) View.VISIBLE else View.GONE
    }
    // 연결선은 터치 불가 오버레이이므로, 숨김 상태면 알파 0 처리
    try {
      val a = if (visible) 1f else 0f
      for ((_, lw) in linkWins) {
        lw.view.alpha = a
      }
    } catch (_: Throwable) {}
  }

  private fun pushToolbarHiddenByModalInternal() {
    toolbarHiddenByModalCount++
    toolbarRoot?.visibility = View.GONE
  }

  private fun popToolbarHiddenByModalInternal() {
    if (toolbarHiddenByModalCount <= 0) return
    toolbarHiddenByModalCount--
    if (toolbarHiddenByModalCount == 0) {
      toolbarRoot?.visibility = View.VISIBLE
    }
  }

  private fun applyOverlayAlpha() {
    // kept for binary compatibility (unused)
    applyOverlayOpacity()
  }

  // 툴바에만 투명도 적용(나머지 창은 일반/불투명)
  private fun applyOverlayOpacity() {
    val p = overlayOpacityPercent.coerceIn(30, 150)

    // 30~100: View alpha를 0.30~1.00로 사용
    val viewAlpha = (p / 100f).coerceIn(0.30f, 1.0f)

    // 100~150: View alpha는 1.0 고정, 대신 배경 drawable 알파를 더 올려 "더 불투명"하게
    // overlay_toolbar_bg 기본 solid가 #BF.... (191) 이라 가정하고 150%에서 255까지 보간
    val extra = ((p - 100).coerceIn(0, 50)) / 50f
    val bgAlpha = (191 + (255 - 191) * extra).roundToInt().coerceIn(0, 255)

    fun applyToBackground(v: View?) {
      val d: Drawable? = v?.background?.mutate()
      if (d != null) d.alpha = bgAlpha
    }

    toolbarRoot?.alpha = viewAlpha
    applyToBackground(toolbarRoot)
  }

  private fun makeWindowOpaque(v: View?) {
    try {
      v?.alpha = 1f
      val d: Drawable? = v?.background?.mutate()
      if (d != null) d.alpha = 255
    } catch (_: Throwable) {}
  }

  private fun updatePassThroughCaption() {
    // 요구사항: "오버레이통과" 캡션을 ON/OFF로 변경 + 2줄 + 작은 글씨
    val lang = I18n.langFromPrefs(prefs())
    passThroughCb?.text = I18n.passThroughCaption(lang, passThroughEnabled)
    passThroughCb?.isChecked = passThroughEnabled
  }

  private fun updateCoordLabel() {
    val x = lastSampleScreenX
    val y = lastSampleScreenY
    tvCoord?.text = if (x >= 0 && y >= 0) "(${x},${y})" else "(x,y)"
    colorPickerTvCoord?.text = if (x >= 0 && y >= 0) "(${x},${y})" else "(x,y)"
  }

  private fun refreshColorPickerUiIfOpen() {
    val swatch = colorPickerSwatch ?: return
    val tvHex = colorPickerTvHex
    val tvCoord = colorPickerTvCoord
    val cbPass = colorPickerPassThroughCb

    val argb = lastPickedArgb
    swatch.setBackgroundColor(argb)
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    tvHex?.text = String.format("#%02X%02X%02X", r, g, b)

    val x = lastSampleScreenX
    val y = lastSampleScreenY
    tvCoord?.text = if (x >= 0 && y >= 0) "(${x},${y})" else "(x,y)"

    val lang = I18n.langFromPrefs(prefs())
    cbPass?.text = I18n.passThroughCaption(lang, passThroughEnabled)
    cbPass?.isChecked = passThroughEnabled
  }

  // 외부에서 불러 쓰기 쉽게: "색상 패널 열기" 공개 함수명
  private fun callimgsetup() {
    if (panelRoot != null) {
      updatePanelLayout()
      return
    }

    // (요청) 메뉴바설정창(패널) 오픈 시 접근성 메뉴툴바 숨김
    try {
      AutoClickAccessibilityService.requestHideToolbar()
    } catch (_: Throwable) {}

    // 모든 창 표시 시 툴바 숨김
    pushToolbarHiddenByModalInternal()

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_color_panel, null)
    panelRoot = root
    makeWindowOpaque(root)

    // (요청) 메뉴툴바 설정(패널)에 버전 정보 표시(컴파일 시 자동 갱신되는 빌드 일자 포함)
    try {
      val tvVer = root.findViewById<TextView>(R.id.tvVersionInfo)
      val verName = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "?" }
      val verCode = try { BuildConfig.VERSION_CODE } catch (_: Throwable) { -1 }
      val buildDate = try { BuildConfig.BUILD_DATE } catch (_: Throwable) { "" }
      val codePart = if (verCode >= 0) " ($verCode)" else ""
      val datePart = if (buildDate.isNotBlank()) " / 빌드: $buildDate" else ""
      tvVer.text = "버전: $verName$codePart$datePart"
    } catch (_: Throwable) {}

    // 상단 버튼: "메뉴" (요청: 메뉴만 보이게 -> PopupMenu(풀다운) 사용, 레이아웃을 밀지 않음)
    val btnTopMenu = root.findViewById<View>(R.id.btnClosePanel)
    val menuTextWrap = root.findViewById<View>(R.id.menuTextWrap)
    val menuTextCard = root.findViewById<View>(R.id.menuTextCard)
    val tvMenuTextTitle = root.findViewById<TextView>(R.id.tvMenuTextTitle)
    val tvMenuTextBody = root.findViewById<TextView>(R.id.tvMenuTextBody)
    val btnMenuTextClose = root.findViewById<View>(R.id.btnMenuTextClose)

    fun closeMenuText() {
      try { menuTextWrap.visibility = View.GONE } catch (_: Throwable) {}
    }
    fun showMenuText(title: String, body: String) {
      try { tvMenuTextTitle.text = title } catch (_: Throwable) {}
      try { tvMenuTextBody.text = body } catch (_: Throwable) {}
      try { menuTextWrap.visibility = View.VISIBLE } catch (_: Throwable) {}
    }

    fun showPulldownMenu(anchor: View) {
      try { closeMenuText() } catch (_: Throwable) {}
      val lang = I18n.langFromPrefs(prefs())
      try {
        val menu = android.widget.PopupMenu(this, anchor)
        val ID_HELP = 100
        val ID_SCRIPTER = 101
        val ID_RATE = 102
        val ID_SHARE = 103
        val ID_FEEDBACK = 104
        val ID_CREDITS = 105
        val ID_PRIVACY = 106
        menu.menu.add(0, ID_HELP, 0, I18n.help(lang))
        menu.menu.add(0, ID_SCRIPTER, 1, I18n.scripter(lang))
        menu.menu.add(0, ID_RATE, 2, I18n.rateApp(lang))
        menu.menu.add(0, ID_SHARE, 3, I18n.shareApp(lang))
        menu.menu.add(0, ID_FEEDBACK, 4, I18n.feedback(lang))
        menu.menu.add(0, ID_CREDITS, 5, I18n.credits(lang))
        menu.menu.add(0, ID_PRIVACY, 6, I18n.privacyPolicy(lang))
        menu.setOnMenuItemClickListener { item ->
          when (item.itemId) {
            ID_HELP -> {
              // (요청) 메뉴 선택 시 설정창 닫고, 선택 항목 창만 표시
              removeColorPanel()
              showSettingsHelpOverlay(lang)
              true
            }
            ID_SCRIPTER -> {
              removeColorPanel()
              showSettingsTextOverlay(I18n.scripter(lang), buildScripterText(lang))
              true
            }
            ID_RATE -> {
              removeColorPanel()
              openAppReview()
              true
            }
            ID_SHARE -> {
              removeColorPanel()
              shareApp()
              true
            }
            ID_FEEDBACK -> {
              removeColorPanel()
              sendFeedbackEmail()
              true
            }
            ID_CREDITS -> {
              removeColorPanel()
              showSettingsTextOverlay(I18n.credits(lang), "오토클릭짱\n\n- Android 접근성 기반 자동 클릭/스와이프 도구\n- © 2026")
              true
            }
            ID_PRIVACY -> {
              removeColorPanel()
              openPrivacyPolicy()
              true
            }
            else -> false
          }
        }
        menu.show()
      } catch (_: Throwable) {}
    }

    btnTopMenu.setOnClickListener { showPulldownMenu(btnTopMenu) }
    // 패널 닫기는 길게 누르기
    btnTopMenu.setOnLongClickListener { removeColorPanel(); true }

    // 오버레이 배경 클릭 시 닫기(카드 클릭은 무시)
    menuTextWrap.setOnClickListener { closeMenuText() }
    menuTextCard.setOnClickListener { /* consume */ }
    btnMenuTextClose.setOnClickListener { closeMenuText() }

    // 언어 선택
    val spLang = root.findViewById<android.widget.Spinner>(R.id.spLang)
    var suppressLangCallback = false
    fun applyLanguageToOpenUi() {
      val lang = I18n.langFromPrefs(prefs())
      try {
        // RTL 대응(아랍어)
        root.layoutDirection =
          if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
      } catch (_: Throwable) {}
      try {
        root.findViewById<TextView>(R.id.tvPanelTitle).text = I18n.settingsTitle(lang)
      } catch (_: Throwable) {}
      try {
        root.findViewById<TextView>(R.id.btnClosePanel).text = I18n.menu(lang)
      } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnMenuTextClose).text = I18n.close(lang) } catch (_: Throwable) {}
      try {
        root.findViewById<TextView>(R.id.tvLangLabel).text = I18n.language(lang)
      } catch (_: Throwable) {}
      try {
        root.findViewById<TextView>(R.id.tvStopTitle).text = I18n.stopCondition(lang)
      } catch (_: Throwable) {}
      try {
        root.findViewById<android.widget.RadioButton>(R.id.rbInfinite).text = I18n.stopInfinite(lang)
        root.findViewById<android.widget.RadioButton>(R.id.rbTimeLimit).text = I18n.stopTimeLimit(lang)
        root.findViewById<android.widget.RadioButton>(R.id.rbCycleCount).text = I18n.stopCycles(lang)
      } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnScreenSettings).text = I18n.screenSettings(lang) } catch (_: Throwable) {}
      // (요청) 메뉴 설정창에서 "색상패널" 버튼 삭제(숨김)
      try { root.findViewById<android.widget.Button>(R.id.btnColorPanel).text = I18n.colorPanel(lang) } catch (_: Throwable) {}
      try { root.findViewById<View>(R.id.btnColorPanel).visibility = View.GONE } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnMacroSave).text = I18n.macroSave(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnMacroOpen).text = I18n.macroOpen(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnAccessibilitySettings).text = I18n.accessibilitySettings(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnScreenSharePermissionSettings).text = I18n.screenSharePermissionSettings(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnClose).text = I18n.close(lang) } catch (_: Throwable) {}

      // 화면설정/메크로 창이 열려있으면 같이 갱신
      try { applyLanguageToScreenSettingsIfOpen() } catch (_: Throwable) {}
      try { applyLanguageToMacroOverlaysIfOpen() } catch (_: Throwable) {}
      try { updatePassThroughCaption() } catch (_: Throwable) {}
    }

    // (개선) 스피너 항목은 각 언어 자체 표기로 고정 → 어댑터 재생성/재선택으로 인한 지연 제거
    val langs = I18n.languageOptionsSelf()
    val adapter =
      android.widget.ArrayAdapter(
        this,
        R.layout.spinner_lang_selected,
        langs.map { it.second }
      ).apply {
        setDropDownViewResource(R.layout.spinner_lang_dropdown)
      }
    suppressLangCallback = true
    spLang.adapter = adapter
    val saved = I18n.langFromPrefs(prefs())
    val idx = langs.indexOfFirst { it.first == saved }.let { if (it >= 0) it else 0 }
    spLang.setSelection(idx, false)
    suppressLangCallback = false
    spLang.onItemSelectedListener =
      object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: View?,
          position: Int,
          id: Long
        ) {
          if (suppressLangCallback) return
          val code = langs.getOrNull(position)?.first ?: "ko"
          prefs().edit().putString("flutter.lang", code).apply()
          // (개선) 선택 처리 시간을 짧게: 저장 후 UI 갱신은 다음 턴에
          Handler(Looper.getMainLooper()).post { try { applyLanguageToOpenUi() } catch (_: Throwable) {} }
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }

    // 최초 표시도 현재 언어로 반영
    applyLanguageToOpenUi()

    // 화면설정
    root.findViewById<View>(R.id.btnScreenSettings).setOnClickListener {
      showScreenSettingsOverlay()
    }

    // 색상패널
    try { root.findViewById<View>(R.id.btnColorPanel).visibility = View.GONE } catch (_: Throwable) {}

    // 중지조건(저장만: 실행 엔진과의 연결은 이후)
    val rgStop = root.findViewById<android.widget.RadioGroup>(R.id.rgStop)
    val rbInf = root.findViewById<android.widget.RadioButton>(R.id.rbInfinite)
    val rbTime = root.findViewById<android.widget.RadioButton>(R.id.rbTimeLimit)
    val rbCycle = root.findViewById<android.widget.RadioButton>(R.id.rbCycleCount)
    val tvTime = root.findViewById<TextView>(R.id.tvTimeHms)
    val tvCycles = root.findViewById<TextView>(R.id.tvCycles)

    val mode = prefs().getString("flutter.stop_mode", "infinite") ?: "infinite"
    when (mode) {
      "time" -> rbTime.isChecked = true
      "cycle" -> rbCycle.isChecked = true
      else -> rbInf.isChecked = true
    }
    fun formatHms(totalSec: Int): String {
      val sec = totalSec.coerceAtLeast(0)
      val h = sec / 3600
      val m = (sec % 3600) / 60
      val s = sec % 60
      return String.format("%02dh %02dm %02ds", h, m, s)
    }
    fun refreshStopLabels() {
      val timeSec = prefs().getInt("flutter.stop_time_sec", 0).coerceAtLeast(0)
      val cycles = prefs().getInt("flutter.stop_cycles", 1).coerceAtLeast(1)
      tvTime.text = formatHms(timeSec)
      tvCycles.text = cycles.toString()
    }
    refreshStopLabels()

    fun persistStop() {
      val selected =
        when {
          rbTime.isChecked -> "time"
          rbCycle.isChecked -> "cycle"
          else -> "infinite"
        }
      val timeSec = prefs().getInt("flutter.stop_time_sec", 0).coerceAtLeast(0)
      val cycles = prefs().getInt("flutter.stop_cycles", 1).coerceAtLeast(1)
      prefs().edit()
        .putString("flutter.stop_mode", selected)
        .putInt("flutter.stop_time_sec", timeSec)
        .putInt("flutter.stop_cycles", cycles)
        .apply()
    }

    // (중요) RadioGroup은 직계 RadioButton만 단일선택을 보장한다.
    // 현재 레이아웃은 LinearLayout 안에 RadioButton이 있어, 직접 단일선택을 강제한다.
    fun checkOnly(target: android.widget.RadioButton) {
      val list = listOf(rbInf, rbTime, rbCycle)
      for (b in list) b.isChecked = (b === target)
      persistStop()
    }
    rbInf.setOnCheckedChangeListener { _, isChecked -> if (isChecked) checkOnly(rbInf) }
    rbTime.setOnCheckedChangeListener { _, isChecked -> if (isChecked) checkOnly(rbTime) }
    rbCycle.setOnCheckedChangeListener { _, isChecked -> if (isChecked) checkOnly(rbCycle) }

    tvTime.setOnClickListener {
      rbTime.isChecked = true
      showStopTimeOverlay { refreshStopLabels(); persistStop() }
    }
    tvCycles.setOnClickListener {
      rbCycle.isChecked = true
      showStopCyclesOverlay { refreshStopLabels(); persistStop() }
    }

    // 메크로 저장/열기(파일)
    root.findViewById<View>(R.id.btnMacroSave).setOnClickListener { showMacroSaveOverlay() }
    // (요청) 메크로 파일 열기: 설정창을 닫고 진행
    root.findViewById<View>(R.id.btnMacroOpen).setOnClickListener {
      try { removeColorPanel() } catch (_: Throwable) {}
      showMacroOpenOverlay()
    }

    // 접근성 설정(안내 Activity)
    root.findViewById<View>(R.id.btnAccessibilitySettings).setOnClickListener {
      try {
        startActivity(Intent(this, AccessibilityIntroActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      } catch (_: Throwable) {
        try {
          startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
      }
    }

    // 화면 공유(캡처) 권한 설정
    root.findViewById<View>(R.id.btnScreenSharePermissionSettings).setOnClickListener {
      // 앱(Flutter Activity)으로 이동해 MediaProjection 권한을 다시 요청
      try { removeColorPanel() } catch (_: Throwable) {}
      // (중요) 접근성 메뉴툴바(TYPE_ACCESSIBILITY_OVERLAY)가 권한 UI 위에 뜰 수 있어 먼저 숨김
      try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
      // 앱으로 복귀하면 툴바를 다시 복구(폴백)
      try { prefs().edit().putBoolean("flutter.restore_toolbar_after_share", true).apply() } catch (_: Throwable) {}
      requestRestartFromOverlay()
    }

    // 닫기 (설정창 내 "종료" 버튼은 제거됨)
    root.findViewById<View>(R.id.btnClose).setOnClickListener { removeColorPanel() }

    val usable = getUsableRectPx()
    // 요구사항: 메뉴바 설정창(메인 패널) 높이를 7줄 정도 줄이기
    // 요구사항: 메뉴바 설정창을 2줄 더 크게(= 줄임을 2줄 덜 함)
    val shrinkPx = dpToPx(240f) // 약 24dp * 10 (1줄 더 줄임)
    // (요청) 설정창(메인 패널) 가로/세로 5% 축소
    val maxH0 =
      ((usable.height() * 0.92f).roundToInt() - shrinkPx)
        .coerceAtLeast(dpToPx(260f))
        .coerceAtMost(usable.height())
    val maxH = (maxH0 * 0.95f).roundToInt().coerceAtMost(usable.height()).coerceAtLeast(dpToPx(260f))
    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      // 화면에 다 안 들어오면 스크롤로 보기 위해 높이 제한
      maxH,
      if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    lp.softInputMode =
      WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
    panelLp = lp

    updatePanelLayout()

    try {
      wm?.addView(root, lp)
    } catch (_: Throwable) {
      panelRoot = null
      panelLp = null
      popToolbarHiddenByModalInternal()
      return
    }

    // (중요) 최초 addView 직후에는 width/height 측정 전이라 중앙정렬이 틀어질 수 있어
    // 레이아웃 이후 한 번 더 중앙정렬한다.
    try {
      root.post {
        try {
          updatePanelLayout()
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    applyOverlayOpacity()
  }

  private fun applyLanguageToScreenSettingsIfOpen() {
    val root = screenSettingsRoot ?: return
    val lang = I18n.langFromPrefs(prefs())
    try { root.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
    try { root.findViewById<TextView>(R.id.tvTitle).text = I18n.screenSettingsTitle(lang) } catch (_: Throwable) {}
    try { root.findViewById<CheckBox>(R.id.cbTouchViz).text = I18n.touchViz(lang) } catch (_: Throwable) {}
    try { root.findViewById<TextView>(R.id.tvTouchVizHint).text = I18n.touchVizHint(lang) } catch (_: Throwable) {}
    try { root.findViewById<CheckBox>(R.id.cbLogView).text = I18n.logView(lang) } catch (_: Throwable) {}
    try { root.findViewById<CheckBox>(R.id.cbDebugAdb).text = I18n.debugging(lang) } catch (_: Throwable) {}
    try { root.findViewById<android.widget.Button>(R.id.btnClose).text = I18n.close(lang) } catch (_: Throwable) {}

    // 현재 상태값 기준으로 라벨 갱신(문구만)
    try {
      val op = overlayOpacityPercent.coerceIn(30, 150)
      root.findViewById<TextView>(R.id.tvToolbarOpacity).text = I18n.toolbarOpacity(lang, op)
    } catch (_: Throwable) {}
    try {
      val sx0 = toolbarScaleXPercent.coerceIn(50, 200)
      root.findViewById<TextView>(R.id.tvToolbarScale).text = I18n.toolbarScaleX(lang, sx0)
    } catch (_: Throwable) {}
    try {
      val delta0 = (markerScalePercent.coerceIn(50, 200) - 100).coerceIn(-50, 100)
      root.findViewById<TextView>(R.id.tvObjectScale).text = I18n.objectScale(lang, delta0)
    } catch (_: Throwable) {}
    try {
      val p0 = clickPressMsGlobal.coerceIn(10, 500)
      root.findViewById<TextView>(R.id.tvClickPressMs).text = I18n.clickPressMs(lang, p0)
    } catch (_: Throwable) {}
    try {
      val prob0 = execProbabilityPercent.coerceIn(0, 100)
      root.findViewById<TextView>(R.id.tvExecProbability).text = I18n.execProbability(lang, prob0)
    } catch (_: Throwable) {}
    try {
      val ms0 = imageVerifyThirdIntervalMs.coerceIn(0, 1000)
      root.findViewById<TextView>(R.id.tvImageVerifyInterval).text = I18n.imageVerifyInterval(lang, ms0)
    } catch (_: Throwable) {}
  }

  private fun applyLanguageToMacroOverlaysIfOpen() {
    val lang = I18n.langFromPrefs(prefs())
    // 저장창
    macroSaveRoot?.let { root ->
      try { root.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
      try { root.findViewById<TextView>(R.id.tvTitle).text = I18n.macroSave(lang) } catch (_: Throwable) {}
      try { root.findViewById<TextView>(R.id.tvHint).text = I18n.macroSaveHint(lang) } catch (_: Throwable) {}
      try { root.findViewById<TextView>(R.id.tvFilenameLabel).text = I18n.filenameLabel(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnClose).text = I18n.close(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnSave).text = I18n.save(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.EditText>(R.id.etFilename).hint = "atx_macro.jws" } catch (_: Throwable) {}
    }
    // 열기창
    macroOpenRoot?.let { root ->
      try { root.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
      try { root.findViewById<TextView>(R.id.tvTitle).text = I18n.macroOpen(lang) } catch (_: Throwable) {}
      try { root.findViewById<TextView>(R.id.tvHint).text = I18n.macroOpenHint(lang) } catch (_: Throwable) {}
      try { root.findViewById<TextView>(R.id.tvFavHint).text = I18n.macroFavHint(lang) } catch (_: Throwable) {}
      try { root.findViewById<android.widget.Button>(R.id.btnClose).text = I18n.close(lang) } catch (_: Throwable) {}
    }
  }

  private fun showStopTimeOverlay(onApplied: () -> Unit) {
    if (stopTimeRoot != null) return
    pushToolbarHiddenByModalInternal()
    val wmLocal = wm ?: return
    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_stop_time, null)
    stopTimeRoot = root
    makeWindowOpaque(root)

    val lang = I18n.langFromPrefs(prefs())
    try { root.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
    try { root.findViewById<TextView>(R.id.tvTitle).text = I18n.stopTimeTitle(lang) } catch (_: Throwable) {}
    try { root.findViewById<android.widget.Button>(R.id.btnCancel).text = I18n.cancel(lang) } catch (_: Throwable) {}
    try { root.findViewById<android.widget.Button>(R.id.btnApply).text = I18n.apply(lang) } catch (_: Throwable) {}

    val etH = root.findViewById<EditText>(R.id.etH)
    val etM = root.findViewById<EditText>(R.id.etM)
    val etS = root.findViewById<EditText>(R.id.etS)
    val cur = prefs().getInt("flutter.stop_time_sec", 0).coerceAtLeast(0)
    etH.setText((cur / 3600).toString())
    etM.setText(((cur % 3600) / 60).toString())
    etS.setText((cur % 60).toString())

    root.findViewById<View>(R.id.btnCancel).setOnClickListener { removeStopTimeOverlay() }
    root.findViewById<View>(R.id.btnApply).setOnClickListener {
      val h = etH.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 999) ?: 0
      val m = etM.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
      val s = etS.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
      val sec = (h * 3600 + m * 60 + s).coerceAtLeast(0)
      prefs().edit().putInt("flutter.stop_time_sec", sec).apply()
      removeStopTimeOverlay()
      onApplied()
    }

    val usable = getUsableRectPx()
    val maxH = (usable.height() * 0.60f).roundToInt().coerceAtLeast(dpToPx(220f)).coerceAtMost(usable.height())
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        maxH,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
      )
    lp.gravity = Gravity.TOP or Gravity.START
    stopTimeLp = lp
    // 중앙 정렬
    val wGuess = dpToPx(320f)
    val hGuess = dpToPx(260f)
    lp.x = (usable.centerX() - wGuess / 2).coerceIn(usable.left, (usable.right - wGuess).coerceAtLeast(usable.left))
    lp.y = (usable.centerY() - hGuess / 2).coerceIn(usable.top, (usable.bottom - hGuess).coerceAtLeast(usable.top))
    try {
      wmLocal.addView(root, lp)
    } catch (_: Throwable) {
      stopTimeRoot = null
      stopTimeLp = null
      popToolbarHiddenByModalInternal()
    }
  }

  private fun removeStopTimeOverlay() {
    val root = stopTimeRoot ?: return
    stopTimeRoot = null
    stopTimeLp = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
  }

  private fun showStopCyclesOverlay(onApplied: () -> Unit) {
    if (stopCyclesRoot != null) return
    pushToolbarHiddenByModalInternal()
    val wmLocal = wm ?: return
    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_stop_cycles, null)
    stopCyclesRoot = root
    makeWindowOpaque(root)

    val lang = I18n.langFromPrefs(prefs())
    try { root.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
    try { root.findViewById<TextView>(R.id.tvTitle).text = I18n.stopCyclesTitle(lang) } catch (_: Throwable) {}
    try { root.findViewById<android.widget.Button>(R.id.btnCancel).text = I18n.cancel(lang) } catch (_: Throwable) {}
    try { root.findViewById<android.widget.Button>(R.id.btnApply).text = I18n.apply(lang) } catch (_: Throwable) {}

    val et = root.findViewById<EditText>(R.id.etCycles)
    try { et.hint = I18n.cyclesHint(lang) } catch (_: Throwable) {}
    val cur = prefs().getInt("flutter.stop_cycles", 1).coerceAtLeast(1)
    et.setText(cur.toString())

    root.findViewById<View>(R.id.btnCancel).setOnClickListener { removeStopCyclesOverlay() }
    root.findViewById<View>(R.id.btnApply).setOnClickListener {
      val v = et.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
      prefs().edit().putInt("flutter.stop_cycles", v).apply()
      removeStopCyclesOverlay()
      onApplied()
    }

    val usable = getUsableRectPx()
    val maxH = (usable.height() * 0.50f).roundToInt().coerceAtLeast(dpToPx(200f)).coerceAtMost(usable.height())
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        maxH,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
      )
    lp.gravity = Gravity.TOP or Gravity.START
    stopCyclesLp = lp
    val wGuess = dpToPx(320f)
    val hGuess = dpToPx(220f)
    lp.x = (usable.centerX() - wGuess / 2).coerceIn(usable.left, (usable.right - wGuess).coerceAtLeast(usable.left))
    lp.y = (usable.centerY() - hGuess / 2).coerceIn(usable.top, (usable.bottom - hGuess).coerceAtLeast(usable.top))
    try {
      wmLocal.addView(root, lp)
    } catch (_: Throwable) {
      stopCyclesRoot = null
      stopCyclesLp = null
      popToolbarHiddenByModalInternal()
    }
  }

  private fun removeStopCyclesOverlay() {
    val root = stopCyclesRoot ?: return
    stopCyclesRoot = null
    stopCyclesLp = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
  }

  private fun showColorPanel() {
    // 기존 색상 패널(overlay_color_picker) 오픈
    if (colorPanelRoot != null) return
    if (macroRunning) return

    pushToolbarHiddenByModalInternal()

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_color_picker, null)
    colorPanelRoot = root
    makeWindowOpaque(root)
    colorPanelDraggedByUser = false

    val swatch = root.findViewById<View>(R.id.viewSwatch)
    val tvHex = root.findViewById<TextView>(R.id.tvHex)
    val tvCoord = root.findViewById<TextView>(R.id.tvCoord)
    val cbPass = root.findViewById<CheckBox>(R.id.cbPassThrough)

    // 샘플링(sampleAtScreen)에서 실시간으로 갱신할 수 있도록 필드로 보관
    colorPickerSwatch = swatch
    colorPickerTvHex = tvHex
    colorPickerTvCoord = tvCoord
    colorPickerPassThroughCb = cbPass

    fun refreshUi() {
      val argb = lastPickedArgb
      swatch.setBackgroundColor(argb)
      val r = (argb shr 16) and 0xFF
      val g = (argb shr 8) and 0xFF
      val b = argb and 0xFF
      tvHex.text = String.format("#%02X%02X%02X", r, g, b)
      val x = lastSampleScreenX
      val y = lastSampleScreenY
      tvCoord.text = if (x >= 0 && y >= 0) "(${x},${y})" else "(x,y)"
      cbPass.text = if (passThroughEnabled) "통과\nON" else "통과\nOFF"
      cbPass.isChecked = passThroughEnabled
    }

    cbPass.setOnCheckedChangeListener { _, isChecked ->
      passThroughEnabled = isChecked
      if (isChecked) {
        removeTouchOverlay()
        updateNotification("밑에앱 터치: ON")
      } else {
        showTouchOverlay()
        updateNotification("밑에앱 터치: OFF")
      }
      refreshUi()
    }

    root.findViewById<View>(R.id.btnRestart).setOnClickListener { requestRestartFromOverlay() }
    root.findViewById<View>(R.id.btnPickOnce).setOnClickListener {
      pickOnceArmed = true
      showTouchOverlay()
      updateNotification("한 번만 색상 선택: 화면을 탭하세요")
    }
    root.findViewById<View>(R.id.btnCopy).setOnClickListener {
      val hex = tvHex.text?.toString() ?: return@setOnClickListener
      val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
      cm.setPrimaryClip(android.content.ClipData.newPlainText("color", hex))
      updateNotification("복사됨: $hex")
    }
    root.findViewById<View>(R.id.btnStop).setOnClickListener {
      // 요구사항: 색상창의 "종료"는 색상창만 닫기
      removeColorPickerOverlay()
    }
    root.findViewById<View>(R.id.btnCoordInput).setOnClickListener {
      showCoordInputOverlay()
    }

    // 드래그 핸들로 이동
    val dragHandle = root.findViewById<View>(R.id.dragHandle)
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      )
    lp.gravity = Gravity.TOP or Gravity.START
    colorPanelLp = lp
    // 초기 위치: 툴바 아래(추가 전이므로 updateViewLayout 호출 금지)
    run {
      val tlp = toolbarLp
      val usable = getUsableRectPx()
      val toolbarX = tlp?.x ?: usable.left
      val toolbarY = tlp?.y ?: usable.top
      val toolbarH = (toolbarRoot?.height ?: toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
      lp.x = toolbarX
      lp.y = toolbarY + toolbarH + dpToPx(6f)
      clampColorPanelToUsable()
    }

    dragHandle.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val lpLocal = colorPanelLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              // 사용자가 한 번이라도 이동했다면 자동 위치 리셋 금지
              colorPanelDraggedByUser = true
              clampColorPanelToUsable()
              try {
                wm?.updateViewLayout(root, lpLocal)
              } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    try {
      wm?.addView(root, lp)
    } catch (_: Throwable) {
      colorPanelRoot = null
      colorPanelLp = null
      colorPickerSwatch = null
      colorPickerTvHex = null
      colorPickerTvCoord = null
      colorPickerPassThroughCb = null
      popToolbarHiddenByModalInternal()
      return
    }

    refreshUi()
  }

  private var colorPanelRoot: View? = null
  private var colorPanelLp: WindowManager.LayoutParams? = null
  @Volatile
  private var colorPanelDraggedByUser: Boolean = false

  // 색상모듈 전용: 색/좌표 가져오기 작은 창
  private var colorModulePickerRoot: View? = null
  private var colorModulePickerLp: WindowManager.LayoutParams? = null
  private var colorModulePickerTargetIndex: Int = 0

  // 이미지모듈 전용: 템플릿 이미지 잘라 저장(사각형 + 컨트롤 패널)
  private var imageModuleRectRoot: View? = null
  private var imageModuleRectLp: WindowManager.LayoutParams? = null
  private var imageModuleSearchRectRoot: View? = null
  private var imageModuleSearchRectLp: WindowManager.LayoutParams? = null
  private var imageModulePanelRoot: View? = null
  private var imageModulePanelLp: WindowManager.LayoutParams? = null
  private var imageModulePickerTargetIndex: Int = 0
  private var imageModulePickerFileName: String = ""
  private var imageModulePickerOriginalFileName: String = ""
  @Volatile private var imagePickerPurpose: String = MarkerSettingsActivity.PICK_PURPOSE_IMAGE_MODULE
  @Volatile private var imagePickerDraftPrefix: String = "imgDraft"
  private val imagePickerSaveHandler by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var imagePickerSaveScheduled: Boolean = false
  @Volatile private var imagePickerDraftSessionId: Long = 0L

  private data class ImagePickerDraft(
    val file: String,
    val cropW: Int,
    val cropH: Int,
    val cropLeftU: Int,
    val cropTopU: Int,
    val startXU: Int,
    val startYU: Int,
    val endXU: Int,
    val endYU: Int,
  )

  private fun draftKey(idx: Int, suffix: String): String = "${imagePickerDraftPrefix}.$idx.$suffix"

  private fun bumpImagePickerDraftSession() {
    try { imagePickerDraftSessionId = Random.nextLong() } catch (_: Throwable) { imagePickerDraftSessionId = System.currentTimeMillis() }
  }

  private fun readImagePickerDraft(idx: Int): ImagePickerDraft? {
    if (idx == 0) return null
    val p = prefs()
    val s = p.getLong(draftKey(idx, "session"), Long.MIN_VALUE)
    if (s != imagePickerDraftSessionId) return null
    val file = p.getString(draftKey(idx, "file"), "") ?: ""
    val cropW = p.getInt(draftKey(idx, "cropW"), -1)
    val cropH = p.getInt(draftKey(idx, "cropH"), -1)
    val cropLeftU = p.getInt(draftKey(idx, "cropLeftU"), -1)
    val cropTopU = p.getInt(draftKey(idx, "cropTopU"), -1)
    val startXU = p.getInt(draftKey(idx, "startXU"), -1)
    val startYU = p.getInt(draftKey(idx, "startYU"), -1)
    val endXU = p.getInt(draftKey(idx, "endXU"), -1)
    val endYU = p.getInt(draftKey(idx, "endYU"), -1)
    if (file.isBlank()) return null
    if (cropW <= 0 || cropH <= 0) return null
    if (cropLeftU < 0 || cropTopU < 0) return null
    if (startXU < 0 || startYU < 0 || endXU < 0 || endYU < 0) return null
    return ImagePickerDraft(
      file = file,
      cropW = cropW,
      cropH = cropH,
      cropLeftU = cropLeftU,
      cropTopU = cropTopU,
      startXU = startXU,
      startYU = startYU,
      endXU = endXU,
      endYU = endYU,
    )
  }

  private fun schedulePersistImagePickerState(usable: Rect) {
    val idx = imageModulePickerTargetIndex
    if (idx == 0) return
    if (imagePickerSaveScheduled) return
    imagePickerSaveScheduled = true
    imagePickerSaveHandler.postDelayed(
      {
        imagePickerSaveScheduled = false
        persistImagePickerStateNow(usable)
      },
      180L
    )
  }

  private fun persistImagePickerStateNow(usable: Rect) {
    val idx = imageModulePickerTargetIndex
    if (idx == 0) return
    val file = imageModulePickerFileName.trim()
    if (file.isBlank()) return
    val rectLpNow = imageModuleRectLp ?: return
    val searchLpNow = imageModuleSearchRectLp ?: return
    val w = rectLpNow.width.coerceIn(8, 1024)
    val h = rectLpNow.height.coerceIn(8, 1024)
    val cropLeftU = (rectLpNow.x - usable.left).coerceAtLeast(0)
    val cropTopU = (rectLpNow.y - usable.top).coerceAtLeast(0)
    val startXU = (searchLpNow.x - usable.left).coerceAtLeast(0)
    val startYU = (searchLpNow.y - usable.top).coerceAtLeast(0)
    val endXU = ((searchLpNow.x + searchLpNow.width) - usable.left).coerceAtLeast(0)
    val endYU = ((searchLpNow.y + searchLpNow.height) - usable.top).coerceAtLeast(0)
    try {
      // (중요) "저장" 없이도 다시 열기에서 위치/크기를 유지하려면,
      // 마커 저장값(futter.markers)을 덮어쓰면 안 되고 임시(draft)로만 저장해야 한다.
      prefs().edit()
        .putLong(draftKey(idx, "session"), imagePickerDraftSessionId)
        .putString(draftKey(idx, "file"), file)
        .putInt(draftKey(idx, "cropW"), w)
        .putInt(draftKey(idx, "cropH"), h)
        .putInt(draftKey(idx, "cropLeftU"), cropLeftU)
        .putInt(draftKey(idx, "cropTopU"), cropTopU)
        .putInt(draftKey(idx, "startXU"), startXU)
        .putInt(draftKey(idx, "startYU"), startYU)
        .putInt(draftKey(idx, "endXU"), endXU)
        .putInt(draftKey(idx, "endYU"), endYU)
        .apply()
    } catch (_: Throwable) {}
  }

  private fun reopenMarkerSettingsAfterColorPick(targetIndex: Int, hex: String?, xU: Int, yU: Int) {
    try {
      try { dbg("reopenMarkerSettingsAfterColorPick idx=$targetIndex hex=$hex xU=$xU yU=$yU") } catch (_: Throwable) {}
      // 최신 좌표를 반영해서 설정창 좌표 표시도 맞춘다.
      val loaded = try { loadMarkersFromPrefs() } catch (_: Throwable) { mutableListOf<Marker>() }
      val m = loaded.firstOrNull { it.index == targetIndex }
      val cxU = m?.xPx ?: -1
      val cyU = m?.yPx ?: -1

      val it =
        Intent(this, MarkerSettingsActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
          putExtra(MarkerSettingsActivity.EXTRA_INDEX, targetIndex)
          if (cxU >= 0) putExtra(MarkerSettingsActivity.EXTRA_CENTER_USABLE_X, cxU)
          if (cyU >= 0) putExtra(MarkerSettingsActivity.EXTRA_CENTER_USABLE_Y, cyU)
          if (!hex.isNullOrBlank()) putExtra(MarkerSettingsActivity.EXTRA_PICK_HEX, hex)
          if (xU >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, xU)
          if (yU >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, yU)
        }
      // (중요) Android 10+에서 서비스 startActivity가 막히는 경우가 있어,
      // 사용자 버튼(보내기/닫기) 이벤트에서 PendingIntent.send()로 띄운다.
      val flags =
        PendingIntent.FLAG_UPDATE_CURRENT or
          (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
      val pi = PendingIntent.getActivity(this, 9101, it, flags)
      try {
        pi.send()
      } catch (_: Throwable) {
        // fallback
        try { startActivity(it) } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  private fun updateColorPanelLayout() {
    val root = colorPanelRoot ?: return
    val lp = colorPanelLp ?: return
    val usable = getUsableRectPx()
    // (중요) 사용자가 패널을 드래그해서 위치를 바꿨으면,
    // 이후에는 자동으로 툴바 아래로 "복귀"시키지 않는다.
    if (!colorPanelDraggedByUser) {
      val tlp = toolbarLp
      val toolbarX = tlp?.x ?: usable.left
      val toolbarY = tlp?.y ?: usable.top
      val toolbarH = (toolbarRoot?.height ?: toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
      lp.x = toolbarX
      lp.y = toolbarY + toolbarH + dpToPx(6f)
    }
    clampColorPanelToUsable()
    try {
      wm?.updateViewLayout(root, lp)
    } catch (_: Throwable) {}
  }

  private fun clampColorPanelToUsable() {
    val root = colorPanelRoot ?: return
    val lp = colorPanelLp ?: return
    val usable = getUsableRectPx()
    val w = if (root.width > 0) root.width else (root.measuredWidth.takeIf { it > 0 } ?: dpToPx(320f))
    val h = if (root.height > 0) root.height else (root.measuredHeight.takeIf { it > 0 } ?: dpToPx(56f))
    lp.x = lp.x.coerceIn(usable.left, (usable.right - w).coerceAtLeast(usable.left))
    lp.y = lp.y.coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))
  }

  private fun showColorModulePickerOverlay(targetIndex: Int) {
    val wmLocal = wm ?: return
    if (colorModulePickerRoot != null) return
    if (macroRunning) return

    colorModulePickerTargetIndex = targetIndex
    try { dbg("showColorModulePickerOverlay enter idx=$targetIndex") } catch (_: Throwable) {}

    // (요청) 색상가져오기 중에는 통과 OFF + 터치오버레이로 좌표/색상 선택
    passThroughEnabled = false
    pickOnceArmed = true
    try { showTouchOverlay() } catch (_: Throwable) {}
    try { updatePassThroughCaption() } catch (_: Throwable) {}
    try { dbg("showColorModulePickerOverlay passThrough=false pickOnce=true touchOverlayShown=${touchTopRoot != null || touchBottomRoot != null}") } catch (_: Throwable) {}

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_color_module_picker, null)
    colorModulePickerRoot = root
    makeWindowOpaque(root)

    val swatch = root.findViewById<View>(R.id.viewSwatch)
    val tvHex = root.findViewById<TextView>(R.id.tvHex)
    val tvCoord = root.findViewById<TextView>(R.id.tvCoord)

    // sampleAtScreen()의 갱신 로직을 그대로 활용
    colorPickerSwatch = swatch
    colorPickerTvHex = tvHex
    colorPickerTvCoord = tvCoord
    colorPickerPassThroughCb = null

    fun refreshUi() {
      try { refreshColorPickerUiIfOpen() } catch (_: Throwable) {}
    }

    root.findViewById<View>(R.id.btnClose).setOnClickListener {
      // 닫기: 통과 ON 복구 + 창 닫기 + 설정창 다시 표시
      try { dbg("colorModulePicker btnClose") } catch (_: Throwable) {}
      val targetIdx = colorModulePickerTargetIndex
      pickOnceArmed = false
      passThroughEnabled = true
      try { removeTouchOverlay() } catch (_: Throwable) {}
      try { updatePassThroughCaption() } catch (_: Throwable) {}
      removeColorModulePickerOverlay()
      // (요청) 설정창은 Activity를 종료하지 않고 숨김 처리하므로, 브로드캐스트로 "복구"만 한다.
      try {
        val it = Intent(MarkerSettingsActivity.ACTION_COLOR_MODULE_PICK_RESULT)
          .setPackage(packageName)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, targetIdx)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, -1)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, -1)
        sendBroadcast(it)
      } catch (_: Throwable) {}
      // (중요) 숨김 상태/백그라운드에선 브로드캐스트만으로 설정창이 안 보일 수 있어,
      // PendingIntent로 설정창을 다시 앞으로 띄운다.
      try {
        reopenMarkerSettingsAfterColorPick(targetIdx, hex = null, xU = -1, yU = -1)
      } catch (_: Throwable) {}
    }

    root.findViewById<View>(R.id.btnSend).setOnClickListener {
      try { dbg("colorModulePicker btnSend sample=($lastSampleScreenX,$lastSampleScreenY)") } catch (_: Throwable) {}
      val targetIdx = colorModulePickerTargetIndex
      val usable = getUsableRectPx()
      val xS = lastSampleScreenX
      val yS = lastSampleScreenY
      val xU =
        if (xS >= 0) (xS - usable.left).coerceIn(0, (usable.width() - 1).coerceAtLeast(0)) else -1
      val yU =
        if (yS >= 0) (yS - usable.top).coerceIn(0, (usable.height() - 1).coerceAtLeast(0)) else -1
      val argb = lastPickedArgb
      val r = (argb shr 16) and 0xFF
      val g = (argb shr 8) and 0xFF
      val b = argb and 0xFF
      val hex = String.format("#%02X%02X%02X", r, g, b)

      // 보내기: 통과 ON 복구 + 창 닫기 + 설정창 다시 표시(선택값 전달)
      pickOnceArmed = false
      passThroughEnabled = true
      try { removeTouchOverlay() } catch (_: Throwable) {}
      try { updatePassThroughCaption() } catch (_: Throwable) {}
      removeColorModulePickerOverlay()
      // (요청) 설정창은 Activity를 종료하지 않고 숨김 처리하므로, 브로드캐스트로 값 전달 + 복구한다.
      try {
        val it = Intent(MarkerSettingsActivity.ACTION_COLOR_MODULE_PICK_RESULT)
          .setPackage(packageName)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, targetIdx)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_HEX, hex)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, xU)
          .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, yU)
        sendBroadcast(it)
      } catch (_: Throwable) {}
      // (중요) 숨김 상태/백그라운드에선 브로드캐스트만으로 설정창이 안 보일 수 있어,
      // PendingIntent로 설정창을 다시 앞으로 띄운다.
      try {
        reopenMarkerSettingsAfterColorPick(targetIdx, hex = hex, xU = xU, yU = yU)
      } catch (_: Throwable) {}
    }

    // 사용자가 다시 선택하고 싶을 때: 창을 탭하면 1회 선택 모드를 다시 켠다.
    try {
      root.setOnClickListener {
        try {
          pickOnceArmed = true
          showTouchOverlay()
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    // 드래그 핸들로 이동
    val dragHandle = root.findViewById<View>(R.id.dragHandle)
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      )
    lp.gravity = Gravity.TOP or Gravity.START
    colorModulePickerLp = lp

    // 초기 위치: 툴바 아래
    run {
      val tlp = toolbarLp
      val usable0 = getUsableRectPx()
      val toolbarX = tlp?.x ?: usable0.left
      val toolbarY = tlp?.y ?: usable0.top
      val toolbarH = (toolbarRoot?.height ?: toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
      lp.x = toolbarX
      lp.y = toolbarY + toolbarH + dpToPx(8f)
    }

    dragHandle.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val lpLocal = colorModulePickerLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampColorModulePickerToUsable()
              try { wm?.updateViewLayout(root, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    try {
      wmLocal.addView(root, lp)
    } catch (_: Throwable) {
      colorModulePickerRoot = null
      colorModulePickerLp = null
      colorPickerSwatch = null
      colorPickerTvHex = null
      colorPickerTvCoord = null
      return
    }

    clampColorModulePickerToUsable()
    refreshUi()
  }

  // -------- solo verify preClick 좌표 선택(50x50 이동 사각형) --------
  private var soloPreClickPickerTargetIndex: Int = 0
  private var soloPreClickPickerPanelRoot: View? = null
  private var soloPreClickPickerPanelLp: WindowManager.LayoutParams? = null
  private var soloPreClickPickerSquareRoot: View? = null
  private var soloPreClickPickerSquareLp: WindowManager.LayoutParams? = null

  private fun removeSoloVerifyPreClickPickerOverlay() {
    val wmLocal = wm
    try {
      soloPreClickPickerPanelRoot?.let { wmLocal?.removeView(it) }
    } catch (_: Throwable) {}
    try {
      soloPreClickPickerSquareRoot?.let { wmLocal?.removeView(it) }
    } catch (_: Throwable) {}
    soloPreClickPickerPanelRoot = null
    soloPreClickPickerPanelLp = null
    soloPreClickPickerSquareRoot = null
    soloPreClickPickerSquareLp = null
    soloPreClickPickerTargetIndex = 0
  }

  private fun showSoloVerifyPreClickPickerOverlay(targetIndex: Int, startXU: Int, startYU: Int) {
    val wmLocal = wm ?: return
    if (soloPreClickPickerPanelRoot != null || soloPreClickPickerSquareRoot != null) return
    if (macroRunning) return

    soloPreClickPickerTargetIndex = targetIndex

    val lang = I18n.langFromPrefs(prefs())
    val screen = getScreenSize()
    val wS = screen.width.coerceAtLeast(1)
    val hS = screen.height.coerceAtLeast(1)
    val usable = getUsableRectPx()
    // startXU/startYU는 "usable 기준 좌표"이며, 네비바/컷아웃까지 포함하려면 음수/범위초과도 가능하다.
    // 단, (-1,-1)은 "미선택/취소"로 취급해서 기본값(usable 중앙)으로 잡는다.
    val hasStart = !(startXU == -1 && startYU == -1)
    val xU0 = if (hasStart) startXU else (usable.width() / 2)
    val yU0 = if (hasStart) startYU else (usable.height() / 2)

    // (요청) 50x50은 dp가 아니라 "진짜 50px" 고정
    val squareSize = 50

    // 1) 사각형(드래그) 오버레이
    val square =
      FrameLayout(this).apply {
        val d =
          android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#33000000")) // semi transparent
            setStroke(dpToPx(2f).coerceAtLeast(2), Color.parseColor("#EF4444"))
          }
        background = d
      }
    soloPreClickPickerSquareRoot = square
    val squareLp =
      WindowManager.LayoutParams(
        squareSize,
        squareSize,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
          layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
      }
    soloPreClickPickerSquareLp = squareLp

    fun clampSquare() {
      val lp = soloPreClickPickerSquareLp ?: return
      // (요청) 화면 전체(네비바/컷아웃 포함) 끝까지 이동 가능하게
      lp.x = lp.x.coerceIn(0, (wS - squareSize).coerceAtLeast(0))
      lp.y = lp.y.coerceIn(0, (hS - squareSize).coerceAtLeast(0))
    }

    fun centerUsable(): Pair<Int, Int> {
      val lp = soloPreClickPickerSquareLp ?: return Pair(0, 0)
      val cxS = (lp.x + squareSize / 2).coerceIn(0, (wS - 1).coerceAtLeast(0))
      val cyS = (lp.y + squareSize / 2).coerceIn(0, (hS - 1).coerceAtLeast(0))
      // 반환값은 기존 호환을 위해 "usable 기준"으로 유지하되, 음수/범위초과도 허용한다.
      val cxU = (cxS - usable.left)
      val cyU = (cyS - usable.top)
      return Pair(cxU, cyU)
    }

    // 초기 위치: 선택 좌표(usable center) 기준
    squareLp.x = (usable.left + xU0 - squareSize / 2)
    squareLp.y = (usable.top + yU0 - squareSize / 2)
    clampSquare()

    square.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val lp = soloPreClickPickerSquareLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lp.x
              startY = lp.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lp.x = startX + dx
              lp.y = startY + dy
              clampSquare()
              try { wmLocal.updateViewLayout(square, lp) } catch (_: Throwable) {}
              try {
                val p = centerUsable()
                soloPreClickPickerPanelRoot?.findViewById<TextView>(android.R.id.text2)?.text = "x=${p.first}, y=${p.second}"
              } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    // 2) 패널 오버레이(넘기기/닫기)
    val panel =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      }
    soloPreClickPickerPanelRoot = panel

    val tvTitle =
      TextView(this).apply {
        text = I18n.soloPreClickLabel(lang)
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 14f
        gravity = Gravity.CENTER_HORIZONTAL
        textAlignment = View.TEXT_ALIGNMENT_CENTER
      }
    val tvCoord =
      TextView(this).apply {
        id = android.R.id.text2
        setTextColor(Color.parseColor("#111827"))
        textSize = 13f
        val p = centerUsable()
        text = "x=${p.first}, y=${p.second}"
        setPadding(0, dpToPx(6f), 0, dpToPx(8f))
        gravity = Gravity.CENTER_HORIZONTAL
        textAlignment = View.TEXT_ALIGNMENT_CENTER
      }
    val rowCoord =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    val btnCenter =
      Button(this).apply {
        isAllCaps = false
        text = I18n.center(lang)
        setOnClickListener {
          val lp = soloPreClickPickerSquareLp ?: return@setOnClickListener
          // (요청) 클릭 시 사각형을 "화면 정중앙"으로 이동
          lp.x = (wS / 2) - (squareSize / 2)
          lp.y = (hS / 2) - (squareSize / 2)
          clampSquare()
          try { wmLocal.updateViewLayout(square, lp) } catch (_: Throwable) {}
          try {
            val p = centerUsable()
            soloPreClickPickerPanelRoot?.findViewById<TextView>(android.R.id.text2)?.text = "x=${p.first}, y=${p.second}"
          } catch (_: Throwable) {}
        }
      }
    try {
      rowCoord.addView(
        tvCoord,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      )
      rowCoord.addView(btnCenter)
    } catch (_: Throwable) {
      // fallback: 그냥 순서대로 추가(레이아웃 파라미터 실패 대비)
      try { rowCoord.addView(tvCoord) } catch (_: Throwable) {}
      try { rowCoord.addView(btnCenter) } catch (_: Throwable) {}
    }
    val rowBtns =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
      }
    val btnClose =
      Button(this).apply {
        // (보강) 사용자가 "닫기"를 적용으로 오해할 수 있어 명확히 "취소"로 표기
        text = I18n.cancel(lang)
        setOnClickListener {
          val idx = soloPreClickPickerTargetIndex
          removeSoloVerifyPreClickPickerOverlay()
          try {
            val it = Intent(MarkerSettingsActivity.ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT)
              .setPackage(packageName)
              .putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, idx)
              .putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, -1)
              .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, -1)
            sendBroadcast(it)
          } catch (_: Throwable) {}
          try {
            reopenMarkerSettingsAfterSoloPreClickPick(idx, xU = -1, yU = -1)
          } catch (_: Throwable) {}
        }
      }
    val btnNext =
      Button(this).apply {
        text = I18n.select(lang)
        setOnClickListener {
          val idx = soloPreClickPickerTargetIndex
          val p = centerUsable()
          removeSoloVerifyPreClickPickerOverlay()
          try {
            val it = Intent(MarkerSettingsActivity.ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT)
              .setPackage(packageName)
              .putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, idx)
              .putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, p.first)
              .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, p.second)
            sendBroadcast(it)
          } catch (_: Throwable) {}
          try {
            reopenMarkerSettingsAfterSoloPreClickPick(idx, xU = p.first, yU = p.second)
          } catch (_: Throwable) {}
        }
      }
    rowBtns.addView(btnClose)
    rowBtns.addView(btnNext)

    panel.addView(tvTitle)
    panel.addView(rowCoord)
    panel.addView(rowBtns)

    val panelLp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 초기 위치: 툴바 아래
        val tlp = toolbarLp
        val toolbarX = tlp?.x ?: usable.left
        val toolbarY = tlp?.y ?: usable.top
        val toolbarH = (toolbarRoot?.height ?: toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
        x = toolbarX
        y = toolbarY + toolbarH + dpToPx(8f)
        if (Build.VERSION.SDK_INT >= 28) {
          layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
      }
    soloPreClickPickerPanelLp = panelLp

    // 패널 드래그(상단 타이틀 잡기)
    tvTitle.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val lp = soloPreClickPickerPanelLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lp.x
              startY = lp.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lp.x = startX + dx
              lp.y = startY + dy
              // (요청) 패널도 화면 전체로 이동 가능(대략 클램프)
              lp.x = lp.x.coerceIn(0, wS)
              lp.y = lp.y.coerceIn(0, hS)
              try { wmLocal.updateViewLayout(panel, lp) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    try {
      wmLocal.addView(square, squareLp)
      wmLocal.addView(panel, panelLp)
    } catch (_: Throwable) {
      removeSoloVerifyPreClickPickerOverlay()
      return
    }
  }

  private fun reopenMarkerSettingsAfterSoloPreClickPick(targetIndex: Int, xU: Int, yU: Int) {
    val i =
      Intent(this, MarkerSettingsActivity::class.java).apply {
        action = MarkerSettingsActivity.ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(MarkerSettingsActivity.EXTRA_INDEX, targetIndex)
        putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, targetIndex)
        putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, xU)
        putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, yU)
      }
    try { startActivity(i) } catch (_: Throwable) {}
  }

  private fun clampColorModulePickerToUsable() {
    val root = colorModulePickerRoot ?: return
    val lp = colorModulePickerLp ?: return
    val usable = getUsableRectPx()
    val w = if (root.width > 0) root.width else (root.measuredWidth.takeIf { it > 0 } ?: dpToPx(260f))
    val h = if (root.height > 0) root.height else (root.measuredHeight.takeIf { it > 0 } ?: dpToPx(120f))
    lp.x = lp.x.coerceIn(usable.left, (usable.right - w).coerceAtLeast(usable.left))
    lp.y = lp.y.coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))
  }

  private fun removeColorModulePickerOverlay() {
    val root = colorModulePickerRoot ?: return
    colorModulePickerRoot = null
    colorModulePickerLp = null
    colorModulePickerTargetIndex = 0
    try { pickOnceArmed = false } catch (_: Throwable) {}
    // sample ui 연결 해제
    colorPickerSwatch = null
    colorPickerTvHex = null
    colorPickerTvCoord = null
    colorPickerPassThroughCb = null
    try { wm?.removeView(root) } catch (_: Throwable) {}
  }

  private fun showImageModulePickerOverlays(targetIndex: Int, preset: ImagePickerPreset?) {
    val wmLocal = wm ?: return
    if (imageModuleRectRoot != null || imageModuleSearchRectRoot != null || imageModulePanelRoot != null) return
    if (macroRunning) return
    if (!captureReady) return

    imageModulePickerTargetIndex = targetIndex
    try { dbg("showImageModulePickerOverlays enter idx=$targetIndex") } catch (_: Throwable) {}

    // (요청) 이미지 가져오기 중 접근성 메뉴툴바 숨김
    try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
    pushToolbarHiddenByModalInternal()

    val screen = getScreenSize()
    val usable = getUsableRectPx()
    val isLandscape = screen.width > screen.height

    // 초기 중심: 해당 마커 화면 좌표
    val loaded = try { loadMarkersFromPrefs() } catch (_: Throwable) { mutableListOf<Marker>() }
    val m = loaded.firstOrNull { it.index == targetIndex }
    val cxS = (m?.xPx ?: usable.centerX()).coerceIn(0, (screen.width - 1).coerceAtLeast(0))
    val cyS = (m?.yPx ?: usable.centerY()).coerceIn(0, (screen.height - 1).coerceAtLeast(0))

    val purpose = imagePickerPurpose
    val forSoloVerify = (purpose == MarkerSettingsActivity.PICK_PURPOSE_SOLO_VERIFY)
    val markerFile = (if (forSoloVerify) (m?.soloVerifyTemplateFile ?: "") else (m?.imageTemplateFile ?: "")).trim()

    val presetFile = preset?.file?.trim().orEmpty()
    val isEdit = presetFile.isNotBlank() || markerFile.isNotBlank()
    imageModulePickerOriginalFileName = markerFile
    // draft가 있으면 draft 파일을 우선 사용(저장 전 임시 상태)
    val draft = try { readImagePickerDraft(imageModulePickerTargetIndex) } catch (_: Throwable) { null }
    imageModulePickerFileName =
      (draft?.file ?: (if (presetFile.isNotBlank()) presetFile else markerFile)).trim()

    // 사각형(크롭 영역) 오버레이
    val rectView =
      View(this).apply {
        isClickable = true
        background =
          android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#00000000"))
            setStroke(dpToPx(2f), Color.parseColor("#EF4444"))
          }
      }
    // (중요) 저장 안 한 수정값은 "draft"로만 유지하고, 저장/불러오기에는 섞이지 않게 한다.
    // 픽커 재진입 시에는 draft가 있으면 draft를 우선 적용한다.
    val cropW0 = (draft?.cropW ?: preset?.cropW ?: (if (forSoloVerify) (m?.soloVerifyW ?: 128) else (m?.imageW ?: 128))).coerceIn(8, 1024)
    val cropH0 = (draft?.cropH ?: preset?.cropH ?: (if (forSoloVerify) (m?.soloVerifyH ?: 128) else (m?.imageH ?: 128))).coerceIn(8, 1024)
    val cropLeftU0 = (draft?.cropLeftU ?: preset?.cropLeftU ?: (if (forSoloVerify) (m?.soloVerifyCropLeftXPx ?: -1) else (m?.imageCropLeftXPx ?: -1)))
    val cropTopU0 = (draft?.cropTopU ?: preset?.cropTopU ?: (if (forSoloVerify) (m?.soloVerifyCropTopYPx ?: -1) else (m?.imageCropTopYPx ?: -1)))
    val rectLp =
      WindowManager.LayoutParams(
        cropW0,
        cropH0,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x =
          if (cropLeftU0 >= 0) (usable.left + cropLeftU0) else (cxS - width / 2)
        y =
          if (cropTopU0 >= 0) (usable.top + cropTopU0) else (cyS - height / 2)
      }

    rectView.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val lpLocal = imageModuleRectLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampImageModuleRectToScreen()
              try { wmLocal.updateViewLayout(v, lpLocal) } catch (_: Throwable) {}
              // (개선) 저장 버튼 없이도 수정 값이 유지되도록 디바운스 저장
              schedulePersistImagePickerState(usable)
              return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
              schedulePersistImagePickerState(usable)
              return true
            }
            else -> return false
          }
        }
      }
    )

    // 사각형(검색 영역: 시작~종료) 오버레이 + 중앙 라벨(투명 텍스트)
    val searchRectView =
      android.widget.FrameLayout(this).apply {
        isClickable = true
        background =
          android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#00000000"))
            setStroke(dpToPx(2f), Color.parseColor("#3B82F6"))
          }
        addView(
          TextView(this@ScreenCaptureService).apply {
            text = "검색영역"
            setTextColor(Color.parseColor("#80FFFFFF")) // 반투명
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            // 글자 가독성(검은 그림자)
            try { setShadowLayer(2f, 0f, 0f, Color.parseColor("#80000000")) } catch (_: Throwable) {}
            isClickable = false
            isFocusable = false
            try { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } catch (_: Throwable) {}
            layoutParams =
              android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
              ).apply {
                gravity = Gravity.CENTER
              }
          }
        )
      }
    val sxU0 = (draft?.startXU ?: preset?.startXU ?: (if (forSoloVerify) (m?.soloVerifyStartXPx ?: -1) else (m?.imageStartXPx ?: -1)))
    val syU0 = (draft?.startYU ?: preset?.startYU ?: (if (forSoloVerify) (m?.soloVerifyStartYPx ?: -1) else (m?.imageStartYPx ?: -1)))
    val exU0 = (draft?.endXU ?: preset?.endXU ?: (if (forSoloVerify) (m?.soloVerifyEndXPx ?: -1) else (m?.imageEndXPx ?: -1)))
    val eyU0 = (draft?.endYU ?: preset?.endYU ?: (if (forSoloVerify) (m?.soloVerifyEndYPx ?: -1) else (m?.imageEndYPx ?: -1)))
    val sLeftU = if (sxU0 >= 0 && exU0 >= 0) minOf(sxU0, exU0) else -1
    val sTopU = if (syU0 >= 0 && eyU0 >= 0) minOf(syU0, eyU0) else -1
    val sW0 = if (sxU0 >= 0 && exU0 >= 0) (kotlin.math.abs(exU0 - sxU0)).coerceAtLeast(32) else 500
    val sH0 = if (syU0 >= 0 && eyU0 >= 0) (kotlin.math.abs(eyU0 - syU0)).coerceAtLeast(32) else 500
    val searchRectLp =
      WindowManager.LayoutParams(
        sW0,
        sH0,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x =
          if (sLeftU >= 0) (usable.left + sLeftU) else (cxS - width / 2).coerceAtLeast(0)
        y =
          if (sTopU >= 0) (usable.top + sTopU) else (cyS - height / 2).coerceAtLeast(0)
      }

    // (요청) 시작 첫 위치에서 저장영역(빨강)과 검색영역(파랑)이 겹치지 않게 기본 배치
    try {
      val wS = screen.width.coerceAtLeast(1)
      val hS = screen.height.coerceAtLeast(1)
      fun clampLpToScreen(lp: WindowManager.LayoutParams) {
        val w = lp.width.coerceAtLeast(1)
        val h = lp.height.coerceAtLeast(1)
        lp.x = lp.x.coerceIn(0, (wS - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (hS - h).coerceAtLeast(0))
      }

      val cropDefault = (cropLeftU0 < 0 && cropTopU0 < 0)
      val searchDefault = (sLeftU < 0 && sTopU < 0)
      if (cropDefault && searchDefault) {
        clampLpToScreen(rectLp)
        clampLpToScreen(searchRectLp)
        val r1 =
          Rect(
            rectLp.x,
            rectLp.y,
            rectLp.x + rectLp.width,
            rectLp.y + rectLp.height
          )
        val r2 =
          Rect(
            searchRectLp.x,
            searchRectLp.y,
            searchRectLp.x + searchRectLp.width,
            searchRectLp.y + searchRectLp.height
          )
        if (Rect.intersects(r1, r2)) {
          val gap = dpToPx(16f)
          // 우측 배치 -> 안 되면 아래 -> 안 되면 좌측 -> 안 되면 위
          val candidates =
            listOf(
              Pair(r1.right + gap, r1.top),
              Pair(r1.left, r1.bottom + gap),
              Pair((r1.left - gap - searchRectLp.width), r1.top),
              Pair(r1.left, (r1.top - gap - searchRectLp.height)),
            )
          var placed = false
          for (c in candidates) {
            val w2 = searchRectLp.width.coerceAtLeast(1)
            val h2 = searchRectLp.height.coerceAtLeast(1)
            val tmpX = c.first.coerceIn(0, (wS - w2).coerceAtLeast(0))
            val tmpY = c.second.coerceIn(0, (hS - h2).coerceAtLeast(0))
            val tr = Rect(tmpX, tmpY, tmpX + w2, tmpY + h2)
            if (!Rect.intersects(r1, tr)) {
              searchRectLp.x = tmpX
              searchRectLp.y = tmpY
              placed = true
              break
            }
          }
          if (!placed) {
            // 최후: y만 조금 내려서라도 겹침 최소화
            searchRectLp.y = (r1.bottom + gap).coerceIn(0, (hS - searchRectLp.height).coerceAtLeast(0))
          }
        }
      }
    } catch (_: Throwable) {}

    searchRectView.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          val lpLocal = imageModuleSearchRectLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampImageModuleSearchRectToScreen()
              try { wmLocal.updateViewLayout(v, lpLocal) } catch (_: Throwable) {}
              schedulePersistImagePickerState(usable)
              return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
              schedulePersistImagePickerState(usable)
              return true
            }
            else -> return false
          }
        }
      }
    )

    // 컨트롤 패널(작은 창)
    val panel =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // (요청) 화면을 덜 가리도록 컴팩트하게
        val padH = if (isLandscape) dpToPx(8f) else dpToPx(10f)
        val padV = if (isLandscape) dpToPx(6f) else dpToPx(8f)
        setPadding(padH, padV, padH, padV)
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      }
    val drag =
      TextView(this).apply {
        text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = if (isLandscape) 16f else 17f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(if (isLandscape) 4f else 6f))
      }
    val tvTitle =
      TextView(this).apply {
        setTextColor(Color.parseColor("#111827"))
        textSize = if (isLandscape) 13f else 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        text = if (isEdit) "이미지 수정" else "이미지 가져오기"
        setPadding(0, 0, 0, dpToPx(if (isLandscape) 4f else 6f))
      }

    // 파일명: 자동 생성(수정 불가)
    if (!isEdit && imageModulePickerFileName.isBlank()) {
      try { imageModulePickerFileName = generateAutoImageFileName() } catch (_: Throwable) {}
    }
    val tvFileName =
      TextView(this).apply {
        setTextColor(Color.parseColor("#374151"))
        textSize = if (isLandscape) 11f else 12f
        text = "파일명: ${imageModulePickerFileName}"
        // (요청) 가로모드에서 가로폭 과다 점유 방지
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        maxWidth = dpToPx(if (isLandscape) 220f else 260f)
        setPadding(0, 0, 0, dpToPx(if (isLandscape) 6f else 8f))
      }
    val rowSize =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    // (요청) 저장영역/검색영역 입력은 콤보박스로 고정 범위 제공
    val saveSizeOpts = (10..300 step 10).toList()
    val searchSizeOpts = (100..2500 step 50).toList()
    fun nearestIdx(list: List<Int>, v: Int): Int {
      if (list.isEmpty()) return 0
      var bestI = 0
      var bestD = Int.MAX_VALUE
      for (i in list.indices) {
        val d = kotlin.math.abs(list[i] - v)
        if (d < bestD) {
          bestD = d
          bestI = i
        }
      }
      return bestI.coerceIn(0, list.lastIndex.coerceAtLeast(0))
    }

    fun mkRowLabel(text: String): TextView =
      TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#374151"))
        textSize = if (isLandscape) 12f else 13f
        try { setTypeface(typeface, android.graphics.Typeface.BOLD) } catch (_: Throwable) {}
        setPadding(0, 0, dpToPx(8f), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      }

    fun mkIntSpinner(opts: List<Int>, initial: Int, hint: String): android.widget.Spinner {
      val sp =
        android.widget.Spinner(this).apply {
          val items = opts.map { it.toString() }
          val ad =
            android.widget.ArrayAdapter<String>(
              this@ScreenCaptureService,
              R.layout.spinner_marker_kind_selected,
              items
            ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
          adapter = ad
          setSelection(nearestIdx(opts, initial), false)
          contentDescription = hint
          // 터치 영역을 EditText와 비슷하게
          setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
          minimumHeight = dpToPx(36f)
          layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
      return sp
    }

    val spSaveX = mkIntSpinner(saveSizeOpts, cropW0.coerceIn(10, 300), "save_x")
    val spSaveY = mkIntSpinner(saveSizeOpts, cropH0.coerceIn(10, 300), "save_y").apply {
      (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dpToPx(8f)
    }
    val btnApply =
      Button(this).apply {
        text = "적용"
        isAllCaps = false
        textSize = if (isLandscape) 12f else 13f
        minHeight = dpToPx(36f)
        setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
        minimumWidth = 0
        minWidth = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpToPx(8f) }
      }
    rowSize.addView(mkRowLabel("저장영역"))
    rowSize.addView(spSaveX)
    rowSize.addView(spSaveY)
    rowSize.addView(btnApply)

    val rowSearchSize =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    val spSearchX = mkIntSpinner(searchSizeOpts, sW0.coerceIn(100, 2500), "search_x")
    val spSearchY = mkIntSpinner(searchSizeOpts, sH0.coerceIn(100, 2500), "search_y").apply {
      (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dpToPx(8f)
    }
    val btnSearchApply =
      Button(this).apply {
        text = "적용"
        isAllCaps = false
        textSize = if (isLandscape) 12f else 13f
        minHeight = dpToPx(36f)
        setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
        minimumWidth = 0
        minWidth = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpToPx(8f) }
      }
    rowSearchSize.addView(mkRowLabel("검색영역"))
    rowSearchSize.addView(spSearchX)
    rowSearchSize.addView(spSearchY)
    rowSearchSize.addView(btnSearchApply)

    val rowBtns =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    fun mkBtn(text: String, onClick: () -> Unit): Button =
      Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = if (isLandscape) 12f else 13f
        minHeight = dpToPx(36f)
        setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      }
    btnApply.setOnClickListener {
      val w = saveSizeOpts.getOrNull(spSaveX.selectedItemPosition) ?: cropW0.coerceIn(10, 300)
      val h = saveSizeOpts.getOrNull(spSaveY.selectedItemPosition) ?: cropH0.coerceIn(10, 300)
      val lpLocal = imageModuleRectLp ?: return@setOnClickListener
      lpLocal.width = w
      lpLocal.height = h
      clampImageModuleRectToScreen()
      try { wmLocal.updateViewLayout(imageModuleRectRoot, lpLocal) } catch (_: Throwable) {}
      schedulePersistImagePickerState(usable)
    }
    btnSearchApply.setOnClickListener {
      val w = searchSizeOpts.getOrNull(spSearchX.selectedItemPosition) ?: sW0.coerceIn(100, 2500)
      val h = searchSizeOpts.getOrNull(spSearchY.selectedItemPosition) ?: sH0.coerceIn(100, 2500)
      val lpLocal = imageModuleSearchRectLp ?: return@setOnClickListener
      lpLocal.width = w
      lpLocal.height = h
      clampImageModuleSearchRectToScreen()
      try { wmLocal.updateViewLayout(imageModuleSearchRectRoot, lpLocal) } catch (_: Throwable) {}
      schedulePersistImagePickerState(usable)
    }
    val btnCrop =
      mkBtn("자르기/저장") {
        val w = saveSizeOpts.getOrNull(spSaveX.selectedItemPosition) ?: 128
        val h = saveSizeOpts.getOrNull(spSaveY.selectedItemPosition) ?: 128
        // (요청) 이미지 수정(편집)에서 저장해도 "이전 이미지"가 유지되도록,
        // 기존 파일을 덮어쓰지 않고 항상 새 파일명으로 저장한다(메크로 저장 전까지는 이전 파일 유지).
        val curName = imageModulePickerFileName.trim()
        val original = imageModulePickerOriginalFileName.trim()
        val avoidOverwrite = original.isNotBlank() && curName.isNotBlank() && curName == original
        val name =
          when {
            avoidOverwrite -> generateAutoImageFileName()
            curName.isNotBlank() -> curName
            else -> generateAutoImageFileName()
          }
        imageModulePickerFileName = name
        try { tvFileName.text = "파일명: ${imageModulePickerFileName}" } catch (_: Throwable) {}
        val lpLocal = imageModuleRectLp ?: return@mkBtn
        val leftS = lpLocal.x
        val topS = lpLocal.y
        // (요청) 검색영역 중앙 텍스트("검색영역")가 캡처에 같이 저장되는 문제 방지:
        // 저장 직전에 텍스트를 숨기고(다음 프레임 반영 대기), 저장 후 다시 보이게 한다.
        fun setSearchLabelVisible(visible: Boolean) {
          try {
            val vg = searchRectView as? ViewGroup ?: return
            for (i in 0 until vg.childCount) {
              val c = vg.getChildAt(i)
              if (c is TextView && c.text?.toString() == "검색영역") {
                c.visibility = if (visible) View.VISIBLE else View.INVISIBLE
              }
            }
          } catch (_: Throwable) {}
        }
        setSearchLabelVisible(false)

        Handler(Looper.getMainLooper()).postDelayed(
          {
            try {
              val bmp = cropBitmapFromScreenRect(leftS, topS, w, h) ?: run {
                setSearchLabelVisible(true)
                updateNotification("이미지 저장 실패(캡처 없음)")
                return@postDelayed
              }
              val outFile = File(atxImgDir(), name)
              val ok =
                try {
                  outFile.parentFile?.mkdirs()
                  java.io.FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                  }
                  true
                } catch (_: Throwable) {
                  false
                } finally {
                  // (메모리) 저장 후 임시 비트맵 해제
                  try { bmp.recycle() } catch (_: Throwable) {}
                }
              if (!ok) {
                setSearchLabelVisible(true)
                updateNotification("이미지 저장 실패")
                return@postDelayed
              }

              // 저장 완료 후 라벨 복구(아래에서 오버레이 제거되더라도 안전)
              setSearchLabelVisible(true)

              try {
                dbg(
                  "imagePicker purpose=$purpose idx=${imageModulePickerTargetIndex} saved file=$name " +
                    "path=${outFile.absolutePath} bytes=${try { outFile.length() } catch (_: Throwable) { -1L }}"
                )
              } catch (_: Throwable) {}
              val sLp = imageModuleSearchRectLp ?: lpLocal
              val startXU = (sLp.x - usable.left).coerceAtLeast(0)
              val startYU = (sLp.y - usable.top).coerceAtLeast(0)
              val endXU = ((sLp.x + sLp.width) - usable.left).coerceAtLeast(0)
              val endYU = ((sLp.y + sLp.height) - usable.top).coerceAtLeast(0)
              val cropLeftU = (leftS - usable.left).coerceAtLeast(0)
              val cropTopU = (topS - usable.top).coerceAtLeast(0)
              // 설정창 복구 + 값 전달
              val targetIdx = imageModulePickerTargetIndex
              // (중요) 메크로 저장/마커 저장 전에는 flutter.markers를 덮어쓰지 않고 draft로만 저장한다.
              try { persistImagePickerStateNow(usable) } catch (_: Throwable) {}
              removeImageModulePickerOverlays()
              try {
                val it = Intent(MarkerSettingsActivity.ACTION_IMAGE_MODULE_PICK_RESULT)
                  .setPackage(packageName)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, targetIdx)
                  // removeImageModulePickerOverlays()에서 imagePickerPurpose가 초기화될 수 있어 local purpose 사용
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_PURPOSE, purpose)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_FILE, name)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, startXU)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, startYU)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_X2_U, endXU)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y2_U, endYU)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_CROP_LEFT_U, cropLeftU)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_CROP_TOP_U, cropTopU)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_W, w)
                  .putExtra(MarkerSettingsActivity.EXTRA_PICK_H, h)
                sendBroadcast(it)
                try { dbg("imagePicker broadcast purpose=$purpose idx=$targetIdx file=$name") } catch (_: Throwable) {}
              } catch (_: Throwable) {}
              try {
                reopenMarkerSettingsAfterImagePick(
                  targetIdx,
                  purpose = purpose,
                  file = name,
                  xU = startXU,
                  yU = startYU,
                  x2U = endXU,
                  y2U = endYU,
                  cropLeftU = cropLeftU,
                  cropTopU = cropTopU,
                  w = w,
                  h = h
                )
              } catch (_: Throwable) {}
            } catch (_: Throwable) {
              try { setSearchLabelVisible(true) } catch (_: Throwable) {}
              try { updateNotification("이미지 저장 실패") } catch (_: Throwable) {}
            }
          },
          80L
        )
      }
    val btnClose =
      mkBtn("닫기") {
        val targetIdx = imageModulePickerTargetIndex
        // (중요) 닫기에서는 마커 저장값을 건드리지 않는다(draft는 디바운스로 저장됨).
        try { persistImagePickerStateNow(usable) } catch (_: Throwable) {}
        try { dbg("imagePicker purpose=$purpose idx=$targetIdx close") } catch (_: Throwable) {}
        removeImageModulePickerOverlays()
        try {
          val it = Intent(MarkerSettingsActivity.ACTION_IMAGE_MODULE_PICK_RESULT)
            .setPackage(packageName)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_TARGET_INDEX, targetIdx)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_PURPOSE, purpose)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, -1)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, -1)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_X2_U, -1)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_Y2_U, -1)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_CROP_LEFT_U, -1)
            .putExtra(MarkerSettingsActivity.EXTRA_PICK_CROP_TOP_U, -1)
          sendBroadcast(it)
        } catch (_: Throwable) {}
        try {
          reopenMarkerSettingsAfterImagePick(targetIdx, purpose = purpose, file = null, xU = -1, yU = -1, x2U = -1, y2U = -1, cropLeftU = -1, cropTopU = -1, w = -1, h = -1)
        } catch (_: Throwable) {}
      }
    rowBtns.addView(btnCrop)
    rowBtns.addView(btnClose)

    panel.addView(drag)
    panel.addView(tvTitle)
    panel.addView(tvFileName)
    panel.addView(rowSize)
    panel.addView(rowSearchSize)
    panel.addView(rowBtns)

    val panelLp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = usable.left + dpToPx(6f)
        y = (usable.top + dpToPx(if (isLandscape) 8f else 10f))
      }

    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lpLocal = imageModulePanelLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampImageModulePanelToUsable()
              try { wmLocal.updateViewLayout(panel, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    imageModuleRectRoot = rectView
    imageModuleRectLp = rectLp
    imageModuleSearchRectRoot = searchRectView
    imageModuleSearchRectLp = searchRectLp
    imageModulePanelRoot = panel
    imageModulePanelLp = panelLp

    try { wmLocal.addView(rectView, rectLp) } catch (_: Throwable) {
      imageModuleRectRoot = null
      imageModuleRectLp = null
    }
    try { wmLocal.addView(searchRectView, searchRectLp) } catch (_: Throwable) {
      imageModuleSearchRectRoot = null
      imageModuleSearchRectLp = null
    }
    try { wmLocal.addView(panel, panelLp) } catch (_: Throwable) {
      try { wmLocal.removeView(rectView) } catch (_: Throwable) {}
      try { wmLocal.removeView(searchRectView) } catch (_: Throwable) {}
      imageModuleRectRoot = null
      imageModuleRectLp = null
      imageModuleSearchRectRoot = null
      imageModuleSearchRectLp = null
      imageModulePanelRoot = null
      imageModulePanelLp = null
    }

    clampImageModuleRectToScreen()
    clampImageModuleSearchRectToScreen()
    clampImageModulePanelToUsable()
  }

  private fun reopenMarkerSettingsAfterImagePick(
    targetIndex: Int,
    purpose: String,
    file: String?,
    xU: Int,
    yU: Int,
    x2U: Int,
    y2U: Int,
    cropLeftU: Int,
    cropTopU: Int,
    w: Int,
    h: Int
  ) {
    try {
      val loaded = try { loadMarkersFromPrefs() } catch (_: Throwable) { mutableListOf<Marker>() }
      val m = loaded.firstOrNull { it.index == targetIndex }
      val cxU = m?.xPx ?: -1
      val cyU = m?.yPx ?: -1
      val it =
        Intent(this, MarkerSettingsActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
          putExtra(MarkerSettingsActivity.EXTRA_INDEX, targetIndex)
          if (cxU >= 0) putExtra(MarkerSettingsActivity.EXTRA_CENTER_USABLE_X, cxU)
          if (cyU >= 0) putExtra(MarkerSettingsActivity.EXTRA_CENTER_USABLE_Y, cyU)
          putExtra(MarkerSettingsActivity.EXTRA_PICK_PURPOSE, purpose)
          if (!file.isNullOrBlank()) putExtra(MarkerSettingsActivity.EXTRA_PICK_FILE, file)
          if (xU >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_X_U, xU)
          if (yU >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_Y_U, yU)
          if (x2U >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_X2_U, x2U)
          if (y2U >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_Y2_U, y2U)
          if (cropLeftU >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_CROP_LEFT_U, cropLeftU)
          if (cropTopU >= 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_CROP_TOP_U, cropTopU)
          if (w > 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_W, w)
          if (h > 0) putExtra(MarkerSettingsActivity.EXTRA_PICK_H, h)
        }
      val flags =
        PendingIntent.FLAG_UPDATE_CURRENT or
          (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
      val pi = PendingIntent.getActivity(this, 9102, it, flags)
      try {
        pi.send()
      } catch (_: Throwable) {
        try { startActivity(it) } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  private fun clampImageModuleRectToScreen() {
    val root = imageModuleRectRoot ?: return
    val lp = imageModuleRectLp ?: return
    val (wS, hS, _) = getScreenSize()
    val w = lp.width.coerceAtLeast(1)
    val h = lp.height.coerceAtLeast(1)
    lp.x = lp.x.coerceIn(0, (wS - w).coerceAtLeast(0))
    lp.y = lp.y.coerceIn(0, (hS - h).coerceAtLeast(0))
    try { wm?.updateViewLayout(root, lp) } catch (_: Throwable) {}
  }

  private fun clampImageModuleSearchRectToScreen() {
    val root = imageModuleSearchRectRoot ?: return
    val lp = imageModuleSearchRectLp ?: return
    val (wS, hS, _) = getScreenSize()
    val w = lp.width.coerceAtLeast(1)
    val h = lp.height.coerceAtLeast(1)
    lp.x = lp.x.coerceIn(0, (wS - w).coerceAtLeast(0))
    lp.y = lp.y.coerceIn(0, (hS - h).coerceAtLeast(0))
    try { wm?.updateViewLayout(root, lp) } catch (_: Throwable) {}
  }

  private fun clampImageModulePanelToUsable() {
    val root = imageModulePanelRoot ?: return
    val lp = imageModulePanelLp ?: return
    val usable = getUsableRectPx()
    val w = (root.width.takeIf { it > 0 } ?: root.measuredWidth).takeIf { it > 0 } ?: dpToPx(260f)
    val h = (root.height.takeIf { it > 0 } ?: root.measuredHeight).takeIf { it > 0 } ?: dpToPx(180f)
    lp.x = lp.x.coerceIn(usable.left, (usable.right - w).coerceAtLeast(usable.left))
    lp.y = lp.y.coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))
    try { wm?.updateViewLayout(root, lp) } catch (_: Throwable) {}
  }

  private fun removeImageModulePickerOverlays() {
    // (개선) 외부 요인(매크로 시작/정리 등)으로 오버레이가 닫힐 때도 마지막 상태를 저장
    try {
      val usable = getUsableRectPx()
      persistImagePickerStateNow(usable)
    } catch (_: Throwable) {}
    val rect = imageModuleRectRoot
    val searchRect = imageModuleSearchRectRoot
    val panel = imageModulePanelRoot
    imageModuleRectRoot = null
    imageModuleRectLp = null
    imageModuleSearchRectRoot = null
    imageModuleSearchRectLp = null
    imageModulePanelRoot = null
    imageModulePanelLp = null
    imageModulePickerTargetIndex = 0
    imageModulePickerFileName = ""
    imageModulePickerOriginalFileName = ""
    imagePickerPurpose = MarkerSettingsActivity.PICK_PURPOSE_IMAGE_MODULE
    imagePickerDraftPrefix = "imgDraft"
    try { if (rect != null) wm?.removeView(rect) } catch (_: Throwable) {}
    try { if (searchRect != null) wm?.removeView(searchRect) } catch (_: Throwable) {}
    try { if (panel != null) wm?.removeView(panel) } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
    try {
      // (중요) requestShowToolbar는 ref-count(pop) 역할이므로 조건 없이 호출한다.
      // 다른 모달이 열려있으면 hideCount>0이라 실제 표시는 자동으로 보류된다.
      AutoClickAccessibilityService.requestShowToolbar()
    } catch (_: Throwable) {}
  }

  private fun cropBitmapFromScreenRect(leftS: Int, topS: Int, wS: Int, hS: Int): Bitmap? {
    if (!captureReady) return null
    if (wS <= 0 || hS <= 0) return null
    val screen = getScreenSize()
    val l = leftS.coerceIn(0, (screen.width - 1).coerceAtLeast(0))
    val t = topS.coerceIn(0, (screen.height - 1).coerceAtLeast(0))
    val r = (l + wS).coerceIn(0, screen.width)
    val b = (t + hS).coerceIn(0, screen.height)
    val w = (r - l).coerceAtLeast(1)
    val h = (b - t).coerceAtLeast(1)

    val out = IntArray(w * h)
    synchronized(frameLock) {
      val info = frameInfo ?: return null
      val fw = info.width.coerceAtLeast(1)
      val fh = info.height.coerceAtLeast(1)
      val rowStride = info.rowStride
      val pixelStride = info.pixelStride
      if (frameBytes.isEmpty()) return null

      // x/y 맵(스크린->프레임)
      val xMap = IntArray(w) { i ->
        val sx = (l + i).coerceIn(0, (screen.width - 1).coerceAtLeast(0))
        ((sx.toFloat() * fw.toFloat() / screen.width.toFloat()).roundToInt()).coerceIn(0, fw - 1)
      }
      val yMap = IntArray(h) { j ->
        val sy = (t + j).coerceIn(0, (screen.height - 1).coerceAtLeast(0))
        ((sy.toFloat() * fh.toFloat() / screen.height.toFloat()).roundToInt()).coerceIn(0, fh - 1)
      }

      var idx = 0
      for (j in 0 until h) {
        val yy = yMap[j]
        val base = yy * rowStride
        for (i in 0 until w) {
          val xx = xMap[i]
          val off = base + xx * pixelStride
          if (off + 3 >= frameBytes.size) {
            out[idx++] = 0
          } else {
            val rr = frameBytes[off].toInt() and 0xFF
            val gg = frameBytes[off + 1].toInt() and 0xFF
            val bb = frameBytes[off + 2].toInt() and 0xFF
            val aa = frameBytes[off + 3].toInt() and 0xFF
            out[idx++] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
          }
        }
      }
    }
    return try {
      Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
      null
    }
  }

  private fun updateImageFoundCenterInPrefs(index: Int, xU: Int, yU: Int) {
    try {
      val raw = prefs().getString("flutter.markers", null) ?: return
      val arr = JSONArray(raw)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optInt("index", Int.MIN_VALUE) != index) continue
        // image_module일 때만 기록
        if (o.optString("kind", "") != "image_module") return
        o.put("imageFoundCenterXPx", xU.coerceAtLeast(0))
        o.put("imageFoundCenterYPx", yU.coerceAtLeast(0))
        prefs().edit().putString("flutter.markers", arr.toString()).apply()
        return
      }
    } catch (_: Throwable) {}
  }

  private fun updateImageMatchDebugInPrefs(index: Int, xU: Int, yU: Int, score: Float, minScore: Float) {
    try {
      val raw = prefs().getString("flutter.markers", null) ?: return
      val arr = JSONArray(raw)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optInt("index", Int.MIN_VALUE) != index) continue
        if (o.optString("kind", "") != "image_module") return

        val scorePct = if (score >= 0f) (score.coerceIn(0f, 1f) * 100f).roundToInt() else -1
        val minPct = (minScore.coerceIn(0f, 1f) * 100f).roundToInt()
        o.put("imageLastScorePct", scorePct)
        o.put("imageLastMinPct", minPct)
        o.put("imageLastOk", if (score >= minScore && score >= 0f) 1 else 0)
        if (xU >= 0) o.put("imageFoundCenterXPx", xU.coerceAtLeast(0))
        if (yU >= 0) o.put("imageFoundCenterYPx", yU.coerceAtLeast(0))
        prefs().edit().putString("flutter.markers", arr.toString()).apply()
        return
      }
    } catch (_: Throwable) {}
  }

  private fun updateImageModulePickerResultInPrefs(
    index: Int,
    file: String,
    cropW: Int,
    cropH: Int,
    cropLeftU: Int,
    cropTopU: Int,
    startXU: Int,
    startYU: Int,
    endXU: Int,
    endYU: Int,
  ) {
    try {
      val loaded = try { loadMarkersFromPrefs() } catch (_: Throwable) { mutableListOf<Marker>() }
      val m = loaded.firstOrNull { it.index == index } ?: return
      if (m.kind != "image_module") return
      m.imageTemplateFile = file
      m.imageW = cropW.coerceIn(8, 1024)
      m.imageH = cropH.coerceIn(8, 1024)
      m.imageCropLeftXPx = cropLeftU.coerceAtLeast(0)
      m.imageCropTopYPx = cropTopU.coerceAtLeast(0)
      m.imageStartXPx = startXU.coerceAtLeast(0)
      m.imageStartYPx = startYU.coerceAtLeast(0)
      m.imageEndXPx = endXU.coerceAtLeast(0)
      m.imageEndYPx = endYU.coerceAtLeast(0)
      saveMarkersToPrefs(loaded)
    } catch (_: Throwable) {}
  }

  // ---------------- 화면설정 메뉴(도움말/스크립터/평가/공유/피드백/크레딧/개인정보) ----------------
  private fun removeSettingsMenuOverlay() {
    val root = settingsMenuRoot ?: return
    settingsMenuRoot = null
    settingsMenuLp = null
    try { wm?.removeView(root) } catch (_: Throwable) {}
  }

  private fun removeSettingsTextOverlay() {
    val root = settingsTextRoot ?: return
    settingsTextRoot = null
    settingsTextLp = null
    // (메모리) 도움말 이미지 등 비트맵이 붙어있을 수 있어 명시적으로 해제
    try {
      settingsTextBitmaps?.let { list ->
        for (b in list) {
          try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
        }
      }
    } catch (_: Throwable) {}
    settingsTextBitmaps = null
    try { wm?.removeView(root) } catch (_: Throwable) {}
    // (요청) 선택 항목 창 닫으면 메뉴툴바 다시 표시
    try {
      popToolbarHiddenByModalInternal()
    } catch (_: Throwable) {}
    try {
      // ref-count(pop) 성격이라 조건 없이 호출
      AutoClickAccessibilityService.requestShowToolbar()
    } catch (_: Throwable) {}
  }

  // (도움말) 이미지 + 설명 리스트
  private var settingsTextBitmaps: MutableList<android.graphics.Bitmap>? = null

  private fun decodeHelpBitmap(assetPath: String, maxDimPx: Int = 520): android.graphics.Bitmap? {
    fun open(path: String): java.io.InputStream? {
      return try { assets.open(path) } catch (_: Throwable) { null }
    }
    fun calcSampleSize(w: Int, h: Int, maxDim: Int): Int {
      var s = 1
      var ww = w.coerceAtLeast(1)
      var hh = h.coerceAtLeast(1)
      while ((ww / s) > maxDim || (hh / s) > maxDim) s *= 2
      return s.coerceAtLeast(1)
    }

    // Flutter asset은 보통 flutter_assets/ 아래에 패킹된다.
    val tryPaths = listOf("flutter_assets/$assetPath", assetPath)
    for (p in tryPaths) {
      val b1 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
      try {
        open(p)?.use { android.graphics.BitmapFactory.decodeStream(it, null, b1) }
      } catch (_: Throwable) {}
      val w = b1.outWidth
      val h = b1.outHeight
      if (w <= 0 || h <= 0) continue
      val sample = calcSampleSize(w, h, maxDimPx)
      val b2 =
        android.graphics.BitmapFactory.Options().apply {
          inJustDecodeBounds = false
          inSampleSize = sample
          try {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inDither = false
          } catch (_: Throwable) {}
          try { inScaled = false } catch (_: Throwable) {}
        }
      val bmp = try { open(p)?.use { android.graphics.BitmapFactory.decodeStream(it, null, b2) } } catch (_: Throwable) { null }
      if (bmp != null) return bmp
    }
    return null
  }

  private fun showHelpOverlay() {
    val wmLocal = wm ?: return
    // (요청) 메뉴 항목 창만 단독 표시: 이 창이 떠있는 동안 메뉴툴바 숨김
    try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
    pushToolbarHiddenByModalInternal()
    removeSettingsTextOverlay()
    val lang = I18n.langFromPrefs(prefs())

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      }

    val drag =
      TextView(this).apply {
        text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(6f))
      }
    val tvTitle =
      TextView(this).apply {
        text = I18n.help(lang)
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 15f
        setPadding(0, 0, 0, dpToPx(8f))
      }

    val list = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
    }

    fun addItem(asset: String, title: String, body: String) {
      val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(10f), dpToPx(10f), dpToPx(10f), dpToPx(10f))
        setBackgroundColor(Color.parseColor("#F3F4F6"))
      }
      val head = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 13f
        setPadding(0, 0, 0, dpToPx(6f))
      }
      val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
      }
      val iv = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.parseColor("#FFFFFF"))
      }
      val tv = TextView(this).apply {
        text = body
        setTextColor(Color.parseColor("#111827"))
        textSize = 12.5f
      }
      row.addView(
        iv,
        LinearLayout.LayoutParams(dpToPx(140f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          rightMargin = dpToPx(10f)
        }
      )
      row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      card.addView(head)
      card.addView(row)
      list.addView(
        card,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          bottomMargin = dpToPx(10f)
        }
      )

      val bmp = decodeHelpBitmap(asset)
      if (bmp != null) {
        try {
          iv.setImageBitmap(bmp)
          val arr = settingsTextBitmaps ?: mutableListOf<android.graphics.Bitmap>().also { settingsTextBitmaps = it }
          arr.add(bmp)
        } catch (_: Throwable) {
          try { if (!bmp.isRecycled) bmp.recycle() } catch (_: Throwable) {}
        }
      } else {
        iv.visibility = View.GONE
      }
    }

    // 요청한 순서대로 나열 + 설명
    addItem(
      "assets/help/menu1.png",
      "menu1",
      "앱 첫 화면입니다.\n- 오토클릭짱(색상) 시작: 화면공유(캡처) 권한이 필요합니다.\n- 오토클릭짱(기본) 시작: 화면공유 없이 기본 모드로 시작합니다.\n- 접근성 설정 확인: 접근성 서비스 ON이 필요합니다."
    )
    addItem(
      "assets/help/menu2.png",
      "menu2",
      "화면공유(MediaProjection) 권한 팝업입니다.\n- ‘화면 공유’를 누르면 화면 캡처가 시작되고, 색상/이미지 기반 기능이 활성화됩니다.\n- 필요 없으면 ‘취소’를 누르세요."
    )
    addItem(
      "assets/help/menu3.png",
      "menu3",
      "메뉴 툴바(기본 아이콘)입니다.\n- ▶: 실행/정지\n- ＋: 마커 추가\n- ✎: 편집모드(마커 이동)\n- 🗑: 전체 삭제\n- 👁: 객체보기(마커 표시/숨김)\n- ⚙: 설정/패널\n- ✕: 종료"
    )
    addItem(
      "assets/help/menu4.png",
      "menu4",
      "설정(패널) 화면 예시입니다.\n- 언어, 화면설정/색상패널, 중지조건(무한대/시간/사이클)을 설정합니다.\n- 메크로 저장/열기, 접근성 설정, 닫기 기능이 있습니다."
    )
    addItem(
      "assets/help/menu5.png",
      "menu5",
      "기본설정(화면설정) 화면입니다.\n- 툴바 투명도/크기, 객체 크기, 마커 클릭 pressMs, 이미지 10차 검증 인터벌, 실행확률 등을 조절합니다.\n- ‘로그보기’를 켜면 실행로그 창을 볼 수 있습니다."
    )
    addItem(
      "assets/help/menu6.png",
      "menu6",
      "설정창 상단 ‘메뉴’ 버튼을 누르면 나오는 풀다운 메뉴입니다.\n- 도움말, 스크립터, 앱 평가/공유, 피드백, 크레딧, 개인정보보호정책으로 이동합니다."
    )
    addItem(
      "assets/help/menu8.png",
      "menu8",
      "메뉴 툴바 상태 아이콘 예시입니다.\n- 가운데 아이콘/눈 아이콘은 설정(편집모드/표시 여부 등)에 따라 모양이 바뀔 수 있습니다.\n- 기능 자체는 menu3의 각 버튼과 동일하게 동작합니다."
    )

    val sv = ScrollView(this).apply {
      isFillViewport = true
      addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val btnClose =
      Button(this).apply {
        isAllCaps = false
        text = I18n.close(lang)
        setOnClickListener { removeSettingsTextOverlay() }
      }

    root.addView(drag)
    root.addView(tvTitle)
    root.addView(
      sv,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    )
    root.addView(btnClose)

    val usable = getUsableRectPx()
    val lp =
      WindowManager.LayoutParams(
        (usable.width() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.width()),
        (usable.height() * 0.90f).roundToInt().coerceAtLeast(dpToPx(240f)).coerceAtMost(usable.height()),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = usable.left + dpToPx(10f)
        y = usable.top + dpToPx(10f)
      }

    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lpLocal = settingsTextLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampOverlayToUsable(root, lpLocal)
              try { wmLocal.updateViewLayout(root, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    settingsTextRoot = root
    settingsTextLp = lp
    try { wmLocal.addView(root, lp) } catch (_: Throwable) {
      settingsTextRoot = null
      settingsTextLp = null
    }
    clampOverlayToUsable(root, lp)
  }

  private fun loadHelpBitmap(assetRelPath: String, maxSidePx: Int): android.graphics.Bitmap? {
    // Flutter assets는 APK 내 "flutter_assets/" 아래로 패키징된다.
    val nativePathMaybe =
      try {
        if (assetRelPath.startsWith("assets/")) assetRelPath.removePrefix("assets/") else null
      } catch (_: Throwable) {
        null
      }
    val candidates =
      listOf(
        "flutter_assets/$assetRelPath",
        assetRelPath,
        // native assets 폴더(`android/app/src/main/assets/`)에 직접 넣은 경우 (ex: help/menu1.png)
        nativePathMaybe,
      )
        .filterNotNull()
    fun openAny(): Pair<String, java.io.InputStream>? {
      for (p in candidates) {
        try {
          return Pair(p, assets.open(p))
        } catch (_: Throwable) {}
      }
      return null
    }
    fun calcSampleSize(w: Int, h: Int, maxSide: Int): Int {
      var s = 1
      val ww = w.coerceAtLeast(1)
      val hh = h.coerceAtLeast(1)
      while ((ww / s) > maxSide || (hh / s) > maxSide) s *= 2
      return s.coerceAtLeast(1)
    }
    // 1) bounds
    val b = openAny() ?: return null
    val path1 = b.first
    val optsB = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
      b.second.use { BitmapFactory.decodeStream(it, null, optsB) }
    } catch (_: Throwable) {
      try { b.second.close() } catch (_: Throwable) {}
      return null
    }
    val w0 = optsB.outWidth
    val h0 = optsB.outHeight
    if (w0 <= 0 || h0 <= 0) return null
    val sample = calcSampleSize(w0, h0, maxSidePx.coerceAtLeast(64))
    // 2) decode
    val opts =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sample
        // 도움말 이미지는 사진/스크린샷 위주라 RGB_565로 메모리 절감
        try {
          inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
          inDither = true
        } catch (_: Throwable) {}
        try { inScaled = false } catch (_: Throwable) {}
      }
    try {
      assets.open(path1).use { ins ->
        return BitmapFactory.decodeStream(ins, null, opts)
      }
    } catch (_: Throwable) {
      // 다른 후보 재시도(혹시 첫 후보가 bounds만 되고 decode 실패하는 경우)
      for (p in candidates) {
        if (p == path1) continue
        try {
          assets.open(p).use { ins ->
            return BitmapFactory.decodeStream(ins, null, opts)
          }
        } catch (_: Throwable) {}
      }
      return null
    }
  }

  private fun showSettingsHelpOverlay(lang: String) {
    val wmLocal = wm ?: return
    // (요청) 메뉴 항목 창만 단독 표시: 이 창이 떠있는 동안 메뉴툴바 숨김
    try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
    pushToolbarHiddenByModalInternal()
    removeSettingsTextOverlay()

    val title = I18n.help(lang)
    val usable = getUsableRectPx()
    val isNarrow = usable.width() < dpToPx(420f)
    // (변경) 도움말 이미지 축소 처리 취소: 원래 크기(기본 폭/최대사이즈)로 표시
    val imgMaxSide = if (isNarrow) dpToPx(260f) else dpToPx(220f)

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      }
    val drag =
      TextView(this).apply {
        text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(6f))
      }
    val tvTitle =
      TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 15f
        setPadding(0, 0, 0, dpToPx(8f))
      }

    fun desc(text: String): TextView =
      TextView(this).apply {
        setTextColor(Color.parseColor("#111827"))
        textSize = 13f
        this.text = text
      }

    fun item(
      assetName: String,
      label: String,
      body: String,
      forceSideBySide: Boolean = false,
      imgWidthDp: Float = 160f,
      maxSideOverridePx: Int? = null,
    ): View {
      val sideBySide = (!isNarrow) || forceSideBySide
      val box =
        LinearLayout(this).apply {
          orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
          setPadding(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
        }

      val iv =
        ImageView(this).apply {
          adjustViewBounds = true
          scaleType = ImageView.ScaleType.FIT_START
          contentDescription = label
        }
      val bmp = loadHelpBitmap("assets/help/$assetName", maxSidePx = maxSideOverridePx ?: imgMaxSide)
      if (bmp != null) {
        iv.setImageBitmap(bmp)
        // (메모리) 오버레이 제거 시 recycle 하도록 보관
        try {
          val arr = settingsTextBitmaps ?: mutableListOf<android.graphics.Bitmap>().also { settingsTextBitmaps = it }
          arr.add(bmp)
        } catch (_: Throwable) {}
      } else {
        // 로드 실패 시 최소한의 안내(텍스트는 옆 설명으로 대체)
        try { iv.setBackgroundColor(Color.parseColor("#E5E7EB")) } catch (_: Throwable) {}
      }

      val tv =
        desc(
          "$label\n$body"
        ).apply {
          setPadding(
            if (sideBySide) dpToPx(10f) else 0,
            if (sideBySide) 0 else dpToPx(8f),
            0,
            0
          )
        }

      if (!sideBySide) {
        box.addView(
          iv,
          LinearLayout.LayoutParams(dpToPx(imgWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
          }
        )
        box.addView(tv)
      } else {
        // (요청) 메뉴툴바(기본/추가버튼) 같은 가로형 이미지는 너무 커지지 않게 고정 폭으로 표시
        box.addView(
          iv,
          LinearLayout.LayoutParams(dpToPx(imgWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dpToPx(10f)
          }
        )
        box.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      }

      val wrap =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setBackgroundColor(Color.parseColor("#00000000"))
        }
      wrap.addView(box)
      // 구분선
      wrap.addView(
        View(this).apply { setBackgroundColor(Color.parseColor("#E5E7EB")) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
          topMargin = dpToPx(6f)
          bottomMargin = dpToPx(6f)
        }
      )
      return wrap
    }

    val list =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dpToPx(4f))
      }

    // 요청된 순서대로 나열
    list.addView(
      item(
        "menu1.png",
        I18n.helpItemTitle(lang, 1),
        I18n.helpItemBody(lang, 1)
      )
    )
    list.addView(
      item(
        "menu2.png",
        I18n.helpItemTitle(lang, 2),
        I18n.helpItemBody(lang, 2)
      )
    )
    list.addView(
      item(
        "menu3.png",
        I18n.helpItemTitle(lang, 3),
        I18n.helpItemBody(lang, 3),
        forceSideBySide = true,
        imgWidthDp = 68f,
        maxSideOverridePx = dpToPx(100f),
      )
    )
    list.addView(
      item(
        "menu4.png",
        I18n.helpItemTitle(lang, 4),
        I18n.helpItemBody(lang, 4)
      )
    )
    list.addView(
      item(
        "menu5.png",
        I18n.helpItemTitle(lang, 5),
        I18n.helpItemBody(lang, 5)
      )
    )
    list.addView(
      item(
        "menu6.png",
        I18n.helpItemTitle(lang, 6),
        I18n.helpItemBody(lang, 6)
      )
    )
    list.addView(
      item(
        "menu8.png",
        I18n.helpItemTitle(lang, 7),
        I18n.helpItemBody(lang, 7),
        forceSideBySide = true,
        imgWidthDp = 68f,
        maxSideOverridePx = dpToPx(100f),
      )
    )

    val sv =
      ScrollView(this).apply {
        isFillViewport = true
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      }

    val btnClose =
      Button(this).apply {
        isAllCaps = false
        text = I18n.close(lang)
        setOnClickListener { removeSettingsTextOverlay() }
      }

    root.addView(drag)
    root.addView(tvTitle)
    root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(btnClose)

    val lp =
      WindowManager.LayoutParams(
        (usable.width() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.width()),
        (usable.height() * 0.90f).roundToInt().coerceAtLeast(dpToPx(240f)).coerceAtMost(usable.height()),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = usable.left + dpToPx(10f)
        y = usable.top + dpToPx(10f)
      }

    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lpLocal = settingsTextLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampOverlayToUsable(root, lpLocal)
              try { wmLocal.updateViewLayout(root, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    settingsTextRoot = root
    settingsTextLp = lp
    try { wmLocal.addView(root, lp) } catch (_: Throwable) {
      settingsTextRoot = null
      settingsTextLp = null
    }
    clampOverlayToUsable(root, lp)
  }

  private fun updateSettingsMenuOverlayLayout() {
    val root = settingsMenuRoot ?: return
    val lp = settingsMenuLp ?: return
    // (중요) 회전/화면크기 변경 시 메뉴(설정창)가 떠있어도 예외로 앱이 종료되지 않게 방어
    try {
      clampOverlayToUsable(root, lp)
      wm?.updateViewLayout(root, lp)
    } catch (_: Throwable) {}
    // measured size가 0일 수 있어 1회 후속 보정
    if (root.width == 0 || root.height == 0) {
      Handler(Looper.getMainLooper()).postDelayed(
        {
          try {
            clampOverlayToUsable(root, lp)
            wm?.updateViewLayout(root, lp)
          } catch (_: Throwable) {}
        },
        32L
      )
    }
  }

  private fun clampOverlayToUsable(root: View?, lp: WindowManager.LayoutParams?) {
    val r = root ?: return
    val p = lp ?: return
    val usable = getUsableRectPx()
    val w = if (r.width > 0) r.width else (r.measuredWidth.takeIf { it > 0 } ?: dpToPx(260f))
    val h = if (r.height > 0) r.height else (r.measuredHeight.takeIf { it > 0 } ?: dpToPx(220f))
    p.x = p.x.coerceIn(usable.left, (usable.right - w).coerceAtLeast(usable.left))
    p.y = p.y.coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))
  }

  private fun updateSettingsTextOverlayLayout() {
    val root = settingsTextRoot ?: return
    val lp = settingsTextLp ?: return
    val usable = getUsableRectPx()
    val maxW = (usable.width() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.width())
    val maxH = (usable.height() * 0.90f).roundToInt().coerceAtLeast(dpToPx(240f)).coerceAtMost(usable.height())
    lp.width = maxW
    lp.height = maxH
    clampOverlayToUsable(root, lp)
    try { wm?.updateViewLayout(root, lp) } catch (_: Throwable) {}

    // 측정값이 0일 수 있어 1~2회 후속 보정
    if (root.width == 0 || root.height == 0) {
      Handler(Looper.getMainLooper()).postDelayed(
        {
          try {
            clampOverlayToUsable(root, lp)
            wm?.updateViewLayout(root, lp)
          } catch (_: Throwable) {}
        },
        32L
      )
    }
  }

  private fun showSettingsTextOverlay(title: String, text: String) {
    val wmLocal = wm ?: return
    // (요청) 메뉴 항목 창만 단독 표시: 이 창이 떠있는 동안 메뉴툴바 숨김
    try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
    pushToolbarHiddenByModalInternal()
    removeSettingsTextOverlay()
    val lang = I18n.langFromPrefs(prefs())
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      }
    val drag =
      TextView(this).apply {
        this.text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(6f))
      }
    val tvTitle =
      TextView(this).apply {
        this.text = title
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 15f
        setPadding(0, 0, 0, dpToPx(8f))
      }
    val tv =
      TextView(this).apply {
        setTextColor(Color.parseColor("#111827"))
        textSize = 13f
        this.text = text
        setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
      }
    val sv =
      ScrollView(this).apply {
        isFillViewport = true
        addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      }
    val btnClose =
      Button(this).apply {
        isAllCaps = false
        this.text = I18n.close(lang)
        setOnClickListener { removeSettingsTextOverlay() }
      }
    root.addView(drag)
    root.addView(tvTitle)
    // (요청) 창 크기로 인해 "닫기"가 안 보이지 않도록:
    // lp.height를 usable 기준으로 고정하고, 스크롤영역만 남은 공간을 차지하게 한다.
    root.addView(
      sv,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    )
    root.addView(btnClose)

    val usable = getUsableRectPx()
    val lp =
      WindowManager.LayoutParams(
        (usable.width() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.width()),
        (usable.height() * 0.90f).roundToInt().coerceAtLeast(dpToPx(240f)).coerceAtMost(usable.height()),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = usable.left + dpToPx(10f)
        y = usable.top + dpToPx(10f)
      }

    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lpLocal = settingsTextLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampOverlayToUsable(root, lpLocal)
              try { wmLocal.updateViewLayout(root, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    settingsTextRoot = root
    settingsTextLp = lp
    try { wmLocal.addView(root, lp) } catch (_: Throwable) {
      settingsTextRoot = null
      settingsTextLp = null
    }
    clampOverlayToUsable(root, lp)
  }

  private data class HelpImageItem(
    val assetPath: String, // ex) assets/help/menu1.png  (Flutter asset path)
    val title: String,
    val desc: String,
  )

  private fun decodeFlutterAssetBitmap(assetPath: String, maxW: Int, maxH: Int): android.graphics.Bitmap? {
    // Flutter asset은 APK의 assets/flutter_assets/ 아래에 패킹된다.
    val fullPath = "flutter_assets/$assetPath"
    fun openOnce(): java.io.InputStream? =
      try {
        assets.open(fullPath)
      } catch (_: Throwable) {
        null
      }
    fun calcSampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
      var s = 1
      val ww = w.coerceAtLeast(1)
      val hh = h.coerceAtLeast(1)
      while ((ww / s) > maxW || (hh / s) > maxH) s *= 2
      return s.coerceAtLeast(1)
    }

    val b0 = openOnce() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
      BitmapFactory.decodeStream(b0, null, bounds)
    } catch (_: Throwable) {
      // ignore
    } finally {
      try { b0.close() } catch (_: Throwable) {}
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = calcSampleSize(bounds.outWidth, bounds.outHeight, maxW.coerceAtLeast(1), maxH.coerceAtLeast(1))

    val b1 = openOnce() ?: return null
    val opts =
      BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sample
        try { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 } catch (_: Throwable) {}
        try { inDither = false } catch (_: Throwable) {}
        try { inScaled = false } catch (_: Throwable) {}
      }
    return try {
      BitmapFactory.decodeStream(b1, null, opts)
    } catch (_: Throwable) {
      null
    } finally {
      try { b1.close() } catch (_: Throwable) {}
    }
  }

  private fun showHelpOverlayWithImages(title: String, items: List<HelpImageItem>) {
    val wmLocal = wm ?: return
    try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
    pushToolbarHiddenByModalInternal()
    removeSettingsTextOverlay()
    val lang = I18n.langFromPrefs(prefs())

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
        layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
      }

    val drag =
      TextView(this).apply {
        this.text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(6f))
      }
    val tvTitle =
      TextView(this).apply {
        this.text = title
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 15f
        setPadding(0, 0, 0, dpToPx(8f))
      }

    val listCol =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
      }

    val maxImgW = dpToPx(140f)
    val maxImgH = dpToPx(260f)
    for (it in items) {
      val row =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(0, dpToPx(8f), 0, dpToPx(8f))
        }
      val iv =
        ImageView(this).apply {
          adjustViewBounds = true
          scaleType = ImageView.ScaleType.FIT_CENTER
          layoutParams = LinearLayout.LayoutParams(maxImgW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dpToPx(10f)
          }
        }
      try {
        val bmp = decodeFlutterAssetBitmap(it.assetPath, maxImgW, maxImgH)
        if (bmp != null) {
          iv.setImageBitmap(bmp)
        } else {
          // 이미지 로드 실패 시에도 레이아웃은 유지
          iv.setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
      } catch (_: Throwable) {}

      val tv =
        TextView(this).apply {
          setTextColor(Color.parseColor("#111827"))
          textSize = 13f
          text = "${it.title}\n${it.desc}"
          setPadding(0, 0, 0, 0)
          layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

      row.addView(iv)
      row.addView(tv)
      listCol.addView(row)

      // 구분선
      val sep =
        View(this).apply {
          setBackgroundColor(Color.parseColor("#E5E7EB"))
          layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1f)).apply {
            topMargin = dpToPx(2f)
          }
        }
      listCol.addView(sep)
    }

    val sv =
      ScrollView(this).apply {
        isFillViewport = true
        addView(listCol, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      }

    val btnClose =
      Button(this).apply {
        isAllCaps = false
        this.text = I18n.close(lang)
        setOnClickListener { removeSettingsTextOverlay() }
      }

    root.addView(drag)
    root.addView(tvTitle)
    root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(btnClose)

    val usable = getUsableRectPx()
    val lp =
      WindowManager.LayoutParams(
        (usable.width() * 0.94f).roundToInt().coerceAtLeast(dpToPx(280f)).coerceAtMost(usable.width()),
        (usable.height() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.height()),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = usable.left + dpToPx(10f)
        y = usable.top + dpToPx(10f)
      }

    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lpLocal = settingsTextLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampOverlayToUsable(root, lpLocal)
              try { wmLocal.updateViewLayout(root, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    settingsTextRoot = root
    settingsTextLp = lp
    try { wmLocal.addView(root, lp) } catch (_: Throwable) {
      settingsTextRoot = null
      settingsTextLp = null
    }
    clampOverlayToUsable(root, lp)
  }

  private fun buildScripterText(lang: String): String {
    fun t(ko: String, en: String, ja: String = ko, zh: String = ko, ar: String = ko): String {
      return when (lang.lowercase()) {
        "en" -> en
        "ja" -> ja
        "zh" -> zh
        "ar" -> ar
        else -> ko
      }
    }

    fun onOff(v: Boolean): String = if (v) t("ON", "ON", "ON", "ON", "ON") else t("OFF", "OFF", "OFF", "OFF", "OFF")

    fun fmtPx(x: Int, y: Int): String =
      when (lang.lowercase()) {
        "en" -> "px=($x,$y)"
        "ja" -> "px=($x,$y)"
        "zh" -> "px=($x,$y)"
        "ar" -> "px=($x,$y)"
        else -> "픽셀=($x,$y)"
      }

    fun fmtRegion(sx: Int, sy: Int, ex: Int, ey: Int): String {
      val a = minOf(sx, ex)
      val b = minOf(sy, ey)
      val c = maxOf(sx, ex)
      val d = maxOf(sy, ey)
      return when (lang.lowercase()) {
        "en" -> "region=($a,$b)-($c,$d)"
        "ja" -> "範囲=($a,$b)-($c,$d)"
        "zh" -> "范围=($a,$b)-($c,$d)"
        "ar" -> "النطاق=($a,$b)-($c,$d)"
        else -> "영역=($a,$b)-($c,$d)"
      }
    }

    val usable = getUsableRectPx()
    val snap = markersSnapshot()
    val sb = StringBuilder()

    // 순번(순번실행 클릭) 랭크
    val clickRankByIndex =
      snap
        .filter { it.kind == "click" && it.index > 0 }
        .sortedBy { it.index }
        .mapIndexed { i, m -> m.index to (i + 1) }
        .toMap()

    sb.appendLine("${I18n.scripter(lang)} (script)")
    sb.appendLine(t("언어", "Language", "言語", "语言", "اللغة") + ": " + lang)
    sb.appendLine(
      t("기본설정", "Basic", "基本", "基本", "أساسي") +
        ": pressMs=$clickPressMsGlobal, " +
        t("실행확률", "Exec probability", "実行確率", "执行概率", "احتمال التنفيذ") +
        "=${execProbabilityPercent}%, " +
        t("이미지 10차 검증 인터벌", "Image 10x verify interval", "画像10回検証間隔", "图片10次校验间隔", "فاصل التحقق 10 مرات للصورة") +
        "=${imageVerifyThirdIntervalMs}ms, " +
        t("터치표시", "Touch viz", "タッチ表示", "触摸显示", "إظهار اللمس") +
        "=" + onOff(touchVizEnabled)
    )
    sb.appendLine("usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}]")
    sb.appendLine(t("마커 개수", "Total markers", "マーカー数", "标记数量", "عدد العلامات") + "=${snap.size}")
    sb.appendLine("-----")

    fun lineFor(m: Marker): List<String> {
      val ux = (m.xPx - usable.left).coerceAtLeast(0)
      val uy = (m.yPx - usable.top).coerceAtLeast(0)
      val base = mutableListOf<String>()

      val kindLabel = I18n.markerKindName(lang, m.kind)
      val head =
        when (m.kind) {
          "click" -> {
            val r = clickRankByIndex[m.index]
            if (r != null) "${kindLabel} ${r}${t("번", "#", "番", "号", "")}" else kindLabel
          }
          else -> kindLabel
        }
      base.add(head)
      base.add(fmtPx(ux, uy))

      // 공통
      base.add(t("지연", "Delay", "遅延", "延迟", "التأخير") + "=${m.delayMs}ms")
      base.add(t("랜덤지연", "Random delay", "ランダム遅延", "随机延迟", "تأخير عشوائي") + "=${m.jitterPct}%")
      base.add(t("누름", "Press", "押下", "按压", "الضغط") + "=${m.pressMs}ms")

      // 랜덤 실행(대상만 저장되지만, 현재 값 그대로 표기)
      base.add(t("랜덤실행", "Random exec", "ランダム実行", "随机执行", "تنفيذ عشوائي") + "=${onOff(m.randomClickUse)}(${execProbabilityPercent}%)")

      // 색상 조건부 실행
      if (m.useColor && m.colorIndex != 0) {
        base.add(t("색상조건", "Color condition", "色条件", "颜色条件", "شرط اللون") + "=idx:${m.colorIndex}")
      }

      when (m.kind) {
        "swipe" -> {
          base.add(t("스와이프모드", "Swipe mode", "スワイプ方式", "滑动方式", "وضع السحب") + "=${m.swipeMode}")
          if (m.toIndex != 0) base.add("toIndex=${m.toIndex}")
          base.add("moveUpMs=${m.moveUpMs}")
        }
        "module" -> {
          base.add(t("방향", "Direction", "方向", "方向", "اتجاه") + "=${I18n.dirName(lang, m.moduleDir)}")
          if (m.moduleLenPx > 0) base.add(t("길이", "Length", "距離", "长度", "الطول") + "=${m.moduleLenPx}px")
          if (m.moduleMoveUpMs > 0) base.add("moveUpMs=${m.moduleMoveUpMs}")
          base.add("soloExec=${onOff(m.moduleSoloExec)}")
          base.add("dirMode=${m.moduleDirMode}")
          base.add("pattern=${m.modulePatternV2}")
        }
        "color_module" -> {
          base.add(t("검색색상", "Color", "色", "颜色", "لون") + "=${String.format("#%02X%02X%02X", m.colorR.coerceAtLeast(0), m.colorG.coerceAtLeast(0), m.colorB.coerceAtLeast(0))}")
          if (m.colorCheckXPx >= 0 && m.colorCheckYPx >= 0) base.add(t("체크좌표", "Check coord", "チェック座標", "检查坐标", "إحداثيات الفحص") + "=${fmtPx(m.colorCheckXPx, m.colorCheckYPx)}")
          base.add(t("정확도", "Accuracy", "精度", "准确度", "الدقة") + "=${m.colorAccuracyPct}%")
        }
        "image_module" -> {
          base.add(t("검색이미지", "Template", "テンプレ", "模板", "القالب") + "=${m.imageTemplateFile.ifBlank { "-" }}")
          if (m.imageStartXPx >= 0 && m.imageStartYPx >= 0 && m.imageEndXPx >= 0 && m.imageEndYPx >= 0) {
            base.add(fmtRegion(m.imageStartXPx, m.imageStartYPx, m.imageEndXPx, m.imageEndYPx))
          }
          base.add(t("정확도", "Accuracy", "精度", "准确度", "الدقة") + "=${m.imageAccuracyPct}%")
          base.add("tplSize=${m.imageW}x${m.imageH}")
          val pos =
            when (m.imageClickMode) {
              1 -> t("찾은이미지중앙", "Found center", "検出中心", "找到中心", "مركز التطابق")
              2 -> t("소리내기", "Ringtone", "着信音", "铃声", "نغمة الرنين")
              3 -> t("진동하기", "Vibrate", "バイブ", "震动", "اهتزاز")
              else -> t("마커위치", "Marker", "マーカー", "标记", "العلامة")
            }
          base.add(t("클릭위치", "Click position", "クリック位置", "点击位置", "موضع النقر") + "=$pos")
        }
        "solo_main", "solo_item" -> {
          if (m.kind == "solo_item") {
            base.add("parentIndex=${m.parentIndex}")
          }
          if (m.soloLabel.isNotBlank()) base.add(t("라벨", "Label", "ラベル", "标签", "الملصق") + "=${m.soloLabel}")
          if (m.kind == "solo_main") {
            base.add(t("실행전지연", "Pre-delay", "実行前遅延", "执行前延迟", "تأخير قبل التشغيل") + "=${m.soloStartDelayMs}ms")
            base.add(t("콤보", "Combo", "コンボ", "连击", "كومبو") + "=${m.soloComboCount}")
          }

          // 클릭실행확인(이미지)
          if (m.soloVerifyUse && m.soloVerifyTemplateFile.isNotBlank()) {
            base.add(t("클릭실행확인", "Click verify", "クリック確認", "点击确认", "تأكيد النقر") + "=ON")
            // (변경) 실행확인 방식은 1가지로 통일
            base.add(t("방식", "Mode", "方式", "方式", "الوضع") + "=" + I18n.soloVerifyModeFailRetry(lang))
            // (변경) 단독 클릭실행확인은 이미지 매칭을 5회(40ms 간격)로 다수결 판정
            base.add(t("이미지확인", "Image check", "画像確認", "图片确认", "فحص الصورة") + "=5x(40ms)")
            base.add(t("이미지", "Template", "テンプレ", "模板", "القالب") + "=${m.soloVerifyTemplateFile}")
            if (m.soloVerifyStartXPx >= 0 && m.soloVerifyStartYPx >= 0 && m.soloVerifyEndXPx >= 0 && m.soloVerifyEndYPx >= 0) {
              base.add(fmtRegion(m.soloVerifyStartXPx, m.soloVerifyStartYPx, m.soloVerifyEndXPx, m.soloVerifyEndYPx))
            }
            base.add(t("정확도", "Accuracy", "精度", "准确度", "الدقة") + "=${m.soloVerifyAccuracyPct}%")
            base.add("tplSize=${m.soloVerifyW}x${m.soloVerifyH}")

            // (추가) 이미지가 "없으면" 재개(goto) 대상 + (옵션) 실행전 클릭 좌표
            val gotoTarget = try { m.soloVerifyGotoOnStopMissing } catch (_: Throwable) { 0 }
            base.add(
              t("재개", "Resume", "再開", "继续", "استئناف") + "→" +
                if (gotoTarget == 0) t("단독 종료", "End solo", "単独終了", "结束单独", "إنهاء الفردي")
                else "idx=$gotoTarget"
            )
            val hasPre = !(m.soloPreClickXPx == -1 && m.soloPreClickYPx == -1)
            if (gotoTarget != 0) {
              if (m.soloPreClickUse && hasPre) {
                base.add(I18n.soloPreClickLabel(lang) + "=" + onOff(true) + " " + fmtPx(m.soloPreClickXPx, m.soloPreClickYPx))
              } else {
                base.add(I18n.soloPreClickLabel(lang) + "=" + onOff(false))
              }
            }
          }
        }
      }

      // (요청) 마커 1개당 시작 부분에 "마우스" 표시로 구분
      return listOf(" - 🖱 " + base.joinToString(" | "))
    }

    // 그룹 출력(사용자가 보는 "7가지 마커 종류" 중심)
    val orderKinds = listOf("click", "independent", "swipe", "solo_main", "module", "color_module", "image_module")
    for (k in orderKinds) {
      val list = snap.filter { it.kind == k }.sortedBy { it.index }
      if (list.isEmpty()) continue
      sb.appendLine("[${I18n.markerKindName(lang, k)}] (${list.size})")
      for (m in list) {
        for (ln in lineFor(m)) sb.appendLine(ln)
      }
      sb.appendLine()
    }

    // 링/서브 등 기타
    val others = snap.filter { it.kind !in orderKinds }.sortedBy { it.index }
    if (others.isNotEmpty()) {
      sb.appendLine("[" + t("기타", "Others", "その他", "其他", "أخرى") + "] (${others.size})")
      for (m in others) {
        for (ln in lineFor(m)) sb.appendLine(ln)
      }
      sb.appendLine()
    }

    sb.appendLine("-----")
    sb.appendLine(t("※ 좌표는 네비게이션바/컷아웃을 제외한 usable 기준(px)입니다.", "※ Coordinates are in usable(px) (excluding system bars).", "※ 座標は usable(px) 基準です。", "※ 坐标以 usable(px) 为准。", "※ الإحداثيات وفق usable(px)."))
    return sb.toString()
  }

  private fun openAppReview() {
    val pkg = packageName
    try {
      startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return
    } catch (_: Throwable) {}
    try {
      startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {}
  }

  private fun shareApp() {
    // (요청) "앱 공유" 화면(chooser)이 접근성 툴바보다 위에 떠야 한다.
    // 접근성 툴바(TYPE_ACCESSIBILITY_OVERLAY)가 항상 최상단에 오기 때문에,
    // 공유 시작 직전에 툴바를 숨기고, chooser가 타겟을 선택(또는 취소)하면 다시 표시한다.
    val pkg = packageName
    val url = "https://play.google.com/store/apps/details?id=$pkg"
    val lang = I18n.langFromPrefs(prefs())
    val text =
      when (lang.lowercase()) {
        "en" -> "Share app: $url"
        "ja" -> "アプリ共有: $url"
        "zh" -> "分享应用: $url"
        "ar" -> "مشاركة التطبيق: $url"
        else -> "앱 공유: $url"
      }
    try {
      // 메뉴창(오버레이)이 남아있으면 chooser 위를 덮을 수 있으므로 먼저 닫는다.
      try { removeSettingsMenuOverlay() } catch (_: Throwable) {}

      // 툴바 숨김 + 복구 플래그
      try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
      try {
        prefs().edit().putBoolean("flutter.restore_toolbar_after_share", true).apply()
      } catch (_: Throwable) {}

      val i =
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_TEXT, text)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

      // chooser 결과를 받으면(대상 선택/취소) 툴바를 복구한다.
      // API 22+에서 IntentSender를 받을 수 있다.
      val actionShareDone = "$packageName.action.SHARE_CHOOSER_DONE"
      try {
        // 이전 receiver가 남아있으면 정리
        val old = shareChooserReceiver
        if (old != null) {
          try { unregisterReceiver(old) } catch (_: Throwable) {}
          shareChooserReceiver = null
        }
      } catch (_: Throwable) {}

      val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != actionShareDone) return
            // 1회성: 해제
            try {
              val r = shareChooserReceiver
              if (r != null) {
                try { unregisterReceiver(r) } catch (_: Throwable) {}
              }
            } catch (_: Throwable) {}
            shareChooserReceiver = null
            // 복구 플래그 해제 + 툴바 표시(pop)
            try {
              prefs().edit().putBoolean("flutter.restore_toolbar_after_share", false).apply()
            } catch (_: Throwable) {}
            try {
              // (중요) requestShowToolbar는 ref-count(pop) 역할
              AutoClickAccessibilityService.requestShowToolbar()
            } catch (_: Throwable) {}
          }
        }
      shareChooserReceiver = receiver
      try {
        val f = IntentFilter().apply { addAction(actionShareDone) }
        if (Build.VERSION.SDK_INT >= 33) {
          registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
          @Suppress("DEPRECATION")
          registerReceiver(receiver, f)
        }
      } catch (_: Throwable) {
        shareChooserReceiver = null
      }

      val chooser =
        if (Build.VERSION.SDK_INT >= 22 && shareChooserReceiver != null) {
          val pi =
            PendingIntent.getBroadcast(
              this,
              0,
              Intent(actionShareDone).setPackage(packageName),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
          Intent.createChooser(i, I18n.shareApp(lang), pi.intentSender)
        } else {
          Intent.createChooser(i, I18n.shareApp(lang))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

      startActivity(chooser)
    } catch (_: Throwable) {}
  }

  private fun sendFeedbackEmail() {
    val lang = I18n.langFromPrefs(prefs())
    val subject =
      when (lang.lowercase()) {
        "en" -> "AutoClick Feedback"
        "ja" -> "オートクリック フィードバック"
        "zh" -> "自动点击 反馈"
        "ar" -> "ملاحظات"
        else -> "오토클릭짱 피드백"
      }
    val body =
      buildString {
        appendLine("내용을 입력해주세요:")
        appendLine()
        appendLine("-----")
        try {
          val pi = packageManager.getPackageInfo(packageName, 0)
          appendLine("versionName=${pi.versionName}")
          @Suppress("DEPRECATION")
          appendLine("versionCode=${pi.versionCode}")
        } catch (_: Throwable) {}
        appendLine("package=$packageName")
      }
    try {
      val i =
        Intent(Intent.ACTION_SENDTO).apply {
          data = android.net.Uri.parse("mailto:")
          putExtra(Intent.EXTRA_SUBJECT, subject)
          putExtra(Intent.EXTRA_TEXT, body)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      startActivity(Intent.createChooser(i, I18n.feedback(lang)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {}
  }

  private fun openPrivacyPolicy() {
    try {
      startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {}
  }

  private fun showSettingsMenuOverlay() {
    val wmLocal = wm ?: return
    if (settingsMenuRoot != null) {
      try { bringOverlayViewToFrontSafe(settingsMenuRoot, settingsMenuLp) } catch (_: Throwable) {}
      return
    }
    val lang = I18n.langFromPrefs(prefs())
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
        setBackgroundResource(R.drawable.overlay_panel_bg_opaque)
      }
    val drag =
      TextView(this).apply {
        text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dpToPx(6f))
      }
    val tvTitle =
      TextView(this).apply {
        text = I18n.menu(lang)
        setTextColor(Color.parseColor("#111827"))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 15f
        setPadding(0, 0, 0, dpToPx(8f))
      }
    fun mk(text: String, onClick: () -> Unit): Button =
      Button(this).apply {
        isAllCaps = false
        this.text = text
        setOnClickListener { onClick() }
      }

    root.addView(drag)
    root.addView(tvTitle)
    root.addView(mk(I18n.help(lang)) {
      showSettingsHelpOverlay(lang)
    })
    root.addView(mk(I18n.scripter(lang)) { showSettingsTextOverlay(I18n.scripter(lang), buildScripterText(lang)) })
    root.addView(mk(I18n.rateApp(lang)) { openAppReview() })
    root.addView(mk(I18n.shareApp(lang)) { shareApp() })
    root.addView(mk(I18n.feedback(lang)) { sendFeedbackEmail() })
    root.addView(mk(I18n.credits(lang)) {
      showSettingsTextOverlay(I18n.credits(lang), "오토클릭짱\n\n- Android 접근성 기반 자동 클릭/스와이프 도구\n- © 2026")
    })
    root.addView(mk(I18n.privacyPolicy(lang)) { openPrivacyPolicy() })
    root.addView(mk(I18n.close(lang)) { removeSettingsMenuOverlay() })

    val usable = getUsableRectPx()
    // (요청) 메뉴는 "메뉴바 설정창(패널)"의 상단 버튼에서 열리므로, 패널 위치를 우선 기준으로 둔다.
    val baseLp = panelLp ?: screenSettingsLp
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (baseLp?.x ?: usable.left) + dpToPx(10f)
        y = (baseLp?.y ?: usable.top) + dpToPx(10f)
      }

    drag.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lpLocal = settingsMenuLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lpLocal.x
              startY = lpLocal.y
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dx = (event.rawX - downX).roundToInt()
              val dy = (event.rawY - downY).roundToInt()
              lpLocal.x = startX + dx
              lpLocal.y = startY + dy
              clampOverlayToUsable(root, lpLocal)
              try { wmLocal.updateViewLayout(root, lpLocal) } catch (_: Throwable) {}
              return true
            }
            else -> return false
          }
        }
      }
    )

    settingsMenuRoot = root
    settingsMenuLp = lp
    try { wmLocal.addView(root, lp) } catch (_: Throwable) {
      settingsMenuRoot = null
      settingsMenuLp = null
    }
    clampOverlayToUsable(root, lp)
  }

  private fun showScreenSettingsOverlay() {
    val wmLocal = wm ?: return
    if (screenSettingsRoot != null) {
      updateScreenSettingsLayout()
      return
    }

    // (요청) 메뉴바설정창(화면설정) 오픈 시 접근성 메뉴툴바 숨김
    try {
      AutoClickAccessibilityService.requestHideToolbar()
    } catch (_: Throwable) {}

    pushToolbarHiddenByModalInternal()

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_screen_settings, null)
    screenSettingsRoot = root
    makeWindowOpaque(root)

    val tvTitle = root.findViewById<TextView>(R.id.tvTitle)
    val tvToolbarOpacity = root.findViewById<TextView>(R.id.tvToolbarOpacity)
    val sbToolbarOpacity = root.findViewById<SeekBar>(R.id.sbToolbarOpacity)
    val tvToolbarScale = root.findViewById<TextView>(R.id.tvToolbarScale)
    val sbToolbarScale = root.findViewById<SeekBar>(R.id.sbToolbarScale)
    val tvObjectScale = root.findViewById<TextView>(R.id.tvObjectScale)
    val sbObjectScale = root.findViewById<SeekBar>(R.id.sbObjectScale)
    val tvClickPressMs = root.findViewById<TextView>(R.id.tvClickPressMs)
    val sbClickPressMs = root.findViewById<SeekBar>(R.id.sbClickPressMs)
    val tvExecProbability = root.findViewById<TextView>(R.id.tvExecProbability)
    val sbExecProbability = root.findViewById<SeekBar>(R.id.sbExecProbability)
    val tvRandomDelayPct = root.findViewById<TextView>(R.id.tvRandomDelayPct)
    val sbRandomDelayPct = root.findViewById<SeekBar>(R.id.sbRandomDelayPct)
    val tvImageVerifyInterval = root.findViewById<TextView>(R.id.tvImageVerifyInterval)
    val sbImageVerifyInterval = root.findViewById<SeekBar>(R.id.sbImageVerifyInterval)
    val cbTouchViz = root.findViewById<CheckBox>(R.id.cbTouchViz)
    val tvTouchVizHint = root.findViewById<TextView>(R.id.tvTouchVizHint)
    val rowLogDebug = root.findViewById<View>(R.id.rowLogDebug)
    val cbLogView = root.findViewById<CheckBox>(R.id.cbLogView)
    val cbDebugAdb = root.findViewById<CheckBox>(R.id.cbDebugAdb)

    // i18n 적용(제목/캡션/RTL)
    val lang = I18n.langFromPrefs(prefs())
    try { root.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
    try { tvTitle.text = I18n.screenSettingsTitle(lang) } catch (_: Throwable) {}
    try { cbTouchViz.text = I18n.touchViz(lang) } catch (_: Throwable) {}
    try { tvTouchVizHint.text = I18n.touchVizHint(lang) } catch (_: Throwable) {}
    try { cbLogView.text = I18n.logView(lang) } catch (_: Throwable) {}
    try { cbDebugAdb.text = I18n.debugging(lang) } catch (_: Throwable) {}
    // 화면설정 하단 버튼은 "닫기"
    try { root.findViewById<android.widget.Button>(R.id.btnClose).text = I18n.close(lang) } catch (_: Throwable) {}

    // (요청) 화면설정의 일부 항목은 기본 숨김(고급설정), 타이틀 10회 터치로 표시/설정 가능
    fun setAdvancedVisible(visible: Boolean) {
      val vis = if (visible) View.VISIBLE else View.GONE
      try { tvClickPressMs.visibility = vis } catch (_: Throwable) {}
      try { sbClickPressMs.visibility = vis } catch (_: Throwable) {}
      try { tvImageVerifyInterval.visibility = vis } catch (_: Throwable) {}
      try { sbImageVerifyInterval.visibility = vis } catch (_: Throwable) {}
      try { tvExecProbability.visibility = vis } catch (_: Throwable) {}
      try { sbExecProbability.visibility = vis } catch (_: Throwable) {}
      try { tvRandomDelayPct.visibility = vis } catch (_: Throwable) {}
      try { sbRandomDelayPct.visibility = vis } catch (_: Throwable) {}
      try { cbTouchViz.visibility = vis } catch (_: Throwable) {}
      try { tvTouchVizHint.visibility = vis } catch (_: Throwable) {}
      try { rowLogDebug.visibility = vis } catch (_: Throwable) {}
    }

    var advancedVisible =
      try { prefs().getBoolean("flutter.screen_settings_advanced_visible", false) } catch (_: Throwable) { false }
    setAdvancedVisible(advancedVisible)

    var titleTapCount = 0
    var titleFirstTapAt = 0L
    tvTitle.setOnClickListener {
      val now = android.os.SystemClock.uptimeMillis()
      if (titleFirstTapAt <= 0L || now - titleFirstTapAt > 4500L) {
        titleFirstTapAt = now
        titleTapCount = 0
      }
      titleTapCount++
      if (titleTapCount >= 10) {
        titleTapCount = 0
        titleFirstTapAt = 0L
        advancedVisible = !advancedVisible
        try { prefs().edit().putBoolean("flutter.screen_settings_advanced_visible", advancedVisible).apply() } catch (_: Throwable) {}
        setAdvancedVisible(advancedVisible)
        try {
          updateNotification(if (advancedVisible) "고급설정 표시" else "고급설정 숨김")
        } catch (_: Throwable) {}
      }
    }

    // 1) 툴바 투명도(30~150)
    val op = overlayOpacityPercent.coerceIn(30, 150)
    tvToolbarOpacity.text = I18n.toolbarOpacity(lang, op)
    sbToolbarOpacity.max = 120
    sbToolbarOpacity.progress = (op - 30).coerceIn(0, 120)
    sbToolbarOpacity.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val v = (30 + progress).coerceIn(30, 150)
          overlayOpacityPercent = v
          prefs().edit().putInt("flutter.toolbar_opacity_percent", v).apply()
          tvToolbarOpacity.text = I18n.toolbarOpacity(lang, v)
          applyOverlayOpacity()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 2) 툴바 가로크기(50~200)
    val sx0 = toolbarScaleXPercent.coerceIn(50, 200)
    tvToolbarScale.text = I18n.toolbarScaleX(lang, sx0)
    sbToolbarScale.max = 150
    sbToolbarScale.progress = (sx0 - 50).coerceIn(0, 150)
    sbToolbarScale.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val v = (50 + progress).coerceIn(50, 200)
          toolbarScaleXPercent = v
          prefs().edit().putInt("flutter.toolbar_scale_x_percent", v).apply()
          tvToolbarScale.text = I18n.toolbarScaleX(lang, v)
          applyToolbarScaleX()
          // (요청) 접근성 메뉴툴바도 동일 %를 기준으로 가로/세로 크기 자동 조절
          try {
            AutoClickAccessibilityService.requestRefreshToolbarLayout()
          } catch (_: Throwable) {}
          updateTouchOverlayLayout()
          updatePanelLayout()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 3) 객체 크기(UI는 -50~+100 -> 내부 50~200)
    val delta0 = (markerScalePercent.coerceIn(50, 200) - 100).coerceIn(-50, 100)
    tvObjectScale.text = I18n.objectScale(lang, delta0)
    sbObjectScale.max = 150
    sbObjectScale.progress = (delta0 + 50).coerceIn(0, 150)
    sbObjectScale.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val delta = (progress - 50).coerceIn(-50, 100)
          val scale = (100 + delta).coerceIn(50, 200)
          markerScalePercent = scale
          prefs().edit().putInt("flutter.marker_scale_percent", scale).apply()
          tvObjectScale.text = I18n.objectScale(lang, delta)
          refreshMarkerViews()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 4) 클릭 pressMs(10~500)
    val p0 = clickPressMsGlobal.coerceIn(10, 500)
    tvClickPressMs.text = I18n.clickPressMs(lang, p0)
    sbClickPressMs.max = 490
    sbClickPressMs.progress = (p0 - 10).coerceIn(0, 490)
    sbClickPressMs.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val v = (10 + progress).coerceIn(10, 500)
          clickPressMsGlobal = v
          prefs().edit().putInt("flutter.click_press_ms", v).apply()
          tvClickPressMs.text = I18n.clickPressMs(lang, v)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 5) 실행확률%(0~100) - 기본 80%
    val prob0 = execProbabilityPercent.coerceIn(0, 100)
    tvExecProbability.text = I18n.execProbability(lang, prob0)
    sbExecProbability.max = 100
    sbExecProbability.progress = prob0.coerceIn(0, 100)
    sbExecProbability.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val v = progress.coerceIn(0, 100)
          execProbabilityPercent = v
          prefs().edit().putInt("flutter.exec_probability_percent", v).apply()
          tvExecProbability.text = I18n.execProbability(lang, v)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 6) 랜덤지연%(0~100) - 기본 50%
    val rd0 = randomDelayPctGlobal.coerceIn(0, 100)
    tvRandomDelayPct.text = I18n.randomDelayPct(lang, rd0)
    sbRandomDelayPct.max = 100
    sbRandomDelayPct.progress = rd0
    sbRandomDelayPct.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val v = progress.coerceIn(0, 100)
          randomDelayPctGlobal = v
          prefs().edit().putInt("flutter.random_delay_pct", v).apply()
          tvRandomDelayPct.text = I18n.randomDelayPct(lang, v)
          // (요청) 전역 랜덤지연 값 변경 시 모든 마커의 jitterPct도 동시에 변경
          applyGlobalRandomDelayPctToAllMarkers(v)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 7) 이미지 3차검증 인터벌(ms)
    val iv0 = imageVerifyThirdIntervalMs.coerceIn(0, 1000)
    tvImageVerifyInterval.text = I18n.imageVerifyInterval(lang, iv0)
    sbImageVerifyInterval.max = 1000
    sbImageVerifyInterval.progress = iv0
    sbImageVerifyInterval.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          val v = progress.coerceIn(0, 1000)
          imageVerifyThirdIntervalMs = v
          prefs().edit().putInt("flutter.image_verify_third_interval_ms", v).apply()
          tvImageVerifyInterval.text = I18n.imageVerifyInterval(lang, v)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    // 8) 파란 점(터치 표시) ON/OFF
    cbTouchViz.isChecked = touchVizEnabled
    cbTouchViz.setOnCheckedChangeListener { _, isChecked ->
      touchVizEnabled = isChecked
      prefs().edit().putBoolean("flutter.touch_viz_enabled", isChecked).apply()
      // 실행 중에 껐을 때 즉시 숨김
      try {
        if (!isChecked) removeTouchVizOverlay()
        else if (macroRunning) ensureTouchVizOverlay()
      } catch (_: Throwable) {}
    }

    // 9) 로그보기(체크 시 실행 로그창 표시)
    try {
      cbLogView.isChecked = prefs().getBoolean("flutter.exec_log_enabled", false)
      cbLogView.setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
          // (요청) 로그보기 선택 시 메인 설정창/화면설정창을 닫고 로그창만 띄우기
          try { removeColorPanel() } catch (_: Throwable) {}
          try { removeScreenSettingsOverlay() } catch (_: Throwable) {}
          setExecLogEnabled(true, updatePanelCheckbox = false)
        } else {
          setExecLogEnabled(false, updatePanelCheckbox = false)
        }
      }
    } catch (_: Throwable) {}

    // 10) 디버깅(ADB logcat로 실행 마커 정보 스트리밍)
    try {
      cbDebugAdb.isChecked = prefs().getBoolean("flutter.debug_adb_enabled", false)
      cbDebugAdb.setOnCheckedChangeListener { _, isChecked ->
        debugAdbEnabled = isChecked
        try { prefs().edit().putBoolean("flutter.debug_adb_enabled", isChecked).apply() } catch (_: Throwable) {}
        try { Log.i("ATX_STREAM", "debugAdbEnabled=$isChecked") } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    root.findViewById<View>(R.id.btnClose).setOnClickListener { removeScreenSettingsOverlay() }

    val usable = getUsableRectPx()
    val maxH = (usable.height() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.height())
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        // 내용에 맞게 높이 결정(추후 measuredHeight 기반으로 clamp)
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      )
    lp.gravity = Gravity.TOP or Gravity.START
    screenSettingsLp = lp
    updateScreenSettingsLayout()

    try {
      wmLocal.addView(root, lp)
    } catch (_: Throwable) {
      screenSettingsRoot = null
      screenSettingsLp = null
      popToolbarHiddenByModalInternal()
      return
    }

    // (요청) 닫기 버튼 아래 "한줄" 여유만 두고 창 크기 맞추기:
    // - 콘텐츠(measuredHeight)에 맞춰 창 높이를 줄이되, 화면 밖으로 나가면 maxH로 clamp
    val extraPx = dpToPx(8f) // 닫기 버튼 아래 약간의 여유
    fun fitHeightOnce() {
      val lpLocal = screenSettingsLp ?: return
      val h = root.measuredHeight.takeIf { it > 0 } ?: root.height
      if (h <= 0) return
      lpLocal.height = minOf((h + extraPx).coerceAtLeast(dpToPx(260f)), maxH)
      try {
        wmLocal.updateViewLayout(root, lpLocal)
      } catch (_: Throwable) {}
      try {
        updateScreenSettingsLayout()
      } catch (_: Throwable) {}
    }

    // add 직후/레이아웃 후 1~2회 보정(측정값이 0일 수 있음)
    Handler(Looper.getMainLooper()).post { try { fitHeightOnce() } catch (_: Throwable) {} }
    Handler(Looper.getMainLooper()).postDelayed({ try { fitHeightOnce() } catch (_: Throwable) {} }, 32L)
  }

  private fun updateScreenSettingsLayout() {
    val root = screenSettingsRoot ?: return
    val lp = screenSettingsLp ?: return
    val usable = getUsableRectPx()

    val w = if (root.width > 0) root.width else (root.measuredWidth.takeIf { it > 0 } ?: dpToPx(320f))
    val h = if (root.height > 0) root.height else (root.measuredHeight.takeIf { it > 0 } ?: dpToPx(360f))

    // (중요) 패널이 usable보다 큰 경우(가로모드 등)에는 upperBound < lowerBound가 될 수 있어
    // Kotlin coerceIn()이 예외를 던진다. empty range를 방지한다.
    val maxX = (usable.right - w).coerceAtLeast(usable.left)
    val maxY = (usable.bottom - h).coerceAtLeast(usable.top)
    val desiredX = (usable.centerX() - w / 2).coerceIn(usable.left, maxX)
    val desiredY = (usable.centerY() - h / 2).coerceIn(usable.top, maxY)

    lp.x = desiredX
    lp.y = desiredY
    try {
      wm?.updateViewLayout(root, lp)
    } catch (_: Throwable) {}

    if (root.width == 0 || root.height == 0) {
      Handler(Looper.getMainLooper()).post {
        try {
          updateScreenSettingsLayout()
        } catch (_: Throwable) {}
      }
    }
  }

  private fun removeScreenSettingsOverlay() {
    val root = screenSettingsRoot ?: return
    screenSettingsRoot = null
    screenSettingsLp = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
    // (요청) 화면설정 닫기 시 접근성 메뉴툴바 복구
    try {
      AutoClickAccessibilityService.requestShowToolbar()
    } catch (_: Throwable) {}
  }

  // ---------------- 메크로(파일) 저장/열기 ----------------
  private fun atxDir(): File {
    val base = getExternalFilesDir(null) ?: filesDir
    val d = File(base, "atxfile")
    if (!d.exists()) d.mkdirs()
    return d
  }

  private fun atxImgDir(): File {
    val base = getExternalFilesDir(null) ?: filesDir
    val d = File(base, "atximg")
    if (!d.exists()) d.mkdirs()
    return d
  }

  private fun generateAutoImageFileName(): String {
    val dir = atxImgDir()
    val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
    repeat(20) {
      val stamp = fmt.format(java.util.Date())
      val rnd = (1000 + Random.nextInt(9000))
      val name = "${stamp}_${rnd}.png"
      val f = File(dir, name)
      if (!f.exists()) return name
    }
    return "${System.currentTimeMillis()}_${Random.nextInt(100000, 999999)}.png"
  }

  private fun listMacroFiles(): List<File> {
    val d = atxDir()
    val arr = d.listFiles() ?: return emptyList()
    return arr.filter { it.isFile }.sortedByDescending { it.lastModified() }
  }

  private fun sanitizeFilename(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) s = "atx_macro.jws"
    s = s.replace("/", "_").replace("\\", "_").replace("..", "_")
    if (!s.contains(".")) s += ".jws"
    return s
  }

  private fun updateMacroOverlayLayout(root: View?, lp: WindowManager.LayoutParams?) {
    val r = root ?: return
    val p = lp ?: return
    val usable = getUsableRectPx()

    val w = if (r.width > 0) r.width else (r.measuredWidth.takeIf { it > 0 } ?: dpToPx(340f))
    val h = if (r.height > 0) r.height else (r.measuredHeight.takeIf { it > 0 } ?: dpToPx(420f))

    val tlp = toolbarLp
    val toolbarX = tlp?.x ?: usable.left
    val toolbarY = tlp?.y ?: usable.top
    val toolbarH = (toolbarRoot?.height ?: toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
    var x = toolbarX
    var y = toolbarY + toolbarH + dpToPx(6f)
    x = x.coerceIn(usable.left, (usable.right - w).coerceAtLeast(usable.left))
    y = y.coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))

    p.x = x
    p.y = y
    try {
      wm?.updateViewLayout(r, p)
    } catch (_: Throwable) {}

    if (r.width == 0 || r.height == 0) {
      Handler(Looper.getMainLooper()).post {
        try {
          updateMacroOverlayLayout(r, p)
        } catch (_: Throwable) {}
      }
    }
  }

  private fun saveMacroToFile(filename: String): Boolean {
    return try {
      ensureMarkersLoadedAndShown()
      syncMarkerPositionsToPrefs()
      // (중요) 저장 시점의 화면모드/크기를 base로 확정(왕복 회전 시 드리프트 방지 + 메크로에 기록)
      saveMarkersToPrefs(markersCache, updateBase = true)
      val p0 = prefs()
      val markersJson = p0.getString("flutter.markers", "[]") ?: "[]"
      val markersArr = JSONArray(markersJson) // validate

      // (요청) 메크로 저장 파일에 "기본설정(화면설정)" 값도 함께 기록
      val settings = JSONObject()
      try { settings.put("flutter.toolbar_opacity_percent", overlayOpacityPercent.coerceIn(30, 150)) } catch (_: Throwable) {}
      try { settings.put("flutter.toolbar_scale_x_percent", toolbarScaleXPercent.coerceIn(50, 200)) } catch (_: Throwable) {}
      try { settings.put("flutter.marker_scale_percent", markerScalePercent.coerceIn(50, 200)) } catch (_: Throwable) {}
      try { settings.put("flutter.click_press_ms", clickPressMsGlobal.coerceIn(10, 500)) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_probability_percent", execProbabilityPercent.coerceIn(0, 100)) } catch (_: Throwable) {}
      try { settings.put("flutter.random_delay_pct", randomDelayPctGlobal.coerceIn(0, 100)) } catch (_: Throwable) {}
      try { settings.put("flutter.image_verify_third_interval_ms", imageVerifyThirdIntervalMs.coerceIn(0, 1000)) } catch (_: Throwable) {}
      try { settings.put("flutter.touch_viz_enabled", touchVizEnabled) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_log_enabled", execLogEnabled) } catch (_: Throwable) {}
      // (추가) 메크로별 설정: 중지조건/좌표계/언어/로그 상세
      try { settings.put("flutter.stop_mode", p0.getString("flutter.stop_mode", "infinite") ?: "infinite") } catch (_: Throwable) {}
      try { settings.put("flutter.stop_time_sec", p0.getInt("flutter.stop_time_sec", 0).coerceAtLeast(0)) } catch (_: Throwable) {}
      try { settings.put("flutter.stop_cycles", p0.getInt("flutter.stop_cycles", 1).coerceAtLeast(1)) } catch (_: Throwable) {}
      try { settings.put("flutter.lang", p0.getString("flutter.lang", "ko") ?: "ko") } catch (_: Throwable) {}
      try { settings.put(MARKER_COORD_SPACE_KEY, p0.getString(MARKER_COORD_SPACE_KEY, MARKER_COORD_SPACE_USABLE_LEGACY) ?: MARKER_COORD_SPACE_USABLE_LEGACY) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_log_paused", p0.getBoolean("flutter.exec_log_paused", false)) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_log_summary_mode", p0.getBoolean("flutter.exec_log_summary_mode", false)) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_log_minimized", p0.getBoolean("flutter.exec_log_minimized", false)) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_log_w", p0.getInt("flutter.exec_log_w", dpToPx(320f)).coerceAtLeast(120)) } catch (_: Throwable) {}
      try { settings.put("flutter.exec_log_h", p0.getInt("flutter.exec_log_h", dpToPx(520f)).coerceAtLeast(160)) } catch (_: Throwable) {}
      try {
        for (i in 1..7) {
          settings.put("flutter.exec_log_filter_$i", p0.getBoolean("flutter.exec_log_filter_$i", true))
        }
      } catch (_: Throwable) {}

      // (요청) 메크로 저장 파일에 현재 화면모드(ROT 0/90/180/270) + base 화면크기 저장
      try {
        val s = getScreenSize()
        settings.put(MARKER_BASE_ROT_KEY, currentRotation())
        settings.put(MARKER_BASE_W_KEY, s.width.coerceAtLeast(1))
        settings.put(MARKER_BASE_H_KEY, s.height.coerceAtLeast(1))
      } catch (_: Throwable) {}

      val root = JSONObject()
      try { root.put("ver", 3) } catch (_: Throwable) {}
      try { root.put("savedAtMs", System.currentTimeMillis()) } catch (_: Throwable) {}
      root.put("markers", markersArr)
      root.put("screenSettings", settings)

      File(atxDir(), filename).writeText(root.toString(), Charsets.UTF_8)
      true
    } catch (_: Throwable) {
      false
    }
  }

  private fun loadMacroFromFile(filename: String): Boolean {
    return try {
      val f = File(atxDir(), filename)
      if (!f.exists()) return false
      val raw = f.readText(Charsets.UTF_8)
      val txt = raw.trim()

      // (호환) 구버전: 파일이 JSONArray(마커만 저장)인 경우 그대로 처리
      // (신규) JSONObject: { markers: [...], screenSettings: {...} }
      val markersArr: JSONArray
      val settingsObj: JSONObject?
      if (txt.startsWith("[")) {
        markersArr = JSONArray(txt)
        settingsObj = null
      } else {
        val obj = JSONObject(txt)
        val any = obj.opt("markers")
        markersArr =
          when (any) {
            is JSONArray -> any
            is String -> JSONArray(any)
            else -> JSONArray()
          }
        settingsObj = obj.optJSONObject("screenSettings") ?: obj.optJSONObject("settings")
      }

      // markers 적용
      val markersStr = markersArr.toString()
      run {
        val e = prefs().edit()
        e.putString("flutter.markers", markersStr)
        // (중요) 불러온 좌표는 "기준(base) 화면모드" 좌표이므로 base에도 동일하게 저장한다.
        e.putString(MARKERS_BASE_KEY, markersStr)
        // base rot/w/h는 파일에 있으면 그 값을, 없으면 현재 화면 기준으로 세팅(구버전 호환)
        try {
          val cur = getScreenSize()
          val baseRot =
            try {
              val v = settingsObj?.optInt(MARKER_BASE_ROT_KEY, Int.MIN_VALUE) ?: Int.MIN_VALUE
              if (v == Int.MIN_VALUE) currentRotation() else v.coerceIn(0, 3)
            } catch (_: Throwable) {
              currentRotation()
            }
          val baseW =
            try {
              val v = settingsObj?.optInt(MARKER_BASE_W_KEY, Int.MIN_VALUE) ?: Int.MIN_VALUE
              if (v == Int.MIN_VALUE) cur.width else v.coerceAtLeast(1)
            } catch (_: Throwable) {
              cur.width
            }
          val baseH =
            try {
              val v = settingsObj?.optInt(MARKER_BASE_H_KEY, Int.MIN_VALUE) ?: Int.MIN_VALUE
              if (v == Int.MIN_VALUE) cur.height else v.coerceAtLeast(1)
            } catch (_: Throwable) {
              cur.height
            }
          e.putInt(MARKER_BASE_ROT_KEY, baseRot)
          e.putInt(MARKER_BASE_W_KEY, baseW.coerceAtLeast(1))
          e.putInt(MARKER_BASE_H_KEY, baseH.coerceAtLeast(1))
        } catch (_: Throwable) {}
        e.apply()
      }

      // (요청) 불러오기 시 기본설정(화면설정) 값도 적용
      if (settingsObj != null) {
        try {
          val e = prefs().edit()
          fun putIntIfHas(key: String, min: Int, max: Int) {
            if (!settingsObj.has(key)) return
            val v = settingsObj.optInt(key, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) e.putInt(key, v.coerceIn(min, max))
          }
          fun putIntIfHasMin(key: String, min: Int) {
            if (!settingsObj.has(key)) return
            val v = settingsObj.optInt(key, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) e.putInt(key, v.coerceAtLeast(min))
          }
          fun putBoolIfHas(key: String) {
            if (!settingsObj.has(key)) return
            val vAny = settingsObj.opt(key)
            if (vAny is Boolean) e.putBoolean(key, vAny)
            else e.putBoolean(key, settingsObj.optBoolean(key, false))
          }
          fun putStringIfHas(key: String, allowed: Set<String>? = null, maxLen: Int = 48) {
            if (!settingsObj.has(key)) return
            val v = settingsObj.optString(key, "")
            if (v.isBlank()) return
            val vv = if (v.length > maxLen) v.substring(0, maxLen) else v
            if (allowed != null && !allowed.contains(vv)) return
            e.putString(key, vv)
          }

          putIntIfHas("flutter.toolbar_opacity_percent", 30, 150)
          putIntIfHas("flutter.toolbar_scale_x_percent", 50, 200)
          putIntIfHas("flutter.marker_scale_percent", 50, 200)
          putIntIfHas("flutter.click_press_ms", 10, 500)
          putIntIfHas("flutter.exec_probability_percent", 0, 100)
          putIntIfHas("flutter.random_delay_pct", 0, 100)
          putIntIfHas("flutter.image_verify_third_interval_ms", 0, 1000)
          putBoolIfHas("flutter.touch_viz_enabled")
          putBoolIfHas("flutter.exec_log_enabled")
          // (추가) 메크로별 설정
          putStringIfHas("flutter.stop_mode", allowed = setOf("infinite", "time", "cycles"))
          putIntIfHasMin("flutter.stop_time_sec", 0)
          putIntIfHas("flutter.stop_cycles", 1, 1000000)
          putStringIfHas("flutter.lang", allowed = setOf("ko", "en", "ja", "zh", "ar"))
          putStringIfHas(MARKER_COORD_SPACE_KEY, allowed = setOf(MARKER_COORD_SPACE_SCREEN, MARKER_COORD_SPACE_USABLE_LEGACY))
          putBoolIfHas("flutter.exec_log_paused")
          putBoolIfHas("flutter.exec_log_summary_mode")
          putBoolIfHas("flutter.exec_log_minimized")
          putIntIfHas("flutter.exec_log_w", 120, 2000)
          putIntIfHas("flutter.exec_log_h", 160, 2600)
          // (요청) 메크로별 기준 화면모드(base) 복원
          putIntIfHas(MARKER_BASE_ROT_KEY, 0, 3)
          putIntIfHas(MARKER_BASE_W_KEY, 1, 100000)
          putIntIfHas(MARKER_BASE_H_KEY, 1, 100000)
          try {
            for (i in 1..7) putBoolIfHas("flutter.exec_log_filter_$i")
          } catch (_: Throwable) {}
          e.apply()
        } catch (_: Throwable) {}
      }

      // (중요) 불러오기 후에는 이전 "임시(draft)" 수정값이 섞이지 않게 세션을 갱신하여 무효화
      try { bumpImagePickerDraftSession() } catch (_: Throwable) {}

      // UI 설정값 반영(오버레이 스케일/투명도 등)
      try {
        // exec log prefs 포함(요약/필터/작게/크게 등)
        loadExecLogPrefs()
        loadUiPrefs()
        applyOverlayOpacity()
        applyToolbarScaleX()
        updateTouchOverlayLayout()
        updatePanelLayout()
        if (!touchVizEnabled) removeTouchVizOverlay() else if (macroRunning) ensureTouchVizOverlay()
        // 로그보기 on/off 반영(창 표시/숨김 + 체크박스 동기화)
        try { setExecLogEnabled(prefs().getBoolean("flutter.exec_log_enabled", false), updatePanelCheckbox = true) } catch (_: Throwable) {}
      } catch (_: Throwable) {}

      // (버그픽스) 객체크기(markerScalePercent)는 loadUiPrefs()에서 갱신되므로,
      // 그 이전에 refreshMarkerViews()가 실행되면 화면 객체 크기가 기본값으로 남는다.
      // UI prefs를 로드한 뒤에 마커를 다시 로드+리프레시하여 실제 화면에도 반영한다.
      reloadMarkersFromPrefsAndRefresh()
      true
    } catch (_: Throwable) {
      false
    }
  }

  private fun showMacroSaveOverlay() {
    val wmLocal = wm ?: return
    if (macroSaveRoot != null) {
      updateMacroOverlayLayout(macroSaveRoot, macroSaveLp)
      return
    }

    pushToolbarHiddenByModalInternal()

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_macro_save, null)
    macroSaveRoot = root
    makeWindowOpaque(root)

    // i18n 적용(제목/캡션/RTL)
    try { applyLanguageToMacroOverlaysIfOpen() } catch (_: Throwable) {}

    root.findViewById<TextView>(R.id.tvPath).text = atxDir().absolutePath
    val listContainer = root.findViewById<ViewGroup>(R.id.listContainer)
    val etFilename = root.findViewById<EditText>(R.id.etFilename)
    etFilename.setText(prefs().getString("flutter.last_save_name", "atx_macro.jws") ?: "atx_macro.jws")

    fun refreshButtons() {
      listContainer.removeAllViews()
      val files = listMacroFiles()
      for (f in files.take(6)) {
        val b =
          android.widget.Button(this).apply {
            text = f.name
            isAllCaps = false
            try {
              setTextColor(Color.parseColor("#111827"))
            } catch (_: Throwable) {}
            setOnClickListener { etFilename.setText(f.name) }
          }
        listContainer.addView(b)
      }
    }
    refreshButtons()

    root.findViewById<View>(R.id.btnClose).setOnClickListener { removeMacroSaveOverlay() }
    root.findViewById<View>(R.id.btnSave).setOnClickListener {
      val name = sanitizeFilename(etFilename.text?.toString().orEmpty())
      prefs().edit().putString("flutter.last_save_name", name).apply()
      val ok = saveMacroToFile(name)
      if (ok) {
        updateNotification("메크로 저장됨: $name")
        refreshButtons()
        removeMacroSaveOverlay()
      } else {
        updateNotification("메크로 저장 실패")
      }
    }

    val usable = getUsableRectPx()
    val maxH = (usable.height() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.height())
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        // 화면에 다 안 들어오면 스크롤로 보기 위해 높이 제한
        maxH,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      )
    lp.gravity = Gravity.TOP or Gravity.START
    macroSaveLp = lp
    updateMacroOverlayLayout(root, lp)
    try {
      wmLocal.addView(root, lp)
    } catch (_: Throwable) {
      macroSaveRoot = null
      macroSaveLp = null
      popToolbarHiddenByModalInternal()
    }
  }

  private fun removeMacroSaveOverlay() {
    val root = macroSaveRoot ?: return
    macroSaveRoot = null
    macroSaveLp = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
  }

  private fun showMacroOpenOverlay() {
    val wmLocal = wm ?: return
    if (macroOpenRoot != null) {
      updateMacroOverlayLayout(macroOpenRoot, macroOpenLp)
      return
    }

    // (요청) 메크로 열기창 열릴 때 메뉴툴바 숨김
    try { AutoClickAccessibilityService.requestHideToolbar() } catch (_: Throwable) {}
    pushToolbarHiddenByModalInternal()

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_macro_open, null)
    macroOpenRoot = root
    makeWindowOpaque(root)

    // i18n 적용(제목/캡션/RTL)
    try { applyLanguageToMacroOverlaysIfOpen() } catch (_: Throwable) {}

    val lang = I18n.langFromPrefs(prefs())
    root.findViewById<TextView>(R.id.tvPath).text = atxDir().absolutePath

    val btnFav1 = root.findViewById<android.widget.Button>(R.id.btnFav1)
    val btnFav2 = root.findViewById<android.widget.Button>(R.id.btnFav2)
    val btnFav3 = root.findViewById<android.widget.Button>(R.id.btnFav3)
    val cbFavAddToToolbar = root.findViewById<android.widget.CheckBox>(R.id.cbFavAddToToolbar)
    // (중요) 즐겨 버튼은 2줄 표시(즐겨N + 파일명)가 되어야 하므로 singleLine을 확실히 해제한다.
    try {
      for (b in listOf(btnFav1, btnFav2, btnFav3)) {
        try { b.isSingleLine = false } catch (_: Throwable) {}
        try { b.maxLines = 2 } catch (_: Throwable) {}
        try { b.ellipsize = android.text.TextUtils.TruncateAt.END } catch (_: Throwable) {}
        try { b.gravity = Gravity.CENTER } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    fun refreshFavButtons() {
      val p = prefs()
      val f1 = p.getString("flutter.fav1", "") ?: ""
      val f2 = p.getString("flutter.fav2", "") ?: ""
      val f3 = p.getString("flutter.fav3", "") ?: ""
      try {
        Log.i(TAG, "[MacroFav] refreshFavButtons read fav1='$f1' fav2='$f2' fav3='$f3'")
      } catch (_: Throwable) {}
      // 즐겨 버튼은 파일명만 바뀌므로 prefix만 i18n 처리
      fun favLabel(n: Int): String {
        return when (lang.lowercase()) {
          "en" -> "Fav$n"
          "ja" -> "お気に入り$n"
          "zh" -> "收藏$n"
          "ar" -> "مفضل$n"
          else -> "즐겨$n"
        }
      }
      fun empty(): String {
        return when (lang.lowercase()) {
          "en" -> "(empty)"
          "ja" -> "(空)"
          "zh" -> "(空)"
          "ar" -> "(فارغ)"
          else -> "(비어있음)"
        }
      }
      try { btnFav1.text = "${favLabel(1)}\n${if (f1.isBlank()) empty() else f1}" } catch (t: Throwable) {
        try { Log.i(TAG, "[MacroFav] setText fav1 failed ${t.javaClass.simpleName}") } catch (_: Throwable) {}
      }
      try { btnFav2.text = "${favLabel(2)}\n${if (f2.isBlank()) empty() else f2}" } catch (t: Throwable) {
        try { Log.i(TAG, "[MacroFav] setText fav2 failed ${t.javaClass.simpleName}") } catch (_: Throwable) {}
      }
      try { btnFav3.text = "${favLabel(3)}\n${if (f3.isBlank()) empty() else f3}" } catch (t: Throwable) {
        try { Log.i(TAG, "[MacroFav] setText fav3 failed ${t.javaClass.simpleName}") } catch (_: Throwable) {}
      }
    }

    fun openFav(which: Int) {
      val key = "flutter.fav$which"
      val name = prefs().getString(key, "") ?: ""
      if (name.isBlank()) {
        updateNotification("즐겨$which 비어있음")
        return
      }
      val ok = loadMacroFromFile(name)
      if (ok) {
        updateNotification("메크로 열기: $name")
        removeMacroOpenOverlay()
      } else {
        updateNotification("열기 실패: $name")
      }
    }

    btnFav1.setOnClickListener { openFav(1) }
    btnFav2.setOnClickListener { openFav(2) }
    btnFav3.setOnClickListener { openFav(3) }
    refreshFavButtons()

    // (요청) 즐겨찾기 아래 "메뉴에추가": 체크 시 메뉴툴바(접근성 툴바) 축소 상태에서도 즐겨 1~3 버튼 표시
    try {
      val p = prefs()
      val key = "flutter.fav_add_to_collapsed_toolbar"
      cbFavAddToToolbar.isChecked = p.getBoolean(key, false)
      cbFavAddToToolbar.setOnCheckedChangeListener { _, isChecked ->
        try { p.edit().putBoolean(key, isChecked).apply() } catch (_: Throwable) {}
        try { AutoClickAccessibilityService.requestRefreshToolbarLayout() } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    val listContainer = root.findViewById<ViewGroup>(R.id.listContainer)
    fun refreshList() {
      listContainer.removeAllViews()
      val files = listMacroFiles()
      val uiH = Handler(Looper.getMainLooper())
      val longTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(200L)
      // (개선) 롱프레스 취소가 너무 민감하면(손가락 미세 이동) 롱프레스가 잘 안 됨 → 여유를 둔다.
      val touchSlop = (ViewConfiguration.get(this).scaledTouchSlop.coerceAtLeast(4) * 3)
      for (f in files) {
        val fileName = f.name
        fun registerFavAuto() {
          val p = prefs()
          val slot = (p.getInt("flutter.fav_next_slot", 1).coerceIn(1, 3))
          try { Log.i(TAG, "[MacroFav] registerFavAuto file='$fileName' slot=$slot") } catch (_: Throwable) {}
          val e =
            p.edit()
              .putString("flutter.fav$slot", fileName)
              .putInt("flutter.fav_next_slot", if (slot == 3) 1 else slot + 1)
          val okCommit = try { e.commit() } catch (_: Throwable) { false }
          try { Log.i(TAG, "[MacroFav] registerFavAuto commit=$okCommit") } catch (_: Throwable) {}
          if (!okCommit) {
            try { e.apply() } catch (_: Throwable) {}
          }
          try {
            val r1 = p.getString("flutter.fav1", "") ?: ""
            val r2 = p.getString("flutter.fav2", "") ?: ""
            val r3 = p.getString("flutter.fav3", "") ?: ""
            Log.i(TAG, "[MacroFav] afterWrite fav1='$r1' fav2='$r2' fav3='$r3'")
          } catch (_: Throwable) {}
          uiH.post { try { refreshFavButtons() } catch (_: Throwable) {} }
          try { AutoClickAccessibilityService.requestRefreshToolbarLayout() } catch (_: Throwable) {}
          updateNotification("즐겨$slot 등록: $fileName")
        }
        fun registerFavSlot(slot: Int) {
          val s = slot.coerceIn(1, 3)
          val p = prefs()
          try { Log.i(TAG, "[MacroFav] registerFavSlot file='$fileName' slot=$s") } catch (_: Throwable) {}
          val e = p.edit().putString("flutter.fav$s", fileName)
          val okCommit = try { e.commit() } catch (_: Throwable) { false }
          try { Log.i(TAG, "[MacroFav] registerFavSlot commit=$okCommit") } catch (_: Throwable) {}
          if (!okCommit) {
            try { e.apply() } catch (_: Throwable) {}
          }
          try {
            val r1 = p.getString("flutter.fav1", "") ?: ""
            val r2 = p.getString("flutter.fav2", "") ?: ""
            val r3 = p.getString("flutter.fav3", "") ?: ""
            Log.i(TAG, "[MacroFav] afterWrite fav1='$r1' fav2='$r2' fav3='$r3'")
          } catch (_: Throwable) {}
          uiH.post { try { refreshFavButtons() } catch (_: Throwable) {} }
          try { AutoClickAccessibilityService.requestRefreshToolbarLayout() } catch (_: Throwable) {}
          updateNotification("즐겨$s 등록: $fileName")
        }

        val row =
          LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // (개선) 사용자가 행(빈 공간)을 길게 눌러도 즐겨 등록이 되게 함
            isLongClickable = true
            setOnLongClickListener {
              try { registerFavAuto() } catch (_: Throwable) {}
              true
            }
          }
        val btn =
          android.widget.Button(this).apply {
            text = fileName
            isAllCaps = false
            try {
              setTextColor(Color.parseColor("#111827"))
            } catch (_: Throwable) {}
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
              val ok = loadMacroFromFile(fileName)
              if (ok) {
                updateNotification("메크로 열기: $fileName")
                removeMacroOpenOverlay()
              } else {
                updateNotification("열기 실패: $fileName")
              }
            }
            // (중요) 일부 기기/오버레이 조합에서 시스템 long-click이 아예 안 뜨는 문제가 있어,
            // DOWN부터 직접 타이머로 long-press를 감지해서 즐겨 등록한다.
            setOnTouchListener { v, ev ->
              try {
                when (ev.actionMasked) {
                  MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    // 상태 저장
                    v.setTag(R.id.btnFav1, floatArrayOf(ev.rawX, ev.rawY)) // reuse id as tag key
                    v.setTag(R.id.btnFav2, false) // longFired
                    // long press runnable
                    val r =
                      Runnable {
                        try {
                          v.setTag(R.id.btnFav2, true)
                          try { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
                          registerFavAuto()
                        } catch (_: Throwable) {}
                      }
                    v.setTag(R.id.btnFav3, r) // reuse id as tag key
                    uiH.postDelayed(r, longTimeoutMs)
                    return@setOnTouchListener true
                  }
                  MotionEvent.ACTION_MOVE -> {
                    val down = v.getTag(R.id.btnFav1) as? FloatArray
                    if (down != null) {
                      val dx = kotlin.math.abs(ev.rawX - down[0])
                      val dy = kotlin.math.abs(ev.rawY - down[1])
                      if (dx > touchSlop || dy > touchSlop) {
                        val r = v.getTag(R.id.btnFav3) as? Runnable
                        if (r != null) uiH.removeCallbacks(r)
                        v.setTag(R.id.btnFav3, null)
                      }
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    return@setOnTouchListener true
                  }
                  MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    val r = v.getTag(R.id.btnFav3) as? Runnable
                    if (r != null) uiH.removeCallbacks(r)
                    v.setTag(R.id.btnFav3, null)
                    val longFired = (v.getTag(R.id.btnFav2) as? Boolean) == true
                    if (longFired) {
                      return@setOnTouchListener true
                    }
                    // short tap -> normal click
                    try { v.performClick() } catch (_: Throwable) {}
                    return@setOnTouchListener true
                  }
                  MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    val r = v.getTag(R.id.btnFav3) as? Runnable
                    if (r != null) uiH.removeCallbacks(r)
                    v.setTag(R.id.btnFav3, null)
                    return@setOnTouchListener true
                  }
                  else -> return@setOnTouchListener false
                }
              } catch (_: Throwable) {
                return@setOnTouchListener false
              }
            }
          }

        // (추가) 롱프레스가 기기별로 불안정할 수 있어, 즐겨 등록 버튼도 함께 제공(원클릭)
        val favBtn =
          android.widget.Button(this).apply {
            text = "★"
            isAllCaps = false
            try { setTextColor(Color.parseColor("#111827")) } catch (_: Throwable) {}
            // 너무 커지지 않게
            layoutParams = LinearLayout.LayoutParams(dpToPx(44f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
              leftMargin = dpToPx(6f)
            }
            setOnClickListener {
              // (요청) 즐겨1/2/3 선택창(팝업) 표시
              try {
                val menu = android.widget.PopupMenu(this@ScreenCaptureService, this)
                val ID_FAV1 = 1
                val ID_FAV2 = 2
                val ID_FAV3 = 3
                val ID_AUTO = 9
                menu.menu.add(0, ID_FAV1, 0, "즐겨1 등록")
                menu.menu.add(0, ID_FAV2, 1, "즐겨2 등록")
                menu.menu.add(0, ID_FAV3, 2, "즐겨3 등록")
                menu.menu.add(0, ID_AUTO, 3, "자동(순환) 등록")
                menu.setOnMenuItemClickListener { item ->
                  when (item.itemId) {
                    ID_FAV1 -> { try { registerFavSlot(1) } catch (_: Throwable) {}; true }
                    ID_FAV2 -> { try { registerFavSlot(2) } catch (_: Throwable) {}; true }
                    ID_FAV3 -> { try { registerFavSlot(3) } catch (_: Throwable) {}; true }
                    ID_AUTO -> { try { registerFavAuto() } catch (_: Throwable) {}; true }
                    else -> false
                  }
                }
                menu.show()
              } catch (_: Throwable) {
                // 팝업 실패 시 최소한 자동 등록이라도 동작
                try { registerFavAuto() } catch (_: Throwable) {}
              }
            }
          }
        val del =
          android.widget.Button(this).apply {
            text = I18n.delete(lang)
            isAllCaps = false
            try {
              setTextColor(Color.parseColor("#111827"))
            } catch (_: Throwable) {}
            setOnClickListener {
              try {
                File(atxDir(), f.name).delete()
              } catch (_: Throwable) {}
              refreshList()
              refreshFavButtons()
            }
          }
        row.addView(btn)
        row.addView(favBtn)
        row.addView(del)
        listContainer.addView(row)
      }
    }
    refreshList()

    root.findViewById<View>(R.id.btnClose).setOnClickListener { removeMacroOpenOverlay() }

    val usable = getUsableRectPx()
    val maxH = (usable.height() * 0.92f).roundToInt().coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.height())
    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        // 화면에 다 안 들어오면 스크롤로 보기 위해 높이 제한
        maxH,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
      )
    lp.gravity = Gravity.TOP or Gravity.START
    macroOpenLp = lp
    updateMacroOverlayLayout(root, lp)
    try {
      wmLocal.addView(root, lp)
    } catch (_: Throwable) {
      macroOpenRoot = null
      macroOpenLp = null
      popToolbarHiddenByModalInternal()
    }
  }

  private fun removeMacroOpenOverlay() {
    val root = macroOpenRoot ?: return
    macroOpenRoot = null
    macroOpenLp = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
    // (요청) 선택 완료/닫기 시 메뉴툴바 복구
    try {
      AutoClickAccessibilityService.requestShowToolbar()
    } catch (_: Throwable) {}
  }

  private fun updatePanelLayout() {
    val root = panelRoot ?: return
    val lp = panelLp ?: return

    // 요구사항: 설정창은 화면 중앙 정렬
    val usable = getUsableRectPx()
    val panelW0 = if (root.width > 0) root.width else (root.measuredWidth.takeIf { it > 0 } ?: dpToPx(320f))
    val panelH0 = if (root.height > 0) root.height else (root.measuredHeight.takeIf { it > 0 } ?: dpToPx(420f))
    val panelW = (panelW0 * 0.95f).roundToInt().coerceAtMost(panelW0).coerceAtLeast(dpToPx(280f)).coerceAtMost(usable.width().coerceAtLeast(dpToPx(280f)))
    val panelH = (panelH0 * 0.95f).roundToInt().coerceAtMost(panelH0).coerceAtLeast(dpToPx(260f)).coerceAtMost(usable.height().coerceAtLeast(dpToPx(260f)))
    lp.width = panelW
    lp.height = panelH

    val desiredX = (usable.centerX() - panelW / 2).coerceIn(usable.left, (usable.right - panelW).coerceAtLeast(usable.left))
    val desiredY = (usable.centerY() - panelH / 2).coerceIn(usable.top, (usable.bottom - panelH).coerceAtLeast(usable.top))

    lp.x = desiredX
    lp.y = desiredY

    try {
      wm?.updateViewLayout(root, lp)
    } catch (_: Throwable) {}

    // 최초 표시 직후엔 width/height가 0일 수 있어, 레이아웃 후 다시 한 번 정확 클램프
    if (panelW == 0 || panelH == 0) {
      Handler(Looper.getMainLooper()).post {
        try {
          updatePanelLayout()
        } catch (_: Throwable) {}
      }
    }
  }

  private fun refreshPanelUi() {
    // (구)색상 패널 UI 제거됨: 유지 호환용 no-op
  }

  private fun removeColorPanel() {
    val root = panelRoot ?: return
    panelRoot = null
    panelLp = null
    tvHex = null
    tvCoord = null
    colorSwatch = null
    passThroughCb = null
    etX = null
    etY = null
    sbOpacity = null
    tvOpacity = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
    // (요청) 메뉴바설정창 닫기 시 접근성 메뉴툴바 복구
    try {
      AutoClickAccessibilityService.requestShowToolbar()
    } catch (_: Throwable) {}
  }

  private fun showCoordInputOverlay() {
    if (coordInputRoot != null) return

    pushToolbarHiddenByModalInternal()

    // 입력 중에는 터치 레이어가 가로채지 않게 잠시 제거
    coordInputWasTouchOverlayVisible = (touchTopRoot != null || touchBottomRoot != null)
    removeTouchOverlay()

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    val root = inflater.inflate(R.layout.overlay_coord_input, null)
    coordInputRoot = root

    val etX = root.findViewById<EditText>(R.id.etX)
    val etY = root.findViewById<EditText>(R.id.etY)

    // 마지막 좌표가 있으면 기본값으로
    if (lastSampleScreenX >= 0) etX.setText(lastSampleScreenX.toString())
    if (lastSampleScreenY >= 0) etY.setText(lastSampleScreenY.toString())

    root.findViewById<View>(R.id.btnCancel).setOnClickListener {
      removeCoordInputOverlay()
    }

    root.findViewById<View>(R.id.btnSearch).setOnClickListener {
      val x = etX.text?.toString()?.trim()?.toIntOrNull()
      val y = etY.text?.toString()?.trim()?.toIntOrNull()
      if (x == null || y == null) {
        updateNotification("좌표를 입력하세요")
        return@setOnClickListener
      }

      // 요구사항: "검색" 누르면 창을 닫고 해당 좌표 색상 읽기
      removeCoordInputOverlay()
      sampleAtScreen(x, y)
    }

    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.CENTER
    lp.softInputMode =
      WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE

    try {
      wm?.addView(root, lp)
    } catch (_: Throwable) {
      coordInputRoot = null
      popToolbarHiddenByModalInternal()
      // 실패 시 터치 레이어 복구
      if (!passThroughEnabled && coordInputWasTouchOverlayVisible) showTouchOverlay()
    }
  }

  private fun requestRestartFromOverlay() {
    try {
      // (버그픽스) Flutter 초기화면이 잠깐 보이는 문제를 피하기 위해,
      // 투명 Activity에서 MediaProjection 권한만 요청한다.
      val intent =
        Intent(this, ScreenSharePermissionActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
      startActivity(intent)
    } catch (_: Throwable) {}
  }

  private fun removeCoordInputOverlay() {
    val root = coordInputRoot ?: return
    coordInputRoot = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}

    popToolbarHiddenByModalInternal()

    // 닫힌 뒤 원래 상태 복구
    if (!passThroughEnabled && coordInputWasTouchOverlayVisible) {
      showTouchOverlay()
    }
  }

  private fun bringToolbarToFrontSafe() {
    val root = toolbarRoot ?: return
    val lp = toolbarLp ?: return
    val wmLocal = wm ?: return

    val run = Runnable {
      try {
        // touch overlay가 나중에 add 되면 툴바가 뒤로 가려질 수 있어 동기적으로 재-add 한다.
        // (updateViewLayout은 z-order를 바꾸지 못함)
        if (root.isAttachedToWindow) {
          try {
            wmLocal.removeViewImmediate(root)
          } catch (_: Throwable) {}
        }
        try {
          wmLocal.addView(root, lp)
        } catch (_: Throwable) {
          // 이미 추가된 상태면 레이아웃만 갱신
          try {
            wmLocal.updateViewLayout(root, lp)
          } catch (_: Throwable) {}
        }
      } catch (_: Throwable) {
        // ignore
      }
    }

    if (Looper.myLooper() == Looper.getMainLooper()) run.run() else Handler(Looper.getMainLooper()).post(run)
  }

  private fun bringOverlayViewToFrontSafe(v: View?, lp: WindowManager.LayoutParams?) {
    val view = v ?: return
    val params = lp ?: return
    val wmLocal = wm ?: return
    val run =
      Runnable {
        try {
          if (view.isAttachedToWindow) {
            try {
              wmLocal.removeViewImmediate(view)
            } catch (_: Throwable) {}
          }
          try {
            wmLocal.addView(view, params)
          } catch (_: Throwable) {
            // 이미 추가된 상태면 레이아웃만 갱신
            try {
              wmLocal.updateViewLayout(view, params)
            } catch (_: Throwable) {}
          }
        } catch (_: Throwable) {}
      }
    if (Looper.myLooper() == Looper.getMainLooper()) run.run() else Handler(Looper.getMainLooper()).post(run)
  }

  private fun bringPanelsToFrontSafe() {
    // 터치 오버레이가 add되면 최상단을 차지해 패널 터치가 막힐 수 있어,
    // 패널/다이얼로그류를 다시 최상단으로 올린다.
    bringOverlayViewToFrontSafe(colorPanelRoot, colorPanelLp)
    bringOverlayViewToFrontSafe(panelRoot, panelLp)
    bringOverlayViewToFrontSafe(screenSettingsRoot, screenSettingsLp)
    bringOverlayViewToFrontSafe(macroSaveRoot, macroSaveLp)
    bringOverlayViewToFrontSafe(macroOpenRoot, macroOpenLp)
  }

  private fun showTouchOverlay() {
    // (중요) 매크로 실행 중에는 터치 오버레이가 화면을 덮으면
    // 접근성 제스처가 "오버레이에 맞아" 밑에 앱이 실제로 눌리지 않는 문제가 생길 수 있다.
    // 실행 중엔 절대로 터치 오버레이를 띄우지 않는다.
    if (macroRunning) {
      try {
        removeTouchOverlay()
      } catch (_: Throwable) {}
      return
    }
    if (touchTopRoot != null || touchBottomRoot != null) {
      updateTouchOverlayLayout()
      return
    }

    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater

    fun attachTouch(root: View) {
      val touchLayer = root.findViewById<View>(R.id.touchLayer)
      touchLayer.setOnTouchListener { _, event ->
        // 터치 오버레이가 잘못 계산/겹침으로 툴바 영역을 덮을 수 있음.
        // 툴바 영역 터치는 소비하지 않고 아래(툴바)로 넘긴다.
        try {
          if (isPointInsideToolbar(event.rawX, event.rawY)) return@setOnTouchListener false
        } catch (_: Throwable) {}

        // 원샷 픽업일 땐 ACTION_DOWN에서만 1회 샘플링하고 즉시 통과 모드로 복귀
        val shouldSample =
          if (pickOnceArmed) event.action == MotionEvent.ACTION_DOWN
          else (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE)

        if (shouldSample) {
          val x = event.rawX.roundToInt()
          val y = event.rawY.roundToInt()
          sampleAtScreen(x, y)

          if (pickOnceArmed) {
            pickOnceArmed = false
            if (passThroughEnabled) {
              removeTouchOverlay()
              updateNotification("오버레이 통과: ON")
            }
          }
        }
        true
      }
    }

    val top = inflater.inflate(R.layout.overlay_touch_layer, null)
    touchTopRoot = top
    attachTouch(top)

    val bottom = inflater.inflate(R.layout.overlay_touch_layer, null)
    touchBottomRoot = bottom
    attachTouch(bottom)

    try {
      wm?.addView(top, makeTouchLp())
    } catch (_: Throwable) {
      touchTopRoot = null
    }
    try {
      wm?.addView(bottom, makeTouchLp())
    } catch (_: Throwable) {
      touchBottomRoot = null
    }

    updateTouchOverlayLayout()
    bringPanelsToFrontSafe()
    bringToolbarToFrontSafe()
  }

  private fun makeTouchLp(): WindowManager.LayoutParams {
    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    return lp
  }

  private fun updateTouchOverlayLayout() {
    val top = touchTopRoot
    val bottom = touchBottomRoot
    if (top == null && bottom == null) return

    val screen = getScreenSize()
    val insets = getSystemBarsInsetsPx()

    val tlp = toolbarLp
    val toolbarY = tlp?.y ?: insets.top
    val toolbarH = (toolbarRoot?.height ?: toolbarHeightPx).coerceAtLeast(toolbarHeightPx)
    val toolbarTop = toolbarY.coerceAtLeast(insets.top)
    val toolbarBottom = (toolbarY + toolbarH).coerceAtLeast(toolbarTop)

    fun updateOne(root: View?, y: Int, h: Int) {
      if (root == null) return
      val params = (root.layoutParams as? WindowManager.LayoutParams) ?: makeTouchLp()
      if (h <= 0) {
        try {
          wm?.removeView(root)
        } catch (_: Throwable) {}
        return
      }
      params.y = y
      params.height = h
      try {
        wm?.updateViewLayout(root, params)
      } catch (_: Throwable) {}
    }

    val topY = insets.top
    val topH = (toolbarTop - insets.top).coerceAtLeast(0)
    val bottomY = toolbarBottom
    val bottomH = (screen.height - insets.bottom - toolbarBottom).coerceAtLeast(0)

    updateOne(top, topY, topH)
    updateOne(bottom, bottomY, bottomH)
  }

  private fun dpToPx(dp: Float): Int {
    return (dp * resources.displayMetrics.density).roundToInt()
  }

  private data class InsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

  private fun getSystemBarsInsetsPx(): InsetsPx {
    val wmLocal = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
    return if (Build.VERSION.SDK_INT >= 30) {
      // systemBars + displayCutout 을 usable 계산에 함께 반영
      val insets = wmLocal.currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
      // 일부 환경에서 currentWindowMetrics가 비정상적으로 작게 나올 수 있어 max 기준도 같이 고려
      val maxInsets = wmLocal.maximumWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
      // 더 큰 값을 사용(네비게이션바 길이를 "정확히 제외"하기 위함)
      InsetsPx(
        left = maxOf(insets.left, maxInsets.left),
        top = maxOf(insets.top, maxInsets.top),
        right = maxOf(insets.right, maxInsets.right),
        bottom = maxOf(insets.bottom, maxInsets.bottom),
      )
    } else {
      val res = resources
      val topId = res.getIdentifier("status_bar_height", "dimen", "android")
      val bottomId = res.getIdentifier("navigation_bar_height", "dimen", "android")
      val rightId = res.getIdentifier("navigation_bar_width", "dimen", "android")
      val top = if (topId > 0) res.getDimensionPixelSize(topId) else 0
      val bottom = if (bottomId > 0) res.getDimensionPixelSize(bottomId) else 0
      val right = if (rightId > 0) res.getDimensionPixelSize(rightId) else 0
      val landscape = res.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
      InsetsPx(
        left = 0,
        top = top,
        right = if (landscape) right else 0,
        bottom = if (landscape) 0 else bottom,
      )
    }
  }

  private fun removeTouchOverlay() {
    val top = touchTopRoot
    val bottom = touchBottomRoot
    touchTopRoot = null
    touchBottomRoot = null
    try {
      if (top != null) wm?.removeView(top)
    } catch (_: Throwable) {}
    try {
      if (bottom != null) wm?.removeView(bottom)
    } catch (_: Throwable) {}
  }

  private fun removeColorPickerOverlay() {
    val root = colorPanelRoot ?: return
    colorPanelRoot = null
    colorPanelLp = null
    colorPanelDraggedByUser = false
    colorPickerSwatch = null
    colorPickerTvHex = null
    colorPickerTvCoord = null
    colorPickerPassThroughCb = null
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
    popToolbarHiddenByModalInternal()
  }

  private fun removeOverlay() {
    removeTouchOverlay()
    try { removeTouchVizOverlay() } catch (_: Throwable) {}
    // stop/exit 시에는 "닫힌 뒤 원래 상태 복구" 같은 부가 동작이 일어나면 안 된다.
    coordInputWasTouchOverlayVisible = false
    removeColorPanel()
    removeColorPickerOverlay()
    removeScreenSettingsOverlay()
    removeMacroSaveOverlay()
    removeMacroOpenOverlay()
    removeCoordInputOverlay()
    removeAllMarkerViews()

    val root = toolbarRoot ?: return
    toolbarRoot = null
    toolbarLp = null
    passThroughEnabled = false
    try {
      wm?.removeView(root)
    } catch (_: Throwable) {}
  }

  // ---------------- 마커 구현 ----------------
  private fun prefs(): SharedPreferences {
    return getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
  }

  private val MARKER_COORD_SPACE_KEY = "flutter.marker_coord_space"
  private val MARKER_COORD_SPACE_SCREEN = "screen"
  private val MARKER_COORD_SPACE_USABLE_LEGACY = "usable"
  // (요청) 화면모드(가로/세로 + 위/아래 = ROTATION_0/90/180/270) 왕복 시 마커 드리프트 방지용
  // "기준(base) 화면모드" 및 기준 마커 좌표 스냅샷
  private val MARKERS_BASE_KEY = "flutter.markers_base"
  private val MARKER_BASE_ROT_KEY = "flutter.marker_base_rot"
  private val MARKER_BASE_W_KEY = "flutter.marker_base_w"
  private val MARKER_BASE_H_KEY = "flutter.marker_base_h"
  // MarkerSettingsActivity 등 "외부"에서 markers JSON을 직접 수정했을 때,
  // base(회전 기준좌표)도 함께 갱신하도록 요청하는 플래그
  private val MARKERS_UPDATE_BASE_FLAG_KEY = "flutter.markers_update_base"

  private fun loadMarkersFromPrefs(): MutableList<Marker> {
    val raw = prefs().getString("flutter.markers", null) ?: "[]"
    val out = mutableListOf<Marker>()
    val p = prefs()
    val coordSpace = p.getString(MARKER_COORD_SPACE_KEY, MARKER_COORD_SPACE_USABLE_LEGACY) ?: MARKER_COORD_SPACE_USABLE_LEGACY

    val usable = getUsableRectPx()
    val wU = usable.width().coerceAtLeast(1)
    val hU = usable.height().coerceAtLeast(1)
    val maxX = (wU - 1).coerceAtLeast(0)
    val maxY = (hU - 1).coerceAtLeast(0)
    val screen = getScreenSize()
    val wS = screen.width.coerceAtLeast(1)
    val hS = screen.height.coerceAtLeast(1)
    val maxSX = (wS - 1).coerceAtLeast(0)
    val maxSY = (hS - 1).coerceAtLeast(0)

    var migratedToScreen = false
    try {
      val arr = JSONArray(raw)
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val index = obj.optInt("index", 0)
        val kindRaw = obj.optString("kind", "click")
        // 과거 데이터 호환: solo -> solo_main
        val kind = if (kindRaw == "solo") "solo_main" else kindRaw
        val xPxRaw = obj.optInt("xPx", -1)
        val yPxRaw = obj.optInt("yPx", -1)
        val xPctRaw = obj.optDouble("xPct", Double.NaN)
        val yPctRaw = obj.optDouble("yPct", Double.NaN)

        // 좌표계:
        // - legacy(usable): xPx/yPx는 usable 기준 중심 좌표
        // - screen: xPx/yPx는 screen 기준 중심 좌표(전체 영역)
        val xScreen: Int
        val yScreen: Int
        if (coordSpace == MARKER_COORD_SPACE_SCREEN) {
          // px가 없으면 pct(screen)에서 복원
          val x =
            if (xPxRaw >= 0) xPxRaw.coerceIn(0, maxSX)
            else if (!xPctRaw.isNaN()) (xPctRaw.coerceIn(0.0, 1.0) * wS.toDouble()).roundToInt().coerceIn(0, maxSX)
            else 0
          val y =
            if (yPxRaw >= 0) yPxRaw.coerceIn(0, maxSY)
            else if (!yPctRaw.isNaN()) (yPctRaw.coerceIn(0.0, 1.0) * hS.toDouble()).roundToInt().coerceIn(0, maxSY)
            else 0
          xScreen = x
          yScreen = y
        } else {
          // legacy: usable 기준
          val xU =
            if (xPxRaw >= 0) xPxRaw.coerceIn(0, maxX)
            else if (!xPctRaw.isNaN()) (xPctRaw.coerceIn(0.0, 1.0) * wU.toDouble()).roundToInt().coerceIn(0, maxX)
            else 0
          val yU =
            if (yPxRaw >= 0) yPxRaw.coerceIn(0, maxY)
            else if (!yPctRaw.isNaN()) (yPctRaw.coerceIn(0.0, 1.0) * hU.toDouble()).roundToInt().coerceIn(0, maxY)
            else 0
          // screen 좌표로 변환(1회 마이그레이션)
          xScreen = (usable.left + xU).coerceIn(0, maxSX)
          yScreen = (usable.top + yU).coerceIn(0, maxSY)
          migratedToScreen = true
        }

        val xPct = (xScreen.toDouble() / wS.toDouble()).coerceIn(0.0, 1.0)
        val yPct = (yScreen.toDouble() / hS.toDouble()).coerceIn(0.0, 1.0)

        // modulePattern 마이그레이션(ATX2):
        // - v1: modulePattern==6 이 "랜덤"
        // - v2: modulePattern==10 이 "랜덤"
        // - modulePatternV2가 0이면 구버전으로 보고 변환한다.
        val modulePatternV2Raw = obj.optInt("modulePatternV2", 0)
        val modulePatternRawPresent = try { obj.has("modulePattern") } catch (_: Throwable) { false }
        val moduleDirLegacy = obj.optString("moduleDir", "R").trim().uppercase()
        var modulePattern = obj.optInt("modulePattern", 0)
        var modulePatternV2 = modulePatternV2Raw
        if (kind == "module") {
          if (modulePatternV2Raw == 0) {
            if (!modulePatternRawPresent) {
              // (호환) 과거 앱 데이터는 moduleDir만 저장했던 경우가 있어,
              // 해당 방향을 v2 단일 패턴(6~9)으로 복원한다.
              modulePatternV2 = 2
              modulePattern =
                when (moduleDirLegacy) {
                  "U" -> 6
                  "D" -> 7
                  "L" -> 8
                  else -> 9 // R default
                }
            } else {
              // ATX2 v1 -> v2 랜덤 패턴 변환
              if (modulePattern == 6) modulePattern = 10
            }
          }
          modulePattern = modulePattern.coerceIn(0, 10)
        }

        // (보강) goto가 0이면 preClick은 강제로 비활성화(기존 데이터가 남아도 오동작 방지)
        // (중요) solo_item index는 음수일 수 있으므로, 음수를 0으로 바꾸면 안 된다.
        val g1 = obj.optInt("soloVerifyGotoOnStopMissing", 0)
        // (변경) 실행확인 방식 1가지로 통일: found용 goto는 더 이상 사용/저장하지 않는다.
        val g2 = 0
        // (변경) 실행확인 방식은 1가지로 통일되어 goto도 1개만 사용한다.
        // solo_item index는 음수일 수 있으므로, 0만 "단독 종료"로 취급한다.
        val gotoEnabled = (g1 != 0)
        val preUse = if (gotoEnabled) obj.optBoolean("soloPreClickUse", false) else false
        val preX = if (preUse) obj.optInt("soloPreClickXPx", -1) else -1
        val preY = if (preUse) obj.optInt("soloPreClickYPx", -1) else -1

        val m =
          Marker(
            index = index,
            kind = kind,
            xPx = xScreen,
            yPx = yScreen,
            xPct = xPct,
            yPct = yPct,
            delayMs = obj.optInt("delayMs", 300),
            jitterPct = obj.optInt("jitterPct", 50),
            pressMs = obj.optInt("pressMs", 90),
            doubleClick = obj.optBoolean("doubleClick", false),
            parentIndex = obj.optInt("parentIndex", 0),
            toIndex = obj.optInt("toIndex", 0),
            moveUpMs = obj.optInt("moveUpMs", 700),
            swipeMode = obj.optInt("swipeMode", 0),
            // solo_main 라벨이 비어있으면(구버전/변환중) index로부터 복원(A=20001..Z=20026)
            soloLabel =
              run {
                val rawLab = obj.optString("soloLabel", "").trim().uppercase()
                if (rawLab.isNotBlank()) rawLab
                else if (kind == "solo_main" && index in 20001..20026) {
                  val c = ('A'.code + (index - 20001)).toChar()
                  c.toString()
                } else ""
              },
            soloStartDelayMs = obj.optInt("soloStartDelayMs", 720000),
            soloComboCount = obj.optInt("soloComboCount", 1),
            soloExec = obj.optBoolean("soloExec", false),
            moduleDir = moduleDirLegacy.ifBlank { "R" },
            moduleSoloExec = obj.optBoolean("moduleSoloExec", false),
            moduleLenPx = obj.optInt("moduleLenPx", 0),
            moduleMoveUpMs = obj.optInt("moduleMoveUpMs", 0),
            moduleDirMode = obj.optInt("moduleDirMode", 0),
            modulePattern = modulePattern,
            modulePatternV2 = modulePatternV2,
            useColor = obj.optBoolean("useColor", false),
            colorIndex = obj.optInt("colorIndex", 0),
            colorR = obj.optInt("colorR", -1),
            colorG = obj.optInt("colorG", -1),
            colorB = obj.optInt("colorB", -1),
            colorCheckXPx = obj.optInt("colorCheckXPx", -1),
            colorCheckYPx = obj.optInt("colorCheckYPx", -1),
            colorAccuracyPct = obj.optInt("colorAccuracyPct", 100).coerceIn(50, 100),
            imageTemplateFile = obj.optString("imageTemplateFile", "").trim(),
            // (호환) 예전 키(imageCheckXPx/Y)도 읽는다.
            imageStartXPx = obj.optInt("imageStartXPx", obj.optInt("imageCheckXPx", -1)),
            imageStartYPx = obj.optInt("imageStartYPx", obj.optInt("imageCheckYPx", -1)),
            imageEndXPx = obj.optInt("imageEndXPx", -1),
            imageEndYPx = obj.optInt("imageEndYPx", -1),
            imageAccuracyPct = obj.optInt("imageAccuracyPct", 90).coerceIn(50, 100),
            imageW = obj.optInt("imageW", 128).coerceIn(8, 1024),
            imageH = obj.optInt("imageH", 128).coerceIn(8, 1024),
            imageClickMode = obj.optInt("imageClickMode", 0).coerceIn(0, 3),
            imageCropLeftXPx = obj.optInt("imageCropLeftXPx", -1),
            imageCropTopYPx = obj.optInt("imageCropTopYPx", -1),
            soloVerifyUse = obj.optBoolean("soloVerifyUse", false),
            // (변경) 실행확인 방식은 1가지로 고정
            soloVerifyOnFoundMode = 0,
            soloVerifyTemplateFile = obj.optString("soloVerifyTemplateFile", "").trim(),
            soloVerifyStartXPx = obj.optInt("soloVerifyStartXPx", -1),
            soloVerifyStartYPx = obj.optInt("soloVerifyStartYPx", -1),
            soloVerifyEndXPx = obj.optInt("soloVerifyEndXPx", -1),
            soloVerifyEndYPx = obj.optInt("soloVerifyEndYPx", -1),
            soloVerifyAccuracyPct = obj.optInt("soloVerifyAccuracyPct", 80).coerceIn(50, 100),
            soloVerifyW = obj.optInt("soloVerifyW", 128).coerceIn(8, 1024),
            soloVerifyH = obj.optInt("soloVerifyH", 128).coerceIn(8, 1024),
            soloVerifyCropLeftXPx = obj.optInt("soloVerifyCropLeftXPx", -1),
            soloVerifyCropTopYPx = obj.optInt("soloVerifyCropTopYPx", -1),
            soloVerifyGotoOnStopMissing = g1,
            soloVerifyGotoOnStopFound = 0,
            soloPreClickUse = preUse,
            soloPreClickXPx = preX,
            soloPreClickYPx = preY,
            // (요청) AI탐지방어 체크박스 기본값은 ON.
            // 단독/서브/색상모듈/링은 제외 대상이므로, 해당 타입은 확률 적용을 하지 않는다(실행부에서 제외).
            randomClickUse =
              run {
                val excluded =
                  kind == "solo_main" || kind == "solo_item" || kind == "color_module" || kind == "image_module" || kind == "swipe_to" || kind == "color"
                // 키가 없으면 기본 ON(체크)로 간주하되, 제외 타입은 OFF로 둔다(데이터 정리).
                if (excluded) {
                  obj.optBoolean("randomClickUse", false)
                } else {
                  obj.optBoolean("randomClickUse", true)
                }
              },
          )

        out.add(m)
      }
    } catch (_: Throwable) {
      // 파싱 실패 -> 빈 배열
    }

    // 1회 마이그레이션(legacy usable -> screen)
    if (migratedToScreen) {
      try {
        saveMarkersToPrefs(out)
        p.edit().putString(MARKER_COORD_SPACE_KEY, MARKER_COORD_SPACE_SCREEN).apply()
      } catch (_: Throwable) {}
    }
    return out
  }

  private fun saveMarkersToPrefs(markers: List<Marker>, updateBase: Boolean = false) {
    val arr = JSONArray()
    // (버그픽스) 이미지모듈 클릭방식(mode=2: 소리내기)이 실행 중/좌표저장 과정에서
    // 의도치 않게 0/1로 덮어써지는 사례 방지:
    // - 현재 prefs에 저장된 imageClickMode가 2인데, 캐시(markers)에 0/1이 들어있으면
    //   2를 우선 유지한다(사용자가 설정한 "소리내기"가 튀지 않게).
    val preferImageClickMode23ByIndex: HashMap<Int, Int> =
      try {
        val raw = prefs().getString("flutter.markers", null)
        if (raw.isNullOrBlank()) HashMap()
        else {
          val tmp = HashMap<Int, Int>()
          val a = JSONArray(raw)
          for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("kind", "") != "image_module") continue
            val idx = o.optInt("index", Int.MIN_VALUE)
            if (idx == Int.MIN_VALUE) continue
            val mode = o.optInt("imageClickMode", 0).coerceIn(0, 3)
            if (mode == 2 || mode == 3) tmp[idx] = mode
          }
          tmp
        }
      } catch (_: Throwable) {
        HashMap()
      }

    for (m in markers) {
      val o = JSONObject()
      o.put("index", m.index)
      o.put("kind", m.kind)
      o.put("xPx", m.xPx)
      o.put("yPx", m.yPx)
      o.put("xPct", m.xPct)
      o.put("yPct", m.yPct)
      o.put("delayMs", m.delayMs)
      o.put("jitterPct", m.jitterPct)
      o.put("pressMs", m.pressMs)
      o.put("doubleClick", m.doubleClick)
      o.put("parentIndex", m.parentIndex)
      o.put("toIndex", m.toIndex)
      o.put("moveUpMs", m.moveUpMs)
      o.put("swipeMode", m.swipeMode.coerceIn(0, 1))
      o.put("soloLabel", m.soloLabel)
      o.put("soloStartDelayMs", m.soloStartDelayMs)
      o.put("soloComboCount", m.soloComboCount)
      o.put("soloExec", m.soloExec)
      o.put("moduleDir", m.moduleDir)
      o.put("moduleSoloExec", m.moduleSoloExec)
      o.put("moduleLenPx", m.moduleLenPx)
      o.put("moduleMoveUpMs", m.moduleMoveUpMs)
      o.put("moduleDirMode", m.moduleDirMode)
      o.put("modulePattern", m.modulePattern)
      o.put("modulePatternV2", m.modulePatternV2)
      o.put("useColor", m.useColor)
      o.put("colorIndex", m.colorIndex)
      o.put("colorR", m.colorR)
      o.put("colorG", m.colorG)
      o.put("colorB", m.colorB)
      o.put("colorCheckXPx", m.colorCheckXPx)
      o.put("colorCheckYPx", m.colorCheckYPx)
      o.put("colorAccuracyPct", m.colorAccuracyPct.coerceIn(50, 100))
      if (m.kind == "image_module") {
        o.put("imageTemplateFile", m.imageTemplateFile)
        o.put("imageStartXPx", m.imageStartXPx)
        o.put("imageStartYPx", m.imageStartYPx)
        o.put("imageEndXPx", m.imageEndXPx)
        o.put("imageEndYPx", m.imageEndYPx)
        o.put("imageAccuracyPct", m.imageAccuracyPct.coerceIn(50, 100))
        o.put("imageW", m.imageW.coerceIn(8, 1024))
        o.put("imageH", m.imageH.coerceIn(8, 1024))
        val prefMode = preferImageClickMode23ByIndex[m.index]
        val safeMode =
          if (prefMode != null && prefMode in 2..3 && m.imageClickMode != prefMode) {
            try { dbg("image_module preserve clickMode=$prefMode idx=${m.index} cacheMode=${m.imageClickMode}") } catch (_: Throwable) {}
            prefMode
          } else {
            m.imageClickMode.coerceIn(0, 3)
          }
        o.put("imageClickMode", safeMode)
        if (m.imageCropLeftXPx >= 0) o.put("imageCropLeftXPx", m.imageCropLeftXPx)
        if (m.imageCropTopYPx >= 0) o.put("imageCropTopYPx", m.imageCropTopYPx)
      }
      if ((m.kind == "solo_main" || m.kind == "solo_item") && m.soloVerifyUse) {
        o.put("soloVerifyUse", true)
        // (변경) 실행확인 방식은 1가지로 통일: mode는 더 이상 저장하지 않는다(정리).
        o.remove("soloVerifyOnFoundMode")
        if (m.soloVerifyTemplateFile.isNotBlank()) o.put("soloVerifyTemplateFile", m.soloVerifyTemplateFile.trim())
        if (m.soloVerifyStartXPx >= 0) o.put("soloVerifyStartXPx", m.soloVerifyStartXPx)
        if (m.soloVerifyStartYPx >= 0) o.put("soloVerifyStartYPx", m.soloVerifyStartYPx)
        if (m.soloVerifyEndXPx >= 0) o.put("soloVerifyEndXPx", m.soloVerifyEndXPx)
        if (m.soloVerifyEndYPx >= 0) o.put("soloVerifyEndYPx", m.soloVerifyEndYPx)
        o.put("soloVerifyAccuracyPct", m.soloVerifyAccuracyPct.coerceIn(50, 100))
        o.put("soloVerifyW", m.soloVerifyW.coerceIn(8, 1024))
        o.put("soloVerifyH", m.soloVerifyH.coerceIn(8, 1024))
        if (m.soloVerifyCropLeftXPx >= 0) o.put("soloVerifyCropLeftXPx", m.soloVerifyCropLeftXPx)
        if (m.soloVerifyCropTopYPx >= 0) o.put("soloVerifyCropTopYPx", m.soloVerifyCropTopYPx)
        // solo_item index는 음수일 수 있으므로, 0만 "단독 종료"로 취급한다.
        if (m.soloVerifyGotoOnStopMissing != 0) o.put("soloVerifyGotoOnStopMissing", m.soloVerifyGotoOnStopMissing)
        // (변경) 실행확인 방식 1가지로 통일: found용 goto는 더 이상 저장하지 않는다(정리).
        o.remove("soloVerifyGotoOnStopFound")
        val hasPre = !(m.soloPreClickXPx == -1 && m.soloPreClickYPx == -1)
        if (m.soloPreClickUse && hasPre) {
          o.put("soloPreClickUse", true)
          o.put("soloPreClickXPx", m.soloPreClickXPx)
          o.put("soloPreClickYPx", m.soloPreClickYPx)
        }
      }
      // (요청) 단독/서브/색상모듈/이미지모듈/링은 제외 => 저장 키 제거(깔끔 유지)
      if (m.kind == "solo_main" || m.kind == "solo_item" || m.kind == "color_module" || m.kind == "image_module" || m.kind == "swipe_to" || m.kind == "color") {
        // no-op
      } else {
        o.put("randomClickUse", m.randomClickUse)
      }
      arr.put(o)
    }
    val json = arr.toString()
    val e = prefs().edit().putString("flutter.markers", json)
    if (updateBase) {
      try {
        val s = getScreenSize()
        e.putString(MARKERS_BASE_KEY, json)
          .putInt(MARKER_BASE_ROT_KEY, currentRotation())
          .putInt(MARKER_BASE_W_KEY, s.width.coerceAtLeast(1))
          .putInt(MARKER_BASE_H_KEY, s.height.coerceAtLeast(1))
      } catch (_: Throwable) {}
    }
    e.apply()
    // 좌표계가 screen 기준임을 기록(legacy 복원 방지)
    try {
      prefs().edit().putString(MARKER_COORD_SPACE_KEY, MARKER_COORD_SPACE_SCREEN).apply()
    } catch (_: Throwable) {}
  }

  private fun ensureUniqueMarkerIndexes(markers: MutableList<Marker>) {
    // index는 마커의 "고유 ID" 역할도 하므로 임의 재정렬하면
    // 종류 변경 시 위치가 튀거나(뷰 재생성), swipe/toIndex/parentIndex 연결이 깨질 수 있다.
    // 따라서 index는 유지하되, 중복/0만 안전하게 정리한다.
    val used = HashSet<Int>()
    var nextNeg = -1
    fun takeNeg(): Int {
      while (nextNeg == 0 || used.contains(nextNeg)) nextNeg--
      val v = nextNeg
      used.add(v)
      nextNeg--
      return v
    }

    for (m in markers) {
      val idx = m.index
      if (idx == 0) {
        m.index = takeNeg()
        continue
      }
      if (used.contains(idx)) {
        m.index = takeNeg()
        continue
      }
      used.add(idx)
    }
  }

  private fun getUsableRectPx(): Rect {
    val screen = getScreenSize()
    val insets = getSystemBarsInsetsPx()
    // "네비게이션바/컷아웃을 제외한 usable 영역" 기준
    return Rect(
      insets.left,
      insets.top,
      (screen.width - insets.right).coerceAtLeast(insets.left + 1),
      (screen.height - insets.bottom).coerceAtLeast(insets.top + 1),
    )
  }

  private fun ensureMarkersLoadedAndShown() {
    if (markersCache.isEmpty()) {
      synchronized(markersLock) {
        if (markersCache.isEmpty()) {
          markersCache = loadMarkersFromPrefs()
          ensureUniqueMarkerIndexes(markersCache)
          val p0 = prefs()
          val baseMissing = try { p0.getString(MARKERS_BASE_KEY, null).isNullOrBlank() } catch (_: Throwable) { true }
          val baseUpdateRequested = try { p0.getBoolean(MARKERS_UPDATE_BASE_FLAG_KEY, false) } catch (_: Throwable) { false }
          val updateBaseNow = baseMissing || baseUpdateRequested
          if (baseUpdateRequested) {
            try { p0.edit().putBoolean(MARKERS_UPDATE_BASE_FLAG_KEY, false).apply() } catch (_: Throwable) {}
          }
          saveMarkersToPrefs(markersCache, updateBase = updateBaseNow)
        }
      }
    }
    refreshMarkerViews()
  }

  private fun reloadMarkersFromPrefsAndRefresh() {
    synchronized(markersLock) {
      markersCache = loadMarkersFromPrefs()
      ensureUniqueMarkerIndexes(markersCache)
      val p0 = prefs()
      val baseMissing = try { p0.getString(MARKERS_BASE_KEY, null).isNullOrBlank() } catch (_: Throwable) { true }
      val baseUpdateRequested = try { p0.getBoolean(MARKERS_UPDATE_BASE_FLAG_KEY, false) } catch (_: Throwable) { false }
      val updateBaseNow = baseMissing || baseUpdateRequested
      if (baseUpdateRequested) {
        try { p0.edit().putBoolean(MARKERS_UPDATE_BASE_FLAG_KEY, false).apply() } catch (_: Throwable) {}
      }
      saveMarkersToPrefs(markersCache, updateBase = updateBaseNow)
    }
    refreshMarkerViews()
  }

  private fun markersSnapshot(): List<Marker> {
    // 실행 스레드에서 markersCache가 변경되면 예외/중단이 발생할 수 있어 깊은 스냅샷 사용
    return synchronized(markersLock) { markersCache.map { it.copy() } }
  }

  private fun refreshMarkerViews() {
    val wmLocal = wm ?: return
    val usable = getUsableRectPx()
    val screen = getScreenSize()
    val wS = screen.width.coerceAtLeast(1)
    val hS = screen.height.coerceAtLeast(1)

    // (진단) 편집모드 토글 직후 refreshMarkerViews가 호출되며 좌표가 재배치되는지 추적
    val nowMs = android.os.SystemClock.uptimeMillis()
    val nearEditToggle = (nowMs - lastEditToggleAtMs) in 0L..1500L
    val dbgRepositionLimit = 10
    var dbgRepositionCount = 0
    if (nearEditToggle) {
      try {
        val st = Throwable().stackTrace
        val top =
          st
            .drop(1)
            .take(6)
            .joinToString(" <= ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
        dbg("refreshMarkerViews nearEditToggle seq=$editToggleSeq editMode=$markerEditMode stack=$top")
      } catch (_: Throwable) {}
    }

    // ATX2 호환 스케일 팩터(링/연결선 두께에 사용)
    try {
      OverlayUiScale.scaleFactor = (markerScalePercent.coerceIn(50, 200) / 100f)
    } catch (_: Throwable) {}

    fun isRing(m: Marker): Boolean = (m.kind == "color" || m.kind == "swipe_to")

    fun markerWindowSizePx(m: Marker): Int {
      val base = markerSizePx
      // 방향모듈은 원 밖 화살표가 있어 창 크기를 조금 더 크게
      return if (m.kind == "module") {
        val pad = (dpToPx(18f) * OverlayUiScale.scaleFactor.coerceAtLeast(0.5f)).roundToInt()
        (base + pad).coerceAtLeast(base)
      } else base
    }

    // 순번 라벨은 "순번실행(click/swipe)"만 1..N으로 표시(실제 index는 유지)
    val clickRankByIndex: Map<Int, Int> =
      markersCache
        .filter {
          (it.kind == "click" && it.index > 0) ||
            (it.kind == "swipe" && it.index > 0 && it.swipeMode == 0)
        }
        .sortedBy { it.index }
        .mapIndexed { i, m -> m.index to (i + 1) }
        .toMap()

    // (요청) 이미지모듈: 추가/삭제 시 표시 순번을 1..N으로 재부여(실제 index는 유지)
    val imageRankByIndex: Map<Int, Int> =
      markersCache
        .filter { it.kind == "image_module" && it.index > 0 }
        .sortedBy { it.index }
        .mapIndexed { i, m -> m.index to (i + 1) }
        .toMap()

    fun bubbleLabel(m: Marker): String {
      return if (m.kind.startsWith("solo")) {
        m.soloLabel.ifBlank { "0" }
      } else if (m.kind == "color_module" || m.kind == "image_module") {
        ""
      } else if (m.kind == "module") {
        if (m.index > 0) m.index.toString() else "0"
      } else if ((m.kind == "click" && m.index > 0) || (m.kind == "swipe" && m.index > 0 && m.swipeMode == 0)) {
        (clickRankByIndex[m.index] ?: m.index).toString()
      } else {
        "0"
      }
    }

    fun bubbleBaseColor(m: Marker): Int {
      // 명세 기반
      return when {
        m.kind == "solo_main" -> Color.parseColor("#22C55E") // green
        m.kind == "solo_item" -> Color.parseColor("#C4B5FD") // light purple
        m.kind == "color_module" -> Color.TRANSPARENT // 요청: 원은 투명(십자선만)
        m.kind == "image_module" -> Color.parseColor("#EF4444") // red (원은 빨강)
        // 방향모듈: 실행방식에 따라 색상 구분
        // - 모듈순번: 파란색
        // - 모듈단독(병렬): 빨간색
        m.kind == "module" && (m.moduleSoloExec || m.index < 0) -> Color.parseColor("#EF4444") // red
        (m.kind == "independent") -> Color.parseColor("#EF4444") // red
        (m.kind == "swipe" && m.swipeMode == 1) -> Color.parseColor("#EF4444") // red (독립 스와이프)
        (m.index < 0 && m.kind == "click") -> Color.parseColor("#EF4444") // red
        (m.index < 0) -> Color.parseColor("#A855F7") // purple
        else -> Color.parseColor("#3B82F6") // blue
      }
    }

    // 기존 뷰 중 삭제된 것 제거
    val alive = markersCache.map { it.index }.toSet()
    val removeKeys = markerViews.keys.filter { it !in alive }
    for (k in removeKeys) {
      val v = markerViews.remove(k)
      markerLps.remove(k)
      try {
        if (v != null) wmLocal.removeView(v)
      } catch (_: Throwable) {}
    }

    // 추가/갱신
    for (m in markersCache) {
      // (요청) 화면공유(MediaProjection) 권한/캡처가 없으면 색상/이미지 모듈은 화면에 보이지 않게 한다.
      // - 캡처가 없으면 비교가 불가능하므로, 표시/터치 모두 제거한다.
      if ((m.kind == "color_module" || m.kind == "image_module") && !captureReady) {
        val existed = markerViews.remove(m.index)
        markerLps.remove(m.index)
        try {
          if (existed != null) wmLocal.removeView(existed)
        } catch (_: Throwable) {}
        continue
      }

      var v = markerViews[m.index]

      // kind 변경 등으로 타입이 달라지면 재생성
      if (v != null) {
        val wantRing = isRing(m)
        val isRingNow = (v is ColorRingView)
        if (wantRing != isRingNow) {
          try {
            wmLocal.removeView(v)
          } catch (_: Throwable) {}
          markerViews.remove(m.index)
          markerLps.remove(m.index)
          v = null
        }
      }

      if (v == null) {
        val view: View = if (isRing(m)) ColorRingView(this) else MarkerBubbleView(this)
        val win = markerWindowSizePx(m)
        val lp =
          WindowManager.LayoutParams(
            win,
            win,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
          )
        lp.gravity = Gravity.TOP or Gravity.START
        markerViews[m.index] = view
        markerLps[m.index] = lp
        bindMarkerTouch(view, lp, m.index)
        try {
          wmLocal.addView(view, lp)
        } catch (_: Throwable) {
          markerViews.remove(m.index)
          markerLps.remove(m.index)
          continue
        }
        v = view
      }

      // 표시 규칙 적용
      if (v is MarkerBubbleView) {
        v.label =
          if (m.kind == "image_module") {
            (imageRankByIndex[m.index] ?: m.index).toString()
          } else {
            bubbleLabel(m)
          }
        v.baseColor = bubbleBaseColor(m)
        v.doubleRing = (m.kind == "module")
        // module은 "4방향 화살표(↑→↓←)"가 기본 표시라 arrowDir는 사용하지 않음(호환 필드 유지)
        v.arrowDir = if (m.kind == "module") null else null
        v.crosshair = (m.kind == "color_module" || m.kind == "image_module")
        v.crosshairOnly = (m.kind == "color_module") // 이미지모듈은 원+십자 모두 보이게
        v.crosshairColor = Color.parseColor("#A855F7") // purple (십자선은 보라)
        v.hollowCircle = (m.kind == "image_module")
      }

      // 크기 갱신(객체 크기 슬라이더 반영)
      val lp = markerLps[m.index] ?: continue
      val win = markerWindowSizePx(m)
      lp.width = win
      lp.height = win

      // 위치 갱신
      val cx = m.xPx.coerceIn(0, (wS - 1).coerceAtLeast(0))
      val cy = m.yPx.coerceIn(0, (hS - 1).coerceAtLeast(0))
      // (중요) "마커 원 중심 좌표 = 터치 좌표"를 보장하려면,
      // 윈도우를 화면 안쪽으로 강제 clamp하면 안 된다.
      // 가장자리(우측/상단 끝)에서 중심이 안쪽으로 밀려 좌표가 틀어져 보인다.
      // FLAG_LAYOUT_NO_LIMITS를 사용 중이므로 윈도우가 화면 밖으로 일부 나가도 OK.
      lp.x = (cx - win / 2)
      lp.y = (cy - win / 2)

      // (진단) 편집모드 토글 직후에 실제로 위치가 바뀌는지 로그로 남김(최대 N개)
      if (nearEditToggle && dbgRepositionCount < dbgRepositionLimit) {
        try {
          val old = markerLps[m.index]
          if (old != null) {
            val oldX = old.x
            val oldY = old.y
            val newX = lp.x
            val newY = lp.y
            if (oldX != newX || oldY != newY) {
              dbgRepositionCount++
              dbg(
                "refreshMarkerViews reposition idx=${m.index} kind=${m.kind} old=($oldX,$oldY) new=($newX,$newY) " +
                  "win=$win center=($cx,$cy) usable=[${usable.left},${usable.top},${usable.right},${usable.bottom}]"
              )
            }
          }
        } catch (_: Throwable) {}
      }
      applyMarkerEditFlags(lp)
      try {
        wmLocal.updateViewLayout(markerViews[m.index], lp)
      } catch (_: Throwable) {}
    }

    // 객체보기 토글/모달 상태 반영
    applyMarkersVisibility()
    updateSwipeLinkLines()
    // (중요) 마커 오버레이가 나중에 add/update 되면서 툴바를 덮는 경우가 있어
    // 마커 갱신 후에는 툴바를 항상 최상단으로 유지한다.
    try {
      bringToolbarToFrontSafe()
    } catch (_: Throwable) {}
  }

  private fun linkKey(a: Int, b: Int): Long {
    // (a,b) 쌍을 long 키로 인코딩 (음수 포함 안전)
    return (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
  }

  private fun hideAllLinkWindows() {
    val wmLocal = wm
    for ((_, lw) in linkWins) {
      try {
        wmLocal?.removeView(lw.view)
      } catch (_: Throwable) {}
    }
    linkWins.clear()
  }

  /**
   * ATX2 동등 구현:
   * - (변경) 편집모드 ON/OFF와 무관하게 항상 표시
   * - start(toIndex!=0, kind!=swipe_to,color) -> swipe_to 체인만 구간별로 연결
   * - 구간(from->to)마다 LinkLineView 1개 오버레이 윈도우 생성/업데이트
   */
  private fun updateSwipeLinkLines() {
    val wmLocal = wm ?: return

    // (요청) 네비게이션바/컷아웃 포함 전체 화면에서 선이 보이도록 screen 전체를 사용
    val screen = getScreenSize()
    val screenW = screen.width.coerceAtLeast(1)
    val screenH = screen.height.coerceAtLeast(1)

    // kind 조회 빠르게
    val kindByIndex = HashMap<Int, String>(markersCache.size)
    for (m in markersCache) kindByIndex[m.index] = m.kind

    fun centerFromLp(idx: Int): Pair<Float, Float>? {
      val lp = markerLps[idx] ?: return null
      val w = (lp.width.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
      val h = (lp.height.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
      val cx = (lp.x + w / 2f)
      val cy = (lp.y + h / 2f)
      // link view는 screen(0,0) 기준으로 깔기 때문에 그대로 사용
      return Pair(cx, cy)
    }

    // 1) keepKeys 계산
    val keepKeys = HashSet<Long>()
    val starts =
      markersCache.filter {
        it.toIndex != 0 &&
          it.kind != "swipe_to" &&
          it.kind != "color"
      }
    val maxSeg = 20
    for (s in starts) {
      var curFrom = s.index
      var curTo = s.toIndex
      var seg = 0
      while (curTo != 0 && seg++ < maxSeg) {
        keepKeys.add(linkKey(curFrom, curTo))
        val next =
          markersCache
            .firstOrNull { it.index == curTo && it.kind == "swipe_to" }
            ?.toIndex ?: 0
        curFrom = curTo
        curTo = next
      }
    }

    // 2) remove missing
    val remove = linkWins.keys.filter { it !in keepKeys }
    for (k in remove) {
      val lw = linkWins.remove(k) ?: continue
      try {
        wmLocal.removeView(lw.view)
      } catch (_: Throwable) {}
    }

    // 3) upsert
    val alphaNow = if (shouldShowMarkersNow()) 1f else 0f
    for (s in starts) {
      var curFrom = s.index
      var curTo = s.toIndex
      var seg = 0
      while (curTo != 0 && seg++ < maxSeg) {
        val toKind = kindByIndex[curTo]
        if (toKind != "swipe_to") break

        val fromCenter = centerFromLp(curFrom) ?: break
        val toCenter = centerFromLp(curTo) ?: break

        val key = linkKey(curFrom, curTo)
        val existing = linkWins[key]
        if (existing == null) {
          val v = LinkLineView(this).apply {
            isClickable = false
            isFocusable = false
            alpha = alphaNow
            setPoints(fromCenter.first, fromCenter.second, toCenter.first, toCenter.second)
          }
          val lp =
            WindowManager.LayoutParams(
              screenW,
              screenH,
              if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
              else WindowManager.LayoutParams.TYPE_PHONE,
              WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
              PixelFormat.TRANSLUCENT
            )
          lp.gravity = Gravity.TOP or Gravity.START
          lp.x = 0
          lp.y = 0
          try {
            wmLocal.addView(v, lp)
            linkWins[key] = LinkWin(v, lp)
          } catch (_: Throwable) {}
        } else {
          // size/pos 갱신(네비/회전 등)
          existing.lp.width = screenW
          existing.lp.height = screenH
          existing.lp.x = 0
          existing.lp.y = 0
          try {
            existing.view.alpha = alphaNow
            existing.view.setPoints(fromCenter.first, fromCenter.second, toCenter.first, toCenter.second)
            wmLocal.updateViewLayout(existing.view, existing.lp)
          } catch (_: Throwable) {}
        }

        val next =
          markersCache
            .firstOrNull { it.index == curTo && it.kind == "swipe_to" }
            ?.toIndex ?: 0
        curFrom = curTo
        curTo = next
      }
    }
  }

  private fun markerCenterInUsablePx(m: Marker, usable: Rect): Pair<Int, Int> {
    val w = usable.width().coerceAtLeast(1)
    val h = usable.height().coerceAtLeast(1)
    val maxX = (w - 1).coerceAtLeast(0)
    val maxY = (h - 1).coerceAtLeast(0)
    // screen(px) -> usable(px)로 변환(표시/설정 UI용)
    val cx = (m.xPx - usable.left).coerceIn(0, maxX)
    val cy = (m.yPx - usable.top).coerceIn(0, maxY)
    return Pair(cx, cy)
  }

  private fun applyMarkerEditFlags(lp: WindowManager.LayoutParams) {
    // 편집모드 OFF: 마커 탭 동작(자동클릭)을 위해 터치 가능.
    // 편집모드 ON: 드래그/탭 가능.
    //
    // (중요) 실행 중(macroRunning)에는 실제 터치/접근성 제스처가 밑에 앱에 전달되어야 하므로
    // 마커 오버레이가 터치를 가로채지 않도록 NOT_TOUCHABLE을 강제한다.
    lp.flags =
      if (macroRunning) {
        lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
      } else {
        lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
      }
    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
  }

  private fun scheduleMarkersSaveDebounced() {
    if (markerSaveScheduled) return
    markerSaveScheduled = true
    markerSaveHandler.postDelayed(
      {
        markerSaveScheduled = false
        try {
          // (중요) 드래그 중에는 markersCache(xPx/yPx)가 즉시 갱신되므로,
          // 여기서는 "재계산"하지 않고 cache 값을 그대로 저장한다.
          // 재계산(sync)은 창 크기/측정 타이밍에 따라 좌표가 미세하게 밀릴 수 있다.
          // (중요) 사용자 편집 저장이므로 base도 함께 갱신한다.
          saveMarkersToPrefs(markersCache, updateBase = true)
        } catch (_: Throwable) {}
      },
      120L,
    )
  }

  private fun flushMarkersSaveNow() {
    try {
      markerSaveHandler.removeCallbacksAndMessages(null)
    } catch (_: Throwable) {}
    markerSaveScheduled = false
    try {
      // (중요) 사용자 편집 저장이므로 base도 함께 갱신한다.
      saveMarkersToPrefs(markersCache, updateBase = true)
    } catch (_: Throwable) {}
  }

  private data class MarkerTouchState(
    val downRawX: Float,
    val downRawY: Float,
    val startLpX: Int,
    val startLpY: Int,
    var moved: Boolean,
  )

  private fun bindMarkerTouch(view: View, lp: WindowManager.LayoutParams, markerIndex: Int) {
    view.setOnTouchListener { _, event ->
      val usable = getUsableRectPx()
      val screen = getScreenSize()
      val wS = screen.width.coerceAtLeast(1)
      val hS = screen.height.coerceAtLeast(1)
      val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          // 마커가 툴바 위에 떠 있는 경우, 툴바 터치를 가로채서 "편집모드 버튼 누름"이
          // 마커 탭으로 처리되며 설정창이 뜨는 문제가 생길 수 있다.
          // 툴바 영역에서의 터치는 소비하지 않고 툴바로 넘긴다.
          if (isPointInsideToolbar(event.rawX, event.rawY)) {
            view.tag = null
            return@setOnTouchListener false
          }
          // 편집모드일 때만 drag/tap 판정을 위해 tag를 만든다.
          // (중요) 편집모드 OFF에서도 tag가 남아있으면 토글 시 의도치 않게 설정창이 뜰 수 있다.
          view.tag =
            if (markerEditMode) {
              MarkerTouchState(
                downRawX = event.rawX,
                downRawY = event.rawY,
                startLpX = lp.x,
                startLpY = lp.y,
                moved = false,
              )
            } else {
              null
            }
          true
        }
        MotionEvent.ACTION_MOVE -> {
          if (!markerEditMode) return@setOnTouchListener true
          val t = view.tag as? MarkerTouchState ?: return@setOnTouchListener true
          val dx = (event.rawX - t.downRawX).roundToInt()
          val dy = (event.rawY - t.downRawY).roundToInt()
          if (!t.moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) t.moved = true
          val startX = t.startLpX
          val startY = t.startLpY

          val winW = (lp.width.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
          val winH = (lp.height.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
          // (중요) 가장자리에서도 "마커 중심=좌표"가 되려면,
          // 윈도우를 화면 안쪽으로 강제 clamp하면 안 된다.
          // 중심이 0..W-1 / 0..H-1 범위만 유지되도록, 윈도우는 -win/2 .. (W-1 - win/2) 범위를 허용한다.
          val minLpX = -(winW / 2)
          val maxLpX = ((wS - 1) - (winW / 2)).coerceAtLeast(minLpX)
          val minLpY = -(winH / 2)
          val maxLpY = ((hS - 1) - (winH / 2)).coerceAtLeast(minLpY)
          val nx = (startX + dx).coerceIn(minLpX, maxLpX)
          val ny = (startY + dy).coerceIn(minLpY, maxLpY)
          lp.x = nx
          lp.y = ny
          try {
            wm?.updateViewLayout(view, lp)
          } catch (_: Throwable) {}
          updateSwipeLinkLines()

          // 이동 중에도 좌표를 즉시 계산/반영(screen 기준) + 디바운스 저장
          val centerX = (lp.x + winW / 2).coerceIn(0, (wS - 1).coerceAtLeast(0))
          val centerY = (lp.y + winH / 2).coerceIn(0, (hS - 1).coerceAtLeast(0))
          synchronized(markersLock) {
            val m = markersCache.firstOrNull { it.index == markerIndex } ?: return@synchronized
            m.xPx = centerX
            m.yPx = centerY
            m.xPct = (centerX.toDouble() / wS.toDouble()).coerceIn(0.0, 1.0)
            m.yPct = (centerY.toDouble() / hS.toDouble()).coerceIn(0.0, 1.0)
          }
          scheduleMarkersSaveDebounced()
          true
        }
        MotionEvent.ACTION_CANCEL -> {
          // CANCEL은 탭으로 취급하지 않음(자동으로 설정창이 뜨는 현상 방지). 저장만 한다.
          if (markerEditMode) {
            val winW = (lp.width.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
            val winH = (lp.height.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
            val centerX = (lp.x + winW / 2).coerceIn(0, (wS - 1).coerceAtLeast(0))
            val centerY = (lp.y + winH / 2).coerceIn(0, (hS - 1).coerceAtLeast(0))
            synchronized(markersLock) {
              val m = markersCache.firstOrNull { it.index == markerIndex } ?: return@synchronized
              m.xPx = centerX
              m.yPx = centerY
              m.xPct = (centerX.toDouble() / wS.toDouble()).coerceIn(0.0, 1.0)
              m.yPct = (centerY.toDouble() / hS.toDouble()).coerceIn(0.0, 1.0)
            }
            // (중요) 사용자 편집 저장이므로 base도 함께 갱신한다.
            saveMarkersToPrefs(markersCache, updateBase = true)
            true
          } else {
            false
          }
        }
        MotionEvent.ACTION_UP -> {
          val t = view.tag as? MarkerTouchState
          val moved = t?.moved == true
          if (markerEditMode) {
            // 손을 떼는 즉시 저장(요구사항)
            val winW = (lp.width.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
            val winH = (lp.height.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
            val centerX = (lp.x + winW / 2).coerceIn(0, (wS - 1).coerceAtLeast(0))
            val centerY = (lp.y + winH / 2).coerceIn(0, (hS - 1).coerceAtLeast(0))
            val markerNow =
              synchronized(markersLock) {
                val m = markersCache.firstOrNull { it.index == markerIndex } ?: return@synchronized null
                m.xPx = centerX
                m.yPx = centerY
                m.xPct = (centerX.toDouble() / wS.toDouble()).coerceIn(0.0, 1.0)
                m.yPct = (centerY.toDouble() / hS.toDouble()).coerceIn(0.0, 1.0)
                m
              }
            // (중요) 사용자 편집 저장이므로 base도 함께 갱신한다.
            saveMarkersToPrefs(markersCache, updateBase = true)

            // tag 없는 UP는 "진짜 탭"이 아니므로 설정창을 열지 않는다(자동 팝업 방지)
            // 또한 편집모드 토글 직후에는 시스템 이벤트로 UP가 들어올 수 있어 잠깐 억제한다.
            val now = android.os.SystemClock.uptimeMillis()
            if (t != null && !moved && now >= suppressOpenMarkerSettingsUntilMs) {
              if (markerNow != null) {
                openMarkerSettings(markerNow, lp, usable)
              }
            }
            true
          } else {
            // 편집모드 OFF: 마커 탭 -> 접근성으로 실제 클릭 수행
            val win = (lp.width.takeIf { it > 0 } ?: markerSizePx).coerceAtLeast(1)
            val rawX = lp.x + win / 2
            val rawY = lp.y + win / 2
            val screenX = rawX.coerceIn(0, (wS - 1).coerceAtLeast(0))
            val screenY = rawY.coerceIn(0, (hS - 1).coerceAtLeast(0))
            val markerNow = synchronized(markersLock) { markersCache.firstOrNull { it.index == markerIndex } }
              ?: return@setOnTouchListener false
            val totalDelay = delayWithJitterMs(markerNow.delayMs, jitterPctForMarkerDelay(markerNow))
            val ok =
              when (markerNow.kind) {
                "swipe" -> {
                  // 기본 스와이프: 현재 위치에서 오른쪽으로 일정 거리
                  val (w, h, _) = getScreenSize()
                  val toX = (screenX + dpToPx(220f)).coerceIn(0, (w - 1).coerceAtLeast(0))
                  AutoClickAccessibilityService.swipe(
                    fromX = screenX,
                    fromY = screenY,
                    toX = toX,
                    toY = screenY,
                    durationMs = pressMsForMarkerClick(markerNow).coerceAtLeast(80L),
                    delayMs = totalDelay,
                  )
                }
                "module" -> {
                  val (w, h, _) = getScreenSize()
                  val dist = dpToPx(220f)
                  val dxdy =
                    when (markerNow.moduleDir) {
                      "TAP" -> null
                      "U" -> Pair(0, -dist)
                      "D" -> Pair(0, dist)
                      "L" -> Pair(-dist, 0)
                      "R" -> Pair(dist, 0)
                      "UL" -> Pair(-dist, -dist)
                      "UR" -> Pair(dist, -dist)
                      "DL" -> Pair(-dist, dist)
                      "DR" -> Pair(dist, dist)
                      else -> Pair(dist, 0)
                    }

                  if (dxdy == null) {
                    // (요구사항) 스와이프를 제외한 마커는 더블클릭 옵션 적용
                    clickMaybeDoubleForMarker(markerNow, screenX, screenY, delayMs = totalDelay)
                  } else {
                    val toX = (screenX + dxdy.first).coerceIn(0, (w - 1).coerceAtLeast(0))
                    val toY = (screenY + dxdy.second).coerceIn(0, (h - 1).coerceAtLeast(0))
                    AutoClickAccessibilityService.swipe(
                      fromX = screenX,
                      fromY = screenY,
                      toX = toX,
                      toY = toY,
                      durationMs = pressMsForMarkerClick(markerNow).coerceAtLeast(80L),
                      delayMs = totalDelay,
                    )
                  }
                }
                else -> {
                  // 순번실행/독립실행/단독실행/방향모듈: 기본은 클릭
                  // (요구사항) 스와이프를 제외한 마커는 더블클릭 옵션 적용
                  clickMaybeDoubleForMarker(markerNow, screenX, screenY, delayMs = totalDelay)
                }
              }
            if (!ok) updateNotification("접근성 서비스가 필요합니다")
            ok
          }
        }
        else -> false
      }
    }
  }

  private fun openMarkerSettings(marker: Marker, lp: WindowManager.LayoutParams, usable: Rect) {
    val toolbarW = toolbarRoot?.width ?: dpToPx(220f)
    val center = markerCenterInUsablePx(marker, usable)
    val i =
      Intent(this, MarkerSettingsActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(MarkerSettingsActivity.EXTRA_INDEX, marker.index)
        putExtra(MarkerSettingsActivity.EXTRA_TOOLBAR_W, toolbarW)
        putExtra(MarkerSettingsActivity.EXTRA_CENTER_USABLE_X, center.first)
        putExtra(MarkerSettingsActivity.EXTRA_CENTER_USABLE_Y, center.second)
        putExtra(MarkerSettingsActivity.EXTRA_X_PCT, marker.xPct)
        putExtra(MarkerSettingsActivity.EXTRA_Y_PCT, marker.yPct)
      }
    try {
      startActivity(i)
    } catch (_: Throwable) {}
  }

  private fun pushMarkersHiddenInternal() {
    markersHiddenCount++
    applyMarkersVisibility()
  }

  private fun popMarkersHiddenInternal() {
    if (markersHiddenCount <= 0) return
    markersHiddenCount--
    applyMarkersVisibility()
  }

  private fun pushMarkerMoveModeOffByModalInternal() {
    markerMoveModeOffByModalCount++
    if (markerMoveModeOffByModalCount == 1) {
      markerEditModeBeforeModal = markerEditMode
      if (markerEditMode) {
        markerEditMode = false
        updateEditToggleIcon()
        for ((_, lp) in markerLps) applyMarkerEditFlags(lp)
        for ((idx, v) in markerViews) {
          val lp = markerLps[idx] ?: continue
          try {
            wm?.updateViewLayout(v, lp)
          } catch (_: Throwable) {}
        }
      }
    }
  }

  private fun popMarkerMoveModeOffByModalInternal() {
    if (markerMoveModeOffByModalCount <= 0) return
    markerMoveModeOffByModalCount--
    if (markerMoveModeOffByModalCount == 0) {
      if (markerEditModeBeforeModal) {
        markerEditMode = true
        updateEditToggleIcon()
        for ((_, lp) in markerLps) applyMarkerEditFlags(lp)
        for ((idx, v) in markerViews) {
          val lp = markerLps[idx] ?: continue
          try {
            wm?.updateViewLayout(v, lp)
          } catch (_: Throwable) {}
        }
      }
    }
  }

  private fun syncMarkerPositionsToPrefs() {
    val screen = getScreenSize()
    val wS = screen.width.coerceAtLeast(1)
    val hS = screen.height.coerceAtLeast(1)
    val maxX = (wS - 1).coerceAtLeast(0)
    val maxY = (hS - 1).coerceAtLeast(0)
    for (m in markersCache) {
      // (중요) 마커 종류에 따라 윈도우가 정사각이 아닐 수 있음(모듈 화살표/여백 등).
      // 따라서 width 하나로 중심을 계산하면 저장 좌표가 튀어버릴 수 있어,
      // 실제 화면 중심(px)을 기준으로 screen 좌표로 저장한다.
      val (sx, sy) = markerScreenCenterPx(m) // screen px
      val centerX = sx.coerceIn(0, maxX)
      val centerY = sy.coerceIn(0, maxY)
      m.xPx = centerX
      m.yPx = centerY
      m.xPct = (centerX.toDouble() / wS.toDouble()).coerceIn(0.0, 1.0)
      m.yPct = (centerY.toDouble() / hS.toDouble()).coerceIn(0.0, 1.0)
    }
    // (중요) 현재 화면모드의 좌표로 동기화이므로 base도 함께 갱신한다.
    saveMarkersToPrefs(markersCache, updateBase = true)
  }

  private fun addMarkerDefault() {
    // 1) 현재 오버레이 위치 동기화
    ensureMarkersLoadedAndShown()
    syncMarkerPositionsToPrefs()

    // 2) prefs 로드(캐시 기준)
    markersCache = loadMarkersFromPrefs()
    ensureUniqueMarkerIndexes(markersCache)

    // 3) 최대 개수 제한(기본 30개)
    val clickCount = markersCache.count { it.kind == "click" && it.index > 0 }
    if (clickCount >= 30) {
      updateNotification("마커 최대 30개")
      return
    }

    // (중요) index는 모든 마커에서 고유해야 한다.
    // click만 기준으로 잡으면 independent/swipe 등의 양수 index와 충돌해
    // 새 마커가 음수로 밀리며(빨간색처럼 보임) "종류는 순번인데 독립색" 현상이 생길 수 있다.
    val nextIndex = (markersCache.filter { it.index > 0 }.maxOfOrNull { it.index } ?: 0) + 1
    val screen = getScreenSize()
    val w = screen.width.coerceAtLeast(1)
    val h = screen.height.coerceAtLeast(1)

    val existingCenters =
      markersCache.map { Pair(it.xPx, it.yPx) }

    fun isFree(cx: Int, cy: Int): Boolean {
      for (p in existingCenters) {
        val dx = cx - p.first
        val dy = cy - p.second
        if (dx * dx + dy * dy < markerMinDistPx * markerMinDistPx) return false
      }
      return true
    }

    // 4) 빈 슬롯 탐색(센터 근처 그리드)
    val step = markerMinDistPx.coerceAtLeast(24)
    val startCx = (w * 0.5).roundToInt()
    val startCy = (h * 0.5).roundToInt()

    var found: Pair<Int, Int>? = null
    val maxR = 20
    for (r in 0..maxR) {
      for (dy in -r..r) {
        for (dx in -r..r) {
          if (kotlin.math.abs(dx) != r && kotlin.math.abs(dy) != r) continue
          val cx = (startCx + dx * step).coerceIn(markerSizePx / 2, (w - 1 - markerSizePx / 2).coerceAtLeast(markerSizePx / 2))
          val cy = (startCy + dy * step).coerceIn(markerSizePx / 2, (h - 1 - markerSizePx / 2).coerceAtLeast(markerSizePx / 2))
          if (isFree(cx, cy)) {
            found = Pair(cx, cy)
            break
          }
        }
        if (found != null) break
      }
      if (found != null) break
    }

    if (found == null) {
      updateNotification("추가할 공간이 없음")
      return
    }

    val cx = found.first
    val cy = found.second
    val m =
      Marker(
        index = nextIndex,
        kind = "click",
        xPx = cx,
        yPx = cy,
        xPct = (cx.toDouble() / w).coerceIn(0.0, 1.0),
        yPct = (cy.toDouble() / h).coerceIn(0.0, 1.0),
        delayMs = 300,
        jitterPct = randomDelayPctGlobal.coerceIn(0, 100),
        pressMs = 0,
      )

    markersCache.add(m)
    ensureUniqueMarkerIndexes(markersCache)
    // (중요) 마커 추가는 사용자 의도 변경이므로 base도 함께 갱신한다.
    saveMarkersToPrefs(markersCache, updateBase = true)
    refreshMarkerViews()
  }

  private fun toggleMarkerEditMode() {
    // (중요) 편집/객체보기 토글과 같은 UI 동작 중에 좌표가 튀는 현상 방지:
    // 토글 직전에 현재 markersCache 좌표를 prefs에 즉시 flush해 고정한다.
    // (주의) 여기서 오버레이 중심 재계산(sync)을 하면 좌표가 미세하게 밀릴 수 있다.
    try {
      flushMarkersSaveNow()
    } catch (_: Throwable) {}

    lastEditToggleAtMs = android.os.SystemClock.uptimeMillis()
    editToggleSeq++
    markerEditMode = !markerEditMode
    updateEditToggleIcon()
    persistToggleStatesToPrefs()
    // 토글 직후 의도치 않은 UP로 설정창이 뜨는 것을 방지
    suppressOpenMarkerSettingsUntilMs = android.os.SystemClock.uptimeMillis() + 450L
    // 이전 터치 상태 제거(마지막 마커가 자동으로 뜨는 현상 방지)
    for ((_, v) in markerViews) {
      try {
        v.tag = null
      } catch (_: Throwable) {}
    }
    // 편집 OFF일 때는 클릭-스루(터치 통과)
    for ((_, lp) in markerLps) {
      applyMarkerEditFlags(lp)
    }
    for ((idx, v) in markerViews) {
      val lp = markerLps[idx] ?: continue
      try {
        wm?.updateViewLayout(v, lp)
      } catch (_: Throwable) {}
    }
    updateSwipeLinkLines()
    updateNotification(if (markerEditMode) "편집모드 ON" else "편집모드 OFF")
  }

  private fun setMarkerEditModeInternal(enabled: Boolean) {
    if (markerEditMode == enabled) {
      persistToggleStatesToPrefs()
      return
    }
    // (중요) 토글/설정 직전에 좌표 flush
    try {
      flushMarkersSaveNow()
    } catch (_: Throwable) {}

    lastEditToggleAtMs = android.os.SystemClock.uptimeMillis()
    editToggleSeq++
    markerEditMode = enabled
    updateEditToggleIcon()
    suppressOpenMarkerSettingsUntilMs = android.os.SystemClock.uptimeMillis() + 450L
    for ((_, v) in markerViews) {
      try {
        v.tag = null
      } catch (_: Throwable) {}
    }
    for ((_, lp) in markerLps) {
      applyMarkerEditFlags(lp)
    }
    for ((idx, v) in markerViews) {
      val lp = markerLps[idx] ?: continue
      try {
        wm?.updateViewLayout(v, lp)
      } catch (_: Throwable) {}
    }
    updateSwipeLinkLines()
    persistToggleStatesToPrefs()
    updateNotification(if (markerEditMode) "편집모드 ON" else "편집모드 OFF")
  }

  private fun deleteAllMarkers() {
    markersCache.clear()
    // (중요) 마커 삭제는 사용자 의도 변경이므로 base도 함께 갱신한다.
    saveMarkersToPrefs(markersCache, updateBase = true)
    removeAllMarkerViews()
    updateNotification("마커 삭제됨")
  }

  private fun removeAllMarkerViews() {
    val wmLocal = wm
    try {
      hideAllLinkWindows()
    } catch (_: Throwable) {}
    for ((_, v) in markerViews) {
      try {
        wmLocal?.removeView(v)
      } catch (_: Throwable) {}
    }
    markerViews.clear()
    markerLps.clear()
  }

  private fun sampleAtScreen(screenX: Int, screenY: Int) {
    if (!captureReady) {
      updateNotification("캡처가 중지됨: 재시작 필요")
      return
    }
    val localInfo: FrameInfo
    val r: Int
    val g: Int
    val b: Int
    val a: Int

    // 회전 등으로 화면 크기가 바뀌면 캡처(surface/resize)와 매핑을 다시 맞춘다.
    ensureCaptureConfiguredToScreen()

    val (w, h, _) = getScreenSize()
    val clampedScreenX = screenX.coerceIn(0, (w - 1).coerceAtLeast(0))
    val clampedScreenY = screenY.coerceIn(0, (h - 1).coerceAtLeast(0))

    synchronized(frameLock) {
      val info = frameInfo ?: return
      localInfo = info

      // 캡처 해상도와 좌표 매핑(대부분 동일하지만, 안전하게 스케일 적용)
      val sx =
        if (w == 0) 0f else (clampedScreenX.toFloat() * localInfo.width.toFloat() / w.toFloat())
      val sy =
        if (h == 0) 0f else (clampedScreenY.toFloat() * localInfo.height.toFloat() / h.toFloat())
      val x = sx.roundToInt().coerceIn(0, localInfo.width - 1)
      val y = sy.roundToInt().coerceIn(0, localInfo.height - 1)

      val offset = y * localInfo.rowStride + x * localInfo.pixelStride
      if (offset + 3 >= frameBytes.size) return

      // RGBA_8888
      r = frameBytes[offset].toInt() and 0xFF
      g = frameBytes[offset + 1].toInt() and 0xFF
      b = frameBytes[offset + 2].toInt() and 0xFF
      a = frameBytes[offset + 3].toInt() and 0xFF
    }

    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
    lastPickedArgb = argb

    lastSampleScreenX = clampedScreenX
    lastSampleScreenY = clampedScreenY
    updateCoordLabel()

    val hex = String.format("#%02X%02X%02X", r, g, b)
    tvHex?.text = hex
    colorSwatch?.setBackgroundColor(argb)
    refreshColorPickerUiIfOpen()
  }

  private fun updateNotification(text: String) {
    // (요청) 상태/오류 알림 업데이트도 제거.
    // 단, MediaProjection FGS는 "최초 startForeground" 알림이 시스템 요구사항이라 완전 제거 불가.
    // 여기서는 추가 업데이트를 하지 않는다.
  }

  // 단독 TEST: 러너와 무관하게 solo_main 1회 + 콤보 1회 실행
  private fun runSoloMainOnceBlocking(mainIndex: Int) {
    // TEST는 "항상 단독메인부터" 시작해야 한다.
    // - solo_main index로 호출되면 그대로 사용
    // - solo_item index로 호출되면 parentIndex의 solo_main을 찾아 메인부터 시작
    val main =
      synchronized(markersLock) {
        val direct = markersCache.firstOrNull { it.kind == "solo_main" && it.index == mainIndex }?.copy()
        if (direct != null) return@synchronized direct
        val sub = markersCache.firstOrNull { it.kind == "solo_item" && it.index == mainIndex }
        val p = sub?.parentIndex ?: 0
        markersCache.firstOrNull { it.kind == "solo_main" && it.index == p }?.copy()
      } ?: return

    // 단독 실행 중에는 다른 마커 pause
    withGlobalPause {
      soloAbortRequested = false
      soloGotoRequestedIndex = 0
      fireMarkerOnce(main)
      if (soloAbortRequested) return@withGlobalPause

      fun parseSoloItemOrder(label: String): Pair<String, Int?> {
        // 예: A1, A10, B2 ...
        val s = label.trim()
        if (s.isEmpty()) return Pair("", null)
        var i = s.length - 1
        while (i >= 0 && s[i].isDigit()) i--
        val head = s.substring(0, i + 1)
        val tail = s.substring(i + 1)
        val num = tail.toIntOrNull()
        return Pair(head.uppercase(), num)
      }

      val items =
        synchronized(markersLock) {
          markersCache
            .filter { it.kind == "solo_item" && it.parentIndex == main.index && it.soloLabel.isNotBlank() }
            .map { it.copy() }
        }.sortedWith { a, b ->
          val (ha, na) = parseSoloItemOrder(a.soloLabel)
          val (hb, nb) = parseSoloItemOrder(b.soloLabel)
          val c1 = ha.compareTo(hb)
          if (c1 != 0) return@sortedWith c1
          when {
            na != null && nb != null -> na.compareTo(nb)
            na != null && nb == null -> -1
            na == null && nb != null -> 1
            else -> a.soloLabel.compareTo(b.soloLabel)
          }
        }

      val combo = main.soloComboCount.coerceIn(1, 10)
      var done = 0
      var p = 0

      val g0 = soloGotoRequestedIndex
      if (g0 != 0) {
        val jump = items.indexOfFirst { it.index == g0 }
        soloGotoRequestedIndex = 0
        if (jump >= 0) p = jump else soloAbortRequested = true
      }

      while (p < items.size) {
        if (soloAbortRequested) break
        if (done >= combo) break
        val it = items[p]
        try {
          Thread.sleep(delayWithJitterMs(it.delayMs, jitterPctForMarkerDelay(it)))
        } catch (_: InterruptedException) {
          break
        }
        fireMarkerOnce(it)
        if (soloAbortRequested) break
        done++

        val g = soloGotoRequestedIndex
        if (g != 0) {
          val jump = items.indexOfFirst { s -> s.index == g }
          soloGotoRequestedIndex = 0
          if (jump > p) {
            p = jump
            continue
          }
        }

        p++
        try {
          Thread.sleep(20L)
        } catch (_: InterruptedException) {
          break
        }
      }
    }
  }

  private fun stopEverything(deleteMarkers: Boolean = true) {
    requestStop("stopEverything")
    // (요청) 종료 시 만들어진 마커 전체 삭제 후 종료
    if (deleteMarkers) {
      try {
        deleteAllMarkers()
      } catch (_: Throwable) {}
    }
    removeOverlay()
    // (메모리) 완전 종료 시 템플릿/프레임 등 캐시 선해제
    clearTemplateCache("stopEverything")
    stopProjection()
    try {
      if (Build.VERSION.SDK_INT >= 24) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    } catch (_: Throwable) {}
  }

  private fun registerScreenReceiver() {
    if (screenReceiver != null) return

    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
          when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
              Handler(Looper.getMainLooper()).post {
                // 잠금/화면 꺼짐 시 입력용 오버레이가 문제를 일으키지 않도록 정리
                removeTouchOverlay()
                removeCoordInputOverlay()
              }
            }

            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT -> {
              Handler(Looper.getMainLooper()).post {
                // 화면 복귀 시 툴바가 사라진 상태 대비
                showOverlay()
              }
            }
          }
        }
      }

    screenReceiver = receiver
    try {
      val f =
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_OFF)
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_USER_PRESENT)
        }
      registerReceiver(receiver, f)
    } catch (_: Throwable) {
      screenReceiver = null
    }
  }

  private fun unregisterScreenReceiver() {
    val r = screenReceiver ?: return
    screenReceiver = null
    try {
      unregisterReceiver(r)
    } catch (_: Throwable) {}
  }

  override fun onDestroy() {
    unregisterScreenReceiver()
    // (안전) 공유 chooser 결과 receiver가 남아있으면 해제
    try {
      val r = shareChooserReceiver
      if (r != null) {
        try { unregisterReceiver(r) } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
    shareChooserReceiver = null
    try {
      unregisterDisplayListener()
    } catch (_: Throwable) {}
    try {
      resizeDebounceHandler.removeCallbacks(resizeDebounceRunnable)
    } catch (_: Throwable) {}
    // 시스템에 의한 종료/재시작 경로에서는 마커를 지우지 않는다.
    stopEverything(deleteMarkers = false)
    instance = null
    super.onDestroy()
  }

  private data class FrameInfo(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int
  )

  private data class ScreenInfo(val width: Int, val height: Int, val densityDpi: Int)

  private fun getScreenSize(): ScreenInfo {
    val wmLocal = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
    val densityDpi = resources.displayMetrics.densityDpi

    return if (Build.VERSION.SDK_INT >= 30) {
      // 회전 매핑/마커 좌표계는 "전체 화면(Real)" 기준이어야 왕복 회전 시 원복이 정확하다.
      // currentWindowMetrics는 시스템바/IME 등으로 흔들릴 수 있으므로 maximumWindowMetrics를 사용한다.
      val bounds = wmLocal.maximumWindowMetrics.bounds
      ScreenInfo(bounds.width(), bounds.height(), densityDpi)
    } else {
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION")
      wmLocal.defaultDisplay.getRealMetrics(dm)
      ScreenInfo(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }
  }
}

// Android API별 상수/호환
private object ServiceInfoCompat {
  val MEDIA_PROJECTION: Int
    get() = if (Build.VERSION.SDK_INT >= 29) {
      android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    } else {
      0
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
  return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key) as? T
}

