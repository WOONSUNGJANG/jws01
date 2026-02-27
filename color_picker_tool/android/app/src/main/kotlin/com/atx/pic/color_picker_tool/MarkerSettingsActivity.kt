package com.atx.pic.color_picker_tool

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.DisplayMetrics
import kotlin.math.roundToInt
import kotlin.math.min
import java.io.File
import android.view.Surface

class MarkerSettingsActivity : Activity() {
  companion object {
    const val EXTRA_INDEX = "index"
    const val EXTRA_TOOLBAR_W = "toolbarW"
    const val EXTRA_CENTER_USABLE_X = "centerUsableX"
    const val EXTRA_CENTER_USABLE_Y = "centerUsableY"
    const val EXTRA_X_PCT = "xPct"
    const val EXTRA_Y_PCT = "yPct"

    const val ACTION_COLOR_MODULE_PICK_RESULT = "com.atx.pic.color_picker_tool.action.COLOR_MODULE_PICK_RESULT"
    const val EXTRA_PICK_TARGET_INDEX = "targetIndex"
    const val EXTRA_PICK_HEX = "hex"
    const val EXTRA_PICK_X_U = "xUsablePx"
    const val EXTRA_PICK_Y_U = "yUsablePx"

    const val ACTION_IMAGE_MODULE_PICK_RESULT = "com.atx.pic.color_picker_tool.action.IMAGE_MODULE_PICK_RESULT"
    // (추가) solo verify 실행 전 클릭 좌표 선택 결과
    const val ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT = "com.atx.pic.color_picker_tool.action.SOLO_VERIFY_PRECLICK_PICK_RESULT"
    const val EXTRA_PICK_FILE = "fileName"
    const val EXTRA_PICK_W = "cropW"
    const val EXTRA_PICK_H = "cropH"
    const val EXTRA_PICK_X2_U = "x2UsablePx"
    const val EXTRA_PICK_Y2_U = "y2UsablePx"
    const val EXTRA_PICK_CROP_LEFT_U = "cropLeftUsablePx"
    const val EXTRA_PICK_CROP_TOP_U = "cropTopUsablePx"
    const val EXTRA_PICK_PURPOSE = "pickPurpose" // "image_module" | "solo_verify"

    const val PICK_PURPOSE_IMAGE_MODULE = "image_module"
    const val PICK_PURPOSE_SOLO_VERIFY = "solo_verify"

    // (중요) 회전 드리프트 방지용 base 키(서비스와 동일 키 사용)
    const val MARKERS_BASE_KEY = "flutter.markers_base"
    const val MARKER_BASE_ROT_KEY = "flutter.marker_base_rot"
    const val MARKER_BASE_W_KEY = "flutter.marker_base_w"
    const val MARKER_BASE_H_KEY = "flutter.marker_base_h"
  }

  private fun logSvc(msg: String) {
    try { Log.i("ScreenCaptureService", "[MarkerSettings] $msg") } catch (_: Throwable) {}
  }

  private fun prefs(): SharedPreferences =
    getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)

  private fun currentRotationCompat(): Int {
    return try {
      if (Build.VERSION.SDK_INT >= 30) {
        display?.rotation ?: Surface.ROTATION_0
      } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
      }
    } catch (_: Throwable) {
      Surface.ROTATION_0
    }
  }

  private fun getRealScreenSizePx(): Pair<Int, Int> {
    return try {
      val wm = getSystemService(WINDOW_SERVICE) as WindowManager
      if (Build.VERSION.SDK_INT >= 30) {
        val b = wm.maximumWindowMetrics.bounds
        Pair(b.width().coerceAtLeast(1), b.height().coerceAtLeast(1))
      } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
      }
    } catch (_: Throwable) {
      // fallback
      Pair(resources.displayMetrics.widthPixels.coerceAtLeast(1), resources.displayMetrics.heightPixels.coerceAtLeast(1))
    }
  }

  private fun commitMarkersJson(json: String) {
    try {
      val (w, h) = getRealScreenSizePx()
      prefs()
        .edit()
        .putString("flutter.markers", json)
        .putString(MARKERS_BASE_KEY, json)
        .putInt(MARKER_BASE_ROT_KEY, currentRotationCompat())
        .putInt(MARKER_BASE_W_KEY, w.coerceAtLeast(1))
        .putInt(MARKER_BASE_H_KEY, h.coerceAtLeast(1))
        .apply()
    } catch (_: Throwable) {
      try {
        prefs().edit().putString("flutter.markers", json).apply()
      } catch (_: Throwable) {}
    }
  }

  private var index: Int = 0
  private var pickReceiver: BroadcastReceiver? = null
  private var openedAtMs: Long = 0L
  private var hiddenForPicker: Boolean = false
  private var hidAccessibilityToolbar: Boolean = false
  private var restoredAccessibilityToolbar: Boolean = false

  private fun restoreAccessibilityToolbarOnce() {
    if (!hidAccessibilityToolbar || restoredAccessibilityToolbar) return
    restoredAccessibilityToolbar = true
    try {
      AutoClickAccessibilityService.requestShowToolbar()
    } catch (_: Throwable) {}
  }

  private fun finishWithToolbarRestore() {
    restoreAccessibilityToolbarOnce()
    // 일부 기기/타이밍에서 finish 직후 다시 숨김 레이스가 있어 1회 더 보정
    try {
      Handler(Looper.getMainLooper()).postDelayed(
        { try { restoreAccessibilityToolbarOnce() } catch (_: Throwable) {} },
        200L
      )
    } catch (_: Throwable) {}
    finish()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    openedAtMs = SystemClock.uptimeMillis()
    setContentView(R.layout.activity_marker_settings)

    // 혹시 이전 상태에서 숨김 플래그가 남아있지 않게 초기화
    try {
      hiddenForPicker = false
      window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
      window.decorView.alpha = 1f
      findViewById<View>(R.id.root)?.visibility = View.VISIBLE
    } catch (_: Throwable) {}

    index = intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0
    if (index == 0) {
      finish()
      return
    }

    // (요청) 마커설정창 표시 시 접근성 메뉴툴바 숨김
    try {
      hidAccessibilityToolbar = true
      AutoClickAccessibilityService.requestHideToolbar()
    } catch (_: Throwable) {}

    // dim 제거
    try {
      window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
      window.attributes = window.attributes.apply { dimAmount = 0f }
    } catch (_: Throwable) {}

    // 위치/크기: 화면 중앙 정렬 + 화면 안으로 clamp
    try {
      val dm = resources.displayMetrics
      val marginPx = (12f * dm.density).roundToInt()
      val minW = (260f * dm.density).roundToInt()
      val minH = (220f * dm.density).roundToInt()

      fun getUsableSizePx(): Pair<Int, Int> {
        return try {
          if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets =
              metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
              )
            val w = (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1)
            val h = (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
            Pair(w, h)
          } else {
            // pre-R fallback: displayMetrics 기준(기기마다 insets 반영이 제한적일 수 있음)
            Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
          }
        } catch (_: Throwable) {
          Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
        }
      }

      val (usableW, usableH) = getUsableSizePx()

      // 폭: usable 기준으로 적당히(너무 좁지 않게) 잡기
      val capW = (usableW * 0.96f).roundToInt().coerceAtLeast(1)
      val w = (usableW * 0.88f).roundToInt().coerceIn(minW.coerceAtMost(capW), capW.coerceAtLeast(minW))

      // 1차: 폭만 먼저 적용하고 높이는 WRAP_CONTENT로 시작
      window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
      val lp = window.attributes
      lp.gravity = Gravity.CENTER
      window.attributes = lp

      // 2차 높이: 실제 콘텐츠 높이에 맞춰 "딱" 맞추되, 하단에 약간 여유를 둠
      val content = findViewById<View>(R.id.root)
      val gapPx = (12f * dm.density).roundToInt()
      val extraPx = (24f * dm.density).roundToInt() // 설정창 1줄 크게
      content.post {
        try {
          val capH = (usableH * 0.92f).roundToInt().coerceAtLeast(1)
          val desired = (content.measuredHeight + gapPx + extraPx).coerceAtLeast(1)
          val h = desired.coerceIn(minH, capH)
          window.setLayout(w, h)
        } catch (_: Throwable) {
        }
      }
    } catch (_: Throwable) {}

    val tvTitle = findViewById<TextView>(R.id.tvTitle)
    val tvCoord = findViewById<TextView>(R.id.tvCoord)
    val tvDelayLabel = findViewById<TextView>(R.id.tvDelayLabel)
    val etDelay = findViewById<EditText>(R.id.etDelayMs)
    val tvSoloStartDelayLabel = findViewById<TextView>(R.id.tvSoloStartDelayLabel)
    val soloStartDelayRow = findViewById<View>(R.id.soloStartDelayRow)
    val etSoloStartDelay = findViewById<EditText>(R.id.etSoloStartDelay)
    val spSoloStartDelayUnit = findViewById<Spinner>(R.id.spSoloStartDelayUnit)
    val tvSoloComboLabel = findViewById<TextView>(R.id.tvSoloComboLabel)
    val spSoloComboCount = findViewById<Spinner>(R.id.spSoloComboCount)
    val btnSoloCreateCombo = findViewById<Button>(R.id.btnSoloCreateCombo)
    val btnSoloTest = findViewById<Button>(R.id.btnSoloTest)
    val tvKindLabel = findViewById<TextView>(R.id.tvKindLabel)
    val spKind = findViewById<Spinner>(R.id.spKind)
    val tvSwipeModeLabel = findViewById<TextView>(R.id.tvSwipeModeLabel)
    val spSwipeMode = findViewById<Spinner>(R.id.spSwipeMode)
    val swipeSubAlignRow = findViewById<View>(R.id.swipeSubAlignRow)
    val btnAlignH = findViewById<Button>(R.id.btnAlignH)
    val btnAlignV = findViewById<Button>(R.id.btnAlignV)
    val tvMoveUpLabel = findViewById<TextView>(R.id.tvMoveUpLabel)
    val etMoveUp = findViewById<EditText>(R.id.etMoveUpMs)
    val tvModuleDirLabel = findViewById<TextView>(R.id.tvModuleDirLabel)
    val spModuleDir = findViewById<Spinner>(R.id.spModuleDir)
    val tvColorModuleLabel = findViewById<TextView>(R.id.tvColorModuleLabel)
    val colorModuleColorRow = findViewById<View>(R.id.colorModuleColorRow)
    val vColorModuleSwatch = findViewById<View>(R.id.vColorModuleSwatch)
    val etColorModuleHex = findViewById<EditText>(R.id.etColorModuleHex)
    val tvColorModuleCoordLabel = findViewById<TextView>(R.id.tvColorModuleCoordLabel)
    val colorModuleCoordRow = findViewById<View>(R.id.colorModuleCoordRow)
    val etColorModuleCheckX = findViewById<EditText>(R.id.etColorModuleCheckX)
    val etColorModuleCheckY = findViewById<EditText>(R.id.etColorModuleCheckY)
    val tvColorModuleAccuracyLabel = findViewById<TextView>(R.id.tvColorModuleAccuracyLabel)
    val spColorModuleAccuracy = findViewById<Spinner>(R.id.spColorModuleAccuracy)
    val btnColorModulePick = findViewById<Button>(R.id.btnColorModulePick)

    val tvImageModuleLabel = findViewById<TextView>(R.id.tvImageModuleLabel)
    val imageModuleFileRow = findViewById<View>(R.id.imageModuleFileRow)
    val ivImageModulePreview = findViewById<android.widget.ImageView>(R.id.ivImageModulePreview)
    val etImageModuleFile = findViewById<EditText>(R.id.etImageModuleFile)
    val btnImageModulePick = findViewById<Button>(R.id.btnImageModulePick)
    val tvImageModuleCoordLabel = findViewById<TextView>(R.id.tvImageModuleCoordLabel)
    val imageModuleCoordRow = findViewById<View>(R.id.imageModuleCoordRow)
    val etImageModuleCheckX = findViewById<EditText>(R.id.etImageModuleCheckX)
    val etImageModuleCheckY = findViewById<EditText>(R.id.etImageModuleCheckY)
    val tvImageModuleEndCoordLabel = findViewById<TextView>(R.id.tvImageModuleEndCoordLabel)
    val imageModuleEndCoordRow = findViewById<View>(R.id.imageModuleEndCoordRow)
    val etImageModuleEndX = findViewById<EditText>(R.id.etImageModuleEndX)
    val etImageModuleEndY = findViewById<EditText>(R.id.etImageModuleEndY)
    val tvImageModuleFoundCenter = findViewById<TextView>(R.id.tvImageModuleFoundCenter)
    val tvImageModuleClickModeLabel = findViewById<TextView>(R.id.tvImageModuleClickModeLabel)
    val rgImageModuleClickMode = findViewById<android.widget.RadioGroup>(R.id.rgImageModuleClickMode)
    val rbImageClickFoundCenter = findViewById<android.widget.RadioButton>(R.id.rbImageClickFoundCenter)
    val rbImageClickMarker = findViewById<android.widget.RadioButton>(R.id.rbImageClickMarker)
    val rbImageClickSound = findViewById<android.widget.RadioButton>(R.id.rbImageClickSound)
    val rbImageClickVibrate = findViewById<android.widget.RadioButton>(R.id.rbImageClickVibrate)
    val tvImageModuleLastMatch = findViewById<TextView>(R.id.tvImageModuleLastMatch)
    val tvImageModuleAccuracyLabel = findViewById<TextView>(R.id.tvImageModuleAccuracyLabel)
    val spImageModuleAccuracy = findViewById<Spinner>(R.id.spImageModuleAccuracy)

    // solo verify(클릭실행확인) UI
    // (추가) 실행전 클릭(1회)
    val soloPreClickContainer = findViewById<android.view.ViewGroup>(R.id.soloPreClickContainer)
    val tvSoloPreClickLabel = findViewById<TextView>(R.id.tvSoloPreClickLabel)
    val cbSoloPreClickUse = findViewById<CheckBox>(R.id.cbSoloPreClickUse)
    val soloPreClickPickRow = findViewById<View>(R.id.soloPreClickPickRow)
    val btnSoloPreClickPick = findViewById<Button>(R.id.btnSoloPreClickPick)
    val tvSoloPreClickCoord = findViewById<TextView>(R.id.tvSoloPreClickCoord)

    val tvSoloVerifyLabel = findViewById<TextView>(R.id.tvSoloVerifyLabel)
    val cbSoloVerifyUse = findViewById<CheckBox>(R.id.cbSoloVerifyUse)
    val soloVerifyFileRow = findViewById<View>(R.id.soloVerifyFileRow)
    val ivSoloVerifyPreview = findViewById<android.widget.ImageView>(R.id.ivSoloVerifyPreview)
    val btnSoloVerifyPick = findViewById<Button>(R.id.btnSoloVerifyPick)
    val tvSoloVerifyModeLabel = findViewById<TextView>(R.id.tvSoloVerifyModeLabel)
    val soloVerifyModeRow = findViewById<View>(R.id.soloVerifyModeRow)
    val tvSoloVerifyModeFixed = findViewById<TextView>(R.id.tvSoloVerifyModeFixed)
    val soloVerifyGotoRow1 = findViewById<View>(R.id.soloVerifyGotoRow1)
    val tvSoloVerifyGotoLabel1 = findViewById<TextView>(R.id.tvSoloVerifyGotoLabel1)
    val spSoloVerifyGoto1 = findViewById<Spinner>(R.id.spSoloVerifyGoto1)
    // (변경) 실행확인 방식은 1가지로 고정. mode2/재개2 UI는 삭제됨.
    val tvSoloVerifyAccuracyLabel = findViewById<TextView>(R.id.tvSoloVerifyAccuracyLabel)
    val spSoloVerifyAccuracy = findViewById<Spinner>(R.id.spSoloVerifyAccuracy)
    val tvModulePatternLabel = findViewById<TextView>(R.id.tvModulePatternLabel)
    val spModulePattern = findViewById<Spinner>(R.id.spModulePattern)
    val tvModuleLenLabel = findViewById<TextView>(R.id.tvModuleLenLabel)
    val etModuleLenPx = findViewById<EditText>(R.id.etModuleLenPx)
    val tvModuleDirModeLabel = findViewById<TextView>(R.id.tvModuleDirModeLabel)
    val spModuleDirMode = findViewById<Spinner>(R.id.spModuleDirMode)
    val tvModuleMoveUpLabel = findViewById<TextView>(R.id.tvModuleMoveUpLabel)
    val etModuleMoveUpMs = findViewById<EditText>(R.id.etModuleMoveUpMs)
    val tvModuleExecModeLabel = findViewById<TextView>(R.id.tvModuleExecModeLabel)
    val spModuleExecMode = findViewById<Spinner>(R.id.spModuleExecMode)
    val tvJitterLabel = findViewById<TextView>(R.id.tvJitterLabel)
    val sbJitter = findViewById<SeekBar>(R.id.sbJitter)
    val tvJitter = findViewById<TextView>(R.id.tvJitter)
    val tvPressLabel = findViewById<TextView>(R.id.tvPressLabel)
    val etPress = findViewById<EditText>(R.id.etPressMs)
    val cbAiDefense = findViewById<CheckBox>(R.id.cbAiDefense)
    val cbDoubleClick = findViewById<CheckBox>(R.id.cbDoubleClick)
    val btnSave = findViewById<Button>(R.id.btnSave)
    val btnDelete = findViewById<Button>(R.id.btnDelete)
    val btnClose = findViewById<Button>(R.id.btnClose)

    val lang = I18n.langFromPrefs(prefs())
    try { window.decorView.layoutDirection = if (I18n.isRtl(lang)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR } catch (_: Throwable) {}
    try { tvKindLabel.text = I18n.markerKindLabel(lang) } catch (_: Throwable) {}
    try { tvDelayLabel.text = I18n.delayLabel(lang) } catch (_: Throwable) {}
    try { tvJitterLabel.text = I18n.jitterLabel(lang) } catch (_: Throwable) {}
    try { tvPressLabel.text = I18n.pressLabel(lang) } catch (_: Throwable) {}
    try { tvMoveUpLabel.text = I18n.moveUpLabel(lang) } catch (_: Throwable) {}
    try { tvSwipeModeLabel.text = I18n.swipeModeLabel(lang) } catch (_: Throwable) {}
    try { tvModulePatternLabel.text = I18n.modulePatternLabel(lang) } catch (_: Throwable) {}
    try { tvModuleLenLabel.text = I18n.moduleLenLabel(lang) } catch (_: Throwable) {}
    try { tvModuleDirModeLabel.text = I18n.moduleDirModeLabel(lang) } catch (_: Throwable) {}
    try { tvModuleExecModeLabel.text = I18n.moduleExecModeLabel(lang) } catch (_: Throwable) {}
    try { tvModuleMoveUpLabel.text = I18n.moduleMoveUpLabel(lang) } catch (_: Throwable) {}
    try { tvSoloStartDelayLabel.text = I18n.soloStartDelayLabel(lang) } catch (_: Throwable) {}
    try { tvSoloComboLabel.text = I18n.soloComboLabel(lang) } catch (_: Throwable) {}
    try { btnSoloCreateCombo.text = I18n.soloCreateCombo(lang) } catch (_: Throwable) {}
    try { btnSoloTest.text = I18n.test(lang) } catch (_: Throwable) {}
    try { btnAlignH.text = I18n.alignH(lang) } catch (_: Throwable) {}
    try { btnAlignV.text = I18n.alignV(lang) } catch (_: Throwable) {}
    try { btnSave.text = I18n.save(lang) } catch (_: Throwable) {}
    try { btnDelete.text = I18n.delete(lang) } catch (_: Throwable) {}
    try { btnClose.text = I18n.close(lang) } catch (_: Throwable) {}
    try { cbAiDefense.text = I18n.aiDefense(lang) } catch (_: Throwable) {}
    try { cbDoubleClick.text = "더블클릭" } catch (_: Throwable) {}
    try { tvImageModuleLabel.text = I18n.imageModuleLabel(lang) } catch (_: Throwable) {}
    fun refreshImagePickButtonLabel() {
      val hasFile = !etImageModuleFile.text?.toString().orEmpty().trim().isBlank()
      btnImageModulePick.text = if (hasFile) I18n.imageEdit(lang) else I18n.imagePick(lang)
    }
    try { refreshImagePickButtonLabel() } catch (_: Throwable) {}
    try { tvImageModuleAccuracyLabel.text = I18n.imageAccuracyLabel(lang) } catch (_: Throwable) {}
    try { tvImageModuleCoordLabel.text = I18n.imageStartCoordLabel(lang) } catch (_: Throwable) {}
    try { tvImageModuleEndCoordLabel.text = I18n.imageEndCoordLabel(lang) } catch (_: Throwable) {}
    try { tvImageModuleFoundCenter.text = I18n.imageFoundCenterEmpty(lang) } catch (_: Throwable) {}
    try { tvImageModuleClickModeLabel.text = I18n.imageClickModeLabel(lang) } catch (_: Throwable) {}
    try { rbImageClickFoundCenter.text = I18n.imageClickFoundCenter(lang) } catch (_: Throwable) {}
    try { rbImageClickMarker.text = I18n.imageClickMarker(lang) } catch (_: Throwable) {}
    try { rbImageClickSound.text = I18n.imageClickSound(lang) } catch (_: Throwable) {}
    try { rbImageClickVibrate.text = I18n.imageClickVibrate(lang) } catch (_: Throwable) {}
    try { tvImageModuleLastMatch.text = I18n.imageLastMatchEmpty(lang) } catch (_: Throwable) {}

    try { tvSoloVerifyLabel.text = I18n.soloVerifyLabel(lang) } catch (_: Throwable) {}
    try { cbSoloVerifyUse.text = I18n.soloVerifyUse(lang) } catch (_: Throwable) {}
    try { tvSoloVerifyModeLabel.text = I18n.soloVerifyModeLabel(lang) } catch (_: Throwable) {}
    try { tvSoloVerifyAccuracyLabel.text = I18n.imageAccuracyLabel(lang) } catch (_: Throwable) {}
    try { tvSoloVerifyModeFixed.text = I18n.soloVerifyModeFailRetry(lang) } catch (_: Throwable) {}

    // 실행전 클릭(1회)
    try { tvSoloPreClickLabel.text = I18n.soloPreClickLabel(lang) } catch (_: Throwable) {}
    try { cbSoloPreClickUse.text = I18n.soloPreClickUse(lang) } catch (_: Throwable) {}
    try { btnSoloPreClickPick.text = I18n.soloPreClickPick(lang) } catch (_: Throwable) {}

    tvTitle.text = "${I18n.markerSettingsTitle(lang)} (#$index)"

    // 표시용 좌표 (스크린샷 형태)
    val cx = intent?.getIntExtra(EXTRA_CENTER_USABLE_X, -1) ?: -1
    val cy = intent?.getIntExtra(EXTRA_CENTER_USABLE_Y, -1) ?: -1
    if (cx >= 0 && cy >= 0) {
      tvCoord.text = "Mark $index XY픽셀:\nx=${cx}px, y=${cy}px"
    } else {
      tvCoord.text = "Mark $index XY픽셀:\nx=?, y=?"
    }
    // (요청) 모든 마커 설정창에서 상단 Mark XY 좌표 표시 숨김
    try {
      tvCoord.visibility = View.GONE
    } catch (_: Throwable) {}

    // 숫자 입력 설정(키보드 과도 UI 방지)
    etDelay.inputType = InputType.TYPE_CLASS_NUMBER
    etPress.inputType = InputType.TYPE_CLASS_NUMBER
    if (Build.VERSION.SDK_INT >= 26) {
      etDelay.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
      etPress.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    // prefs에서 현재 마커 읽기
    val m = loadMarker(index)
    val kindRaw = m?.optString("kind", "click")?.ifBlank { "click" } ?: "click"
    val isSwipeSub = (kindRaw == "swipe_to")
    val isSoloItem = (kindRaw == "solo_item")
    val doubleClick0 =
      run {
        // 스와이프는 제외(요구사항)
        if (kindRaw == "swipe" || kindRaw == "swipe_to") false else (m?.optBoolean("doubleClick", false) ?: false)
      }
    val swipeMode0 = (m?.optInt("swipeMode", 0) ?: 0).coerceIn(0, 1) // 0=순번,1=독립
    val moveUp0 = (m?.optInt("moveUpMs", 700) ?: 700).coerceIn(0, 600000)
    val soloStartDelayMs0 = (m?.optInt("soloStartDelayMs", 720000) ?: 720000).coerceIn(0, 3600000)
    val soloComboCount0 = (m?.optInt("soloComboCount", 1) ?: 1).coerceIn(1, 10)
    val kind0 =
      when (kindRaw) {
        "swipe_to" -> "swipe"
        "solo_item" -> "solo_main" // UI상 단독으로 보이되 저장 시 solo_item 유지
        else -> kindRaw
      }
    val moduleDir0 = m?.optString("moduleDir", "R")?.ifBlank { "R" } ?: "R"
    val modulePatternV20 = (m?.optInt("modulePatternV2", 0) ?: 0).coerceAtLeast(0)
    val modulePatternPresent = try { m?.has("modulePattern") == true } catch (_: Throwable) { false }
    val modulePatternRaw0 = (m?.optInt("modulePattern", 0) ?: 0)
    val moduleLenPx0 = (m?.optInt("moduleLenPx", 0) ?: 0)
    // (요청) 방향모듈: Move up 기본값 700ms
    val moduleMoveUpMs0 =
      (m?.optInt("moduleMoveUpMs", if (kind0 == "module") 700 else 0) ?: if (kind0 == "module") 700 else 0)
        .coerceIn(0, 600000)
    val moduleDirMode0 = (m?.optInt("moduleDirMode", 0) ?: 0).coerceIn(0, 1)
    val moduleSoloExec0 = (m?.optBoolean("moduleSoloExec", false) ?: false)
    val colorR0 = (m?.optInt("colorR", -1) ?: -1)
    val colorG0 = (m?.optInt("colorG", -1) ?: -1)
    val colorB0 = (m?.optInt("colorB", -1) ?: -1)
    val colorCheckX0 = (m?.optInt("colorCheckXPx", -1) ?: -1)
    val colorCheckY0 = (m?.optInt("colorCheckYPx", -1) ?: -1)
    val colorAcc0 = (m?.optInt("colorAccuracyPct", 100) ?: 100).coerceIn(50, 100)
    val imageFile0 = (m?.optString("imageTemplateFile", "") ?: "").trim()
    // (호환) 기존 key(imageCheckXPx/Y) -> 신규 key(imageStartXPx/Y)
    val imageStartX0 = (m?.optInt("imageStartXPx", (m?.optInt("imageCheckXPx", -1) ?: -1)) ?: -1)
    val imageStartY0 = (m?.optInt("imageStartYPx", (m?.optInt("imageCheckYPx", -1) ?: -1)) ?: -1)
    val imageEndX0 = (m?.optInt("imageEndXPx", -1) ?: -1)
    val imageEndY0 = (m?.optInt("imageEndYPx", -1) ?: -1)
    val imageAcc0 = (m?.optInt("imageAccuracyPct", 90) ?: 90).coerceIn(50, 100)
    val imageW0 = (m?.optInt("imageW", 128) ?: 128).coerceIn(8, 1024)
    val imageH0 = (m?.optInt("imageH", 128) ?: 128).coerceIn(8, 1024)
    val foundCenterX0 = (m?.optInt("imageFoundCenterXPx", -1) ?: -1)
    val foundCenterY0 = (m?.optInt("imageFoundCenterYPx", -1) ?: -1)
    val imageClickMode0 = (m?.optInt("imageClickMode", 0) ?: 0).coerceIn(0, 3) // 0=마커,1=찾은중앙,2=소리,3=진동
    val lastScore0 = (m?.optInt("imageLastScorePct", -1) ?: -1)
    val lastMin0 = (m?.optInt("imageLastMinPct", -1) ?: -1)
    val lastOk0 = (m?.optInt("imageLastOk", -1) ?: -1)
    val cropLeft0 = (m?.optInt("imageCropLeftXPx", -1) ?: -1)
    val cropTop0 = (m?.optInt("imageCropTopYPx", -1) ?: -1)
    var pickedImageW = imageW0
    var pickedImageH = imageH0
    var pickedCropLeft = cropLeft0
    var pickedCropTop = cropTop0
    // 지연시간 기본값:
    // - 방향모듈: 1000ms
    // - 이미지모듈: 1500ms
    val defaultDelayMs =
      when (kind0) {
        "module" -> 1000
        "image_module" -> 1500
        else -> 300
      }
    val delay0 = (m?.optInt("delayMs", defaultDelayMs) ?: defaultDelayMs).coerceIn(0, 600000)
    val jitter0 = (m?.optInt("jitterPct", 50) ?: 50).coerceIn(0, 100)
    val press0 = (m?.optInt("pressMs", 90) ?: 90).coerceIn(0, 600000)
    // (요청) AI탐지방어 체크박스: 기본 체크(true). 단독/서브/색상모듈은 제외.
    val aiDefense0 = (m?.optBoolean("randomClickUse", true) ?: true)

    // (요청) 화면공유(MediaProjection) 거부/미허용 상태면 6.색상모듈은 선택/표시하지 않는다.
    val captureReady = try { prefs().getBoolean("flutter.capture_ready", false) } catch (_: Throwable) { false }

    // solo verify 값
    val soloVerifyUse0 = (m?.optBoolean("soloVerifyUse", false) ?: false)
    val soloVerifyMode0 = (m?.optInt("soloVerifyOnFoundMode", 0) ?: 0).coerceIn(0, 1)
    val soloVerifyFile0 = (m?.optString("soloVerifyTemplateFile", "") ?: "").trim()
    val soloVerifyStartX0 = (m?.optInt("soloVerifyStartXPx", -1) ?: -1)
    val soloVerifyStartY0 = (m?.optInt("soloVerifyStartYPx", -1) ?: -1)
    val soloVerifyEndX0 = (m?.optInt("soloVerifyEndXPx", -1) ?: -1)
    val soloVerifyEndY0 = (m?.optInt("soloVerifyEndYPx", -1) ?: -1)
    val soloVerifyAcc0 = (m?.optInt("soloVerifyAccuracyPct", 80) ?: 80).coerceIn(50, 100)
    val soloVerifyW0 = (m?.optInt("soloVerifyW", 128) ?: 128).coerceIn(8, 1024)
    val soloVerifyH0 = (m?.optInt("soloVerifyH", 128) ?: 128).coerceIn(8, 1024)
    val soloVerifyCropLeft0 = (m?.optInt("soloVerifyCropLeftXPx", -1) ?: -1)
    val soloVerifyCropTop0 = (m?.optInt("soloVerifyCropTopYPx", -1) ?: -1)
    // (추가) solo verify "재개" 시 goto 대상 (0이면 기존처럼 단독 종료)
    val soloVerifyGotoOnStopMissing0 = (m?.optInt("soloVerifyGotoOnStopMissing", 0) ?: 0)
    val soloVerifyGotoOnStopFound0 = (m?.optInt("soloVerifyGotoOnStopFound", 0) ?: 0)
    // (추가) solo verify 실행 전 클릭(1회)
    val soloPreClickUse0 = (m?.optBoolean("soloPreClickUse", false) ?: false)
    val soloPreClickX0 = (m?.optInt("soloPreClickXPx", -1) ?: -1)
    val soloPreClickY0 = (m?.optInt("soloPreClickYPx", -1) ?: -1)

    try {
      logSvc(
        "load idx=$index kindRaw=$kindRaw captureReady=$captureReady " +
          "soloVerifyUse0=$soloVerifyUse0 mode0=$soloVerifyMode0 tpl0=$soloVerifyFile0 " +
          "gotoMissing0=$soloVerifyGotoOnStopMissing0 gotoFound0=$soloVerifyGotoOnStopFound0 " +
          "preClick0=$soloPreClickUse0 pre0=($soloPreClickX0,$soloPreClickY0)"
      )
    } catch (_: Throwable) {}
    var soloVerifyFileCur = soloVerifyFile0
    var soloVerifyStartXCur = soloVerifyStartX0
    var soloVerifyStartYCur = soloVerifyStartY0
    var soloVerifyEndXCur = soloVerifyEndX0
    var soloVerifyEndYCur = soloVerifyEndY0
    var pickedSoloVerifyW = soloVerifyW0
    var pickedSoloVerifyH = soloVerifyH0
    var pickedSoloVerifyCropLeft = soloVerifyCropLeft0
    var pickedSoloVerifyCropTop = soloVerifyCropTop0
    var soloPreClickXCur = soloPreClickX0
    var soloPreClickYCur = soloPreClickY0

    fun refreshSoloPreClickCoordLabel() {
      try {
        // (-1,-1)은 "미선택"으로 취급(취소). 그 외(음수 포함)는 그대로 표시한다.
        if (!(soloPreClickXCur == -1 && soloPreClickYCur == -1)) {
          tvSoloPreClickCoord.text = "x=${soloPreClickXCur}, y=${soloPreClickYCur}"
        } else {
          tvSoloPreClickCoord.text = "x=-, y=-"
        }
      } catch (_: Throwable) {}
    }

    // 사용자 요구 6종 고정
    val kinds =
      mutableListOf(
        "click" to I18n.markerKindName(lang, "click"),
        "independent" to I18n.markerKindName(lang, "independent"),
        "swipe" to I18n.markerKindName(lang, "swipe"),
        "solo_main" to I18n.markerKindName(lang, "solo_main"),
        "module" to I18n.markerKindName(lang, "module"),
      ).apply {
        if (captureReady) {
          add("color_module" to I18n.markerKindName(lang, "color_module"))
          add("image_module" to I18n.markerKindName(lang, "image_module"))
        } else {
          // 이미 색상모듈인 경우에는 "변경/선택"을 막기 위해 표시만 유지(스피너 비활성)
          if (kind0 == "color_module") add("color_module" to "${I18n.markerKindName(lang, "color_module")}(${I18n.screenShareRequired(lang)})")
          if (kind0 == "image_module") add("image_module" to "${I18n.markerKindName(lang, "image_module")}(${I18n.screenShareRequired(lang)})")
        }
      }
    val adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        kinds.map { it.second }
      ).apply {
        setDropDownViewResource(R.layout.spinner_marker_kind_dropdown)
      }
    spKind.adapter = adapter
    val kindIndex = kinds.indexOfFirst { it.first == kind0 }.let { if (it >= 0) it else 0 }
    spKind.setSelection(kindIndex, false)
    if (!captureReady && (kind0 == "color_module" || kind0 == "image_module")) {
      // 캡처가 없으면 색상모듈은 사용 불가하므로 종류 변경을 막는다.
      spKind.isEnabled = false
      spKind.isClickable = false
    }

    val moduleDirs =
      listOf(
        "TAP" to I18n.dirName(lang, "TAP"),
        "U" to I18n.dirName(lang, "U"),
        "D" to I18n.dirName(lang, "D"),
        "L" to I18n.dirName(lang, "L"),
        "R" to I18n.dirName(lang, "R"),
        "UL" to I18n.dirName(lang, "UL"),
        "UR" to I18n.dirName(lang, "UR"),
        "DL" to I18n.dirName(lang, "DL"),
        "DR" to I18n.dirName(lang, "DR"),
      )
    val dirAdapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        moduleDirs.map { it.second }
      ).apply {
        setDropDownViewResource(R.layout.spinner_marker_kind_dropdown)
      }
    spModuleDir.adapter = dirAdapter
    val dirIndex = moduleDirs.indexOfFirst { it.first == moduleDir0 }.let { if (it >= 0) it else 0 }
    spModuleDir.setSelection(dirIndex, false)

    // ----- 색상모듈 UI 준비 -----
    // 정확도(50~100)
    val accs = (50..100).toList()
    spColorModuleAccuracy.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        accs.map { "$it%" }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
    spColorModuleAccuracy.setSelection((colorAcc0 - 50).coerceIn(0, 50), false)

    fun rgbToHex(r: Int, g: Int, b: Int): String {
      fun c(v: Int) = v.coerceIn(0, 255)
      return String.format("#%02X%02X%02X", c(r), c(g), c(b))
    }

    fun parseHexToRgb(s: String): Triple<Int, Int, Int>? {
      val t = s.trim().removePrefix("#")
      if (t.length != 6) return null
      return try {
        val v = t.toInt(16)
        Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
      } catch (_: Throwable) {
        null
      }
    }

    val initialHex =
      if (colorR0 in 0..255 && colorG0 in 0..255 && colorB0 in 0..255) rgbToHex(colorR0, colorG0, colorB0) else "#FFFFFF"
    etColorModuleHex.setText(initialHex)
    try {
      vColorModuleSwatch.setBackgroundColor(Color.parseColor(initialHex))
    } catch (_: Throwable) {}

    // 체크좌표: 저장값이 없으면 현재 마커 좌표(usable px)로 초기화
    etColorModuleCheckX.inputType = InputType.TYPE_CLASS_NUMBER
    etColorModuleCheckY.inputType = InputType.TYPE_CLASS_NUMBER
    etColorModuleCheckX.setText((if (colorCheckX0 >= 0) colorCheckX0 else cx.coerceAtLeast(0)).toString())
    etColorModuleCheckY.setText((if (colorCheckY0 >= 0) colorCheckY0 else cy.coerceAtLeast(0)).toString())

    // ----- 이미지모듈 UI 준비 -----
    fun atxImgDir(): File {
      val base = getExternalFilesDir(null) ?: filesDir
      val d = File(base, "atximg")
      if (!d.exists()) d.mkdirs()
      return d
    }

    fun refreshImagePreview(fileName: String) {
      try {
        if (fileName.isBlank()) {
          ivImageModulePreview.setImageDrawable(null)
          return
        }
        val f = File(atxImgDir(), fileName)
        if (!f.exists() || !f.isFile) {
          ivImageModulePreview.setImageDrawable(null)
          return
        }
        val targetPx = (40f * resources.displayMetrics.density).roundToInt().coerceAtLeast(24)
        val opts0 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts0)
        var sample = 1
        while (opts0.outWidth / sample > targetPx * 2 || opts0.outHeight / sample > targetPx * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
          ?: android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        ivImageModulePreview.setImageBitmap(bmp)
      } catch (_: Throwable) {
        try { ivImageModulePreview.setImageDrawable(null) } catch (_: Throwable) {}
      }
    }

    fun refreshSoloVerifyPreview(fileName: String) {
      try {
        val tag = "SoloVerifyPreview"
        if (fileName.isBlank()) {
          ivSoloVerifyPreview.setImageDrawable(null)
          return
        }
        val f = File(atxImgDir(), fileName)
        val abs = try { f.absolutePath } catch (_: Throwable) { "(absPathFail)" }
        if (!f.exists() || !f.isFile) {
          ivSoloVerifyPreview.setImageDrawable(null)
          try {
            Log.i("MarkerSettings", "$tag missing file=$fileName path=$abs exists=${f.exists()} isFile=${f.isFile}")
            Log.i("ScreenCaptureService", "$tag missing file=$fileName path=$abs exists=${f.exists()} isFile=${f.isFile}")
          } catch (_: Throwable) {}
          return
        }
        val targetPx = (40f * resources.displayMetrics.density).roundToInt().coerceAtLeast(24)
        val opts0 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts0)
        var sample = 1
        while (opts0.outWidth / sample > targetPx * 2 || opts0.outHeight / sample > targetPx * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
          ?: android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        ivSoloVerifyPreview.setImageBitmap(bmp)
        try {
          Log.i("MarkerSettings", "$tag ok file=$fileName path=$abs size=${f.length()} bounds=${opts0.outWidth}x${opts0.outHeight} sample=$sample bmpNull=${bmp == null}")
          Log.i("ScreenCaptureService", "$tag ok file=$fileName path=$abs size=${f.length()} bounds=${opts0.outWidth}x${opts0.outHeight} sample=$sample bmpNull=${bmp == null}")
        } catch (_: Throwable) {}
      } catch (_: Throwable) {
        try { ivSoloVerifyPreview.setImageDrawable(null) } catch (_: Throwable) {}
        try { Log.i("ScreenCaptureService", "SoloVerifyPreview exception") } catch (_: Throwable) {}
      }
    }

    fun refreshSoloVerifyPickButtonLabel() {
      val hasFile = soloVerifyFileCur.isNotBlank()
      try {
        btnSoloVerifyPick.text = if (hasFile) I18n.imageEdit(lang) else I18n.imagePick(lang)
      } catch (_: Throwable) {}
    }

    fun forceShowSoloVerifyUi() {
      try {
        tvSoloVerifyLabel.visibility = View.VISIBLE
        cbSoloVerifyUse.visibility = View.VISIBLE
        soloVerifyFileRow.visibility = View.VISIBLE
        tvSoloVerifyModeLabel.visibility = View.VISIBLE
        soloVerifyModeRow.visibility = View.VISIBLE
        tvSoloVerifyAccuracyLabel.visibility = View.VISIBLE
        spSoloVerifyAccuracy.visibility = View.VISIBLE
      } catch (_: Throwable) {}
    }

    fun refreshSoloVerifyPreviewSoon() {
      try {
        Handler(Looper.getMainLooper()).postDelayed(
          { try { refreshSoloVerifyPreview(soloVerifyFileCur) } catch (_: Throwable) {} },
          120L
        )
      } catch (_: Throwable) {}
    }

    fun refreshImagePreviewSoon() {
      try {
        Handler(Looper.getMainLooper()).postDelayed(
          {
            try {
              val f = etImageModuleFile.text?.toString().orEmpty().trim()
              refreshImagePreview(f)
            } catch (_: Throwable) {}
          },
          120L
        )
      } catch (_: Throwable) {}
    }
    // solo verify 초기값 반영
    try { cbSoloVerifyUse.isChecked = soloVerifyUse0 } catch (_: Throwable) {}
    // 실행전 클릭(1회) 초기값
    try { cbSoloPreClickUse.isChecked = soloPreClickUse0 } catch (_: Throwable) {}
    refreshSoloPreClickCoordLabel()
    // solo verify 모드(2개 중 1개 선택)
    // (변경) 실행확인 방식은 1가지로 고정. mode 체크박스가 없으므로 별도 초기화 불필요.
    refreshSoloVerifyPreview(soloVerifyFileCur)
    refreshSoloVerifyPickButtonLabel()


    etImageModuleFile.setText(imageFile0.ifBlank { "" })
    refreshImagePreview(imageFile0)
    refreshImagePickButtonLabel()
    // (요청) 파일이름은 수정 불가(자동 생성/수정 모드)
    try {
      etImageModuleFile.keyListener = null
      etImageModuleFile.isFocusable = false
      etImageModuleFile.isCursorVisible = false
    } catch (_: Throwable) {}
    etImageModuleCheckX.inputType = InputType.TYPE_CLASS_NUMBER
    etImageModuleCheckY.inputType = InputType.TYPE_CLASS_NUMBER
    etImageModuleEndX.inputType = InputType.TYPE_CLASS_NUMBER
    etImageModuleEndY.inputType = InputType.TYPE_CLASS_NUMBER
    val sx0 = (if (imageStartX0 >= 0) imageStartX0 else cx.coerceAtLeast(0)).coerceAtLeast(0)
    val sy0 = (if (imageStartY0 >= 0) imageStartY0 else cy.coerceAtLeast(0)).coerceAtLeast(0)
    val ex0 =
      // (요청) 이미지모듈 검색영역 기본값: 200x200 -> 500x500
      (if (imageEndX0 >= 0) imageEndX0 else (sx0 + 500)).coerceAtLeast(0)
    val ey0 =
      // (요청) 이미지모듈 검색영역 기본값: 200x200 -> 500x500
      (if (imageEndY0 >= 0) imageEndY0 else (sy0 + 500)).coerceAtLeast(0)
    etImageModuleCheckX.setText(sx0.toString())
    etImageModuleCheckY.setText(sy0.toString())
    etImageModuleEndX.setText(ex0.toString())
    etImageModuleEndY.setText(ey0.toString())
    try {
      tvImageModuleFoundCenter.text =
        if (foundCenterX0 >= 0 && foundCenterY0 >= 0) I18n.imageFoundCenter(lang, foundCenterX0, foundCenterY0)
        else I18n.imageFoundCenterEmpty(lang)
    } catch (_: Throwable) {}
    try {
      tvImageModuleLastMatch.text =
        if (lastScore0 >= 0 && lastMin0 >= 0 && lastOk0 >= 0) {
          I18n.imageLastMatch(lang, lastScore0.coerceIn(0, 100), lastMin0.coerceIn(0, 100), lastOk0 == 1)
        } else {
          I18n.imageLastMatchEmpty(lang)
        }
    } catch (_: Throwable) {}

    // 클릭 방식 기본 선택(기본: 마커 위치 클릭)
    try {
      when (imageClickMode0) {
        1 -> rbImageClickFoundCenter.isChecked = true
        2 -> rbImageClickSound.isChecked = true
        3 -> rbImageClickVibrate.isChecked = true
        else -> rbImageClickMarker.isChecked = true
      }
    } catch (_: Throwable) {}
    // 정확도(50~100)
    spImageModuleAccuracy.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        accs.map { "$it%" }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
    spImageModuleAccuracy.setSelection((imageAcc0 - 50).coerceIn(0, 50), false)

    // solo verify 정확도(50~100)
    spSoloVerifyAccuracy.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        accs.map { "$it%" }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
    spSoloVerifyAccuracy.setSelection((soloVerifyAcc0 - 50).coerceIn(0, 50), false)

    // (변경) 실행확인 방식은 1가지로 고정
    try { tvSoloVerifyModeLabel.text = I18n.soloVerifyModeLabel(lang) } catch (_: Throwable) {}
    try { tvSoloVerifyModeFixed.text = I18n.soloVerifyModeFailRetry(lang) } catch (_: Throwable) {}
    // (추가) solo verify "재개(goto)" 라벨
    try { tvSoloVerifyGotoLabel1.text = "재개 →" } catch (_: Throwable) {}

    // ---- solo verify goto(재개 시 단독sub로 점프) ----
    data class SoloGotoOpt(val targetIndex: Int, val title: String)
    fun parseSoloItemOrder(label: String): Pair<String, Int?> {
      val s = label.trim()
      if (s.isEmpty()) return Pair("", null)
      var i = s.length - 1
      while (i >= 0 && s[i].isDigit()) i--
      val head = s.substring(0, i + 1)
      val tail = s.substring(i + 1)
      val num = tail.toIntOrNull()
      return Pair(head.uppercase(), num)
    }
    fun loadSoloSubOptionsForGoto(): List<SoloGotoOpt> {
      val raw = prefs().getString("flutter.markers", "[]") ?: "[]"
      val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
      val parentIdx =
        if (kindRaw == "solo_item") (m?.optInt("parentIndex", 0) ?: 0) else index
      val subs = mutableListOf<JSONObject>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("kind") != "solo_item") continue
        if (o.optInt("parentIndex", 0) != parentIdx) continue
        if (o.optString("soloLabel", "").trim().isBlank()) continue
        subs.add(o)
      }
      val ordered =
        subs.sortedWith { a, b ->
          val (ha, na) = parseSoloItemOrder(a.optString("soloLabel", ""))
          val (hb, nb) = parseSoloItemOrder(b.optString("soloLabel", ""))
          val c1 = ha.compareTo(hb)
          if (c1 != 0) return@sortedWith c1
          when {
            na != null && nb != null -> na.compareTo(nb)
            na != null && nb == null -> -1
            na == null && nb != null -> 1
            else -> a.optString("soloLabel", "").compareTo(b.optString("soloLabel", ""))
          }
        }

      val curPos = ordered.indexOfFirst { it.optInt("index") == index }
      val startPos =
        when {
          kindRaw == "solo_item" && curPos >= 0 -> curPos // 현재 단독sub + 이후
          else -> 0 // solo_main: 전체
        }

      val out = mutableListOf<SoloGotoOpt>()
      out.add(SoloGotoOpt(0, "단독 종료(기존 재개)"))
      for (p in startPos until ordered.size) {
        val o = ordered[p]
        val idxT = o.optInt("index")
        val lab = o.optString("soloLabel", "").trim()
        val head = if (p == startPos && kindRaw == "solo_item") "현재 단독sub" else "이후 단독sub"
        out.add(SoloGotoOpt(idxT, "$head: $lab"))
      }
      return out
    }

    val soloGotoOpts = loadSoloSubOptionsForGoto()
    val soloGotoTitles = soloGotoOpts.map { it.title }
    val soloGotoAdapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        soloGotoTitles
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
    spSoloVerifyGoto1.adapter = soloGotoAdapter

    // (중요) Kotlin 로컬 함수는 선언 이전 참조가 컴파일 실패할 수 있어,
    // goto/픽커 리스너에서 호출하기 전에 먼저 정의한다.
    fun updateSoloVerifyVisibility(kind: String) {
      // (요청) 화면공유 권한(캡처) 허용일 때만 표시
      val eligible = captureReady && !isSwipeSub && (kind == "solo_main" || isSoloItem)
      val baseVis = if (eligible) View.VISIBLE else View.GONE
      tvSoloVerifyLabel.visibility = baseVis
      cbSoloVerifyUse.visibility = baseVis
      val detailVis = if (eligible && cbSoloVerifyUse.isChecked) View.VISIBLE else View.GONE
      // (추가) 실행전 클릭(1회): "goto 선택"을 한 경우에만 연결/표시
      val gotoT1 =
        if (detailVis == View.VISIBLE) {
          try { soloGotoOpts.getOrNull(spSoloVerifyGoto1.selectedItemPosition)?.targetIndex ?: 0 } catch (_: Throwable) { 0 }
        } else {
          0
        }
      // solo_item index는 음수일 수 있으므로, 0만 "단독 종료"로 취급한다.
      val gotoEnabled = (gotoT1 != 0)
      val preClickVis = if (detailVis == View.VISIBLE && gotoEnabled) View.VISIBLE else View.GONE
      soloPreClickContainer.visibility = preClickVis
      tvSoloPreClickLabel.visibility = preClickVis
      cbSoloPreClickUse.visibility = preClickVis
      if (!gotoEnabled) {
        // goto가 꺼진 상태에서 preClick이 숨어있는데 켜져있으면 오동작하므로 항상 OFF로 되돌림
        try { cbSoloPreClickUse.isChecked = false } catch (_: Throwable) {}
      }
      soloPreClickPickRow.visibility = if (preClickVis == View.VISIBLE && cbSoloPreClickUse.isChecked) View.VISIBLE else View.GONE
      try { tvSoloPreClickCoord.visibility = if (soloPreClickPickRow.visibility == View.VISIBLE) View.VISIBLE else View.GONE } catch (_: Throwable) {}
      soloVerifyFileRow.visibility = detailVis
      tvSoloVerifyModeLabel.visibility = detailVis
      soloVerifyModeRow.visibility = detailVis
      // goto 콤보(요청): 모드별 재개(goto) 대상 선택
      soloVerifyGotoRow1.visibility = detailVis
      tvSoloVerifyAccuracyLabel.visibility = detailVis
      spSoloVerifyAccuracy.visibility = detailVis
    }

    // goto 변경 시 "실행전 클릭" 표시/숨김 반영
    try {
      val lis =
        object : android.widget.AdapterView.OnItemSelectedListener {
          override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            try {
              // (요청) goto를 0으로 바꾸는 순간, 실행전 클릭 좌표값도 즉시 제거(메모리/변수 정리)
              val t1 = try { soloGotoOpts.getOrNull(spSoloVerifyGoto1.selectedItemPosition)?.targetIndex ?: 0 } catch (_: Throwable) { 0 }
              // solo_item index는 음수일 수 있으므로, 0만 "단독 종료"로 취급한다.
              val gotoEnabledNow = (t1 != 0)
              if (!gotoEnabledNow) {
                soloPreClickXCur = -1
                soloPreClickYCur = -1
                try { cbSoloPreClickUse.isChecked = false } catch (_: Throwable) {}
              }
              refreshSoloPreClickCoordLabel()
              val k = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
              updateSoloVerifyVisibility(k)
            } catch (_: Throwable) {}
          }
          override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
      spSoloVerifyGoto1.onItemSelectedListener = lis
    } catch (_: Throwable) {}

    fun selIndexForTarget(target: Int): Int {
      val p = soloGotoOpts.indexOfFirst { it.targetIndex == target }
      return if (p >= 0) p else 0
    }
    try { spSoloVerifyGoto1.setSelection(selIndexForTarget(soloVerifyGotoOnStopMissing0), false) } catch (_: Throwable) {}

    // HEX 변경 시 스와치 업데이트
    etColorModuleHex.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          val rgb = parseHexToRgb(s?.toString() ?: "") ?: return
          try {
            vColorModuleSwatch.setBackgroundColor(Color.rgb(rgb.first, rgb.second, rgb.third))
          } catch (_: Throwable) {}
        }
      }
    )

    // 색가져오기: 서비스 오버레이(작은 이동창)로 색상/좌표 선택 후 결과를 받아 UI에 반영
    btnColorModulePick.setOnClickListener {
      // (중요) 마커 탭 직후 설정창이 뜰 때, 직전 UP 이벤트가 버튼 클릭으로 들어오는 경우가 있어
      // 열린 직후 짧은 시간은 클릭을 무시한다(자동 1회 닫힘/픽커 자동오픈 방지).
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      try {
        ScreenCaptureService.openColorModulePicker(index)
      } catch (_: Throwable) {}
      // (요청) 색상가져오기 동안 설정창은 visible OFF(숨김) 처리(종료하지 않음)
      try {
        hiddenForPicker = true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        findViewById<View>(R.id.root)?.visibility = View.INVISIBLE
        window.decorView.alpha = 0f
      } catch (_: Throwable) {}
    }

    // 이미지 가져오기: 이동 가능한 사각형으로 잘라 저장 후 파일명/좌표를 받는다.
    btnImageModulePick.setOnClickListener {
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      try {
        val file = etImageModuleFile.text?.toString().orEmpty().trim()
        val sx = etImageModuleCheckX.text?.toString()?.toIntOrNull() ?: -1
        val sy = etImageModuleCheckY.text?.toString()?.toIntOrNull() ?: -1
        val ex = etImageModuleEndX.text?.toString()?.toIntOrNull() ?: -1
        val ey = etImageModuleEndY.text?.toString()?.toIntOrNull() ?: -1
        val preset =
          ScreenCaptureService.ImagePickerPreset(
            file = file.ifBlank { null },
            cropW = pickedImageW.coerceIn(8, 1024),
            cropH = pickedImageH.coerceIn(8, 1024),
            cropLeftU = pickedCropLeft,
            cropTopU = pickedCropTop,
            startXU = sx,
            startYU = sy,
            endXU = ex,
            endYU = ey,
          )
        ScreenCaptureService.openImageModulePickerWithPreset(index, preset)
      } catch (_: Throwable) {}
      // 설정창은 visible OFF(숨김) 처리(종료하지 않음)
      try {
        hiddenForPicker = true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        findViewById<View>(R.id.root)?.visibility = View.INVISIBLE
        window.decorView.alpha = 0f
      } catch (_: Throwable) {}
    }

    // solo verify 이미지 가져오기
    btnSoloVerifyPick.setOnClickListener {
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      try {
        val preset =
          ScreenCaptureService.ImagePickerPreset(
            file = soloVerifyFileCur.ifBlank { null },
            cropW = pickedSoloVerifyW.coerceIn(8, 1024),
            cropH = pickedSoloVerifyH.coerceIn(8, 1024),
            cropLeftU = pickedSoloVerifyCropLeft,
            cropTopU = pickedSoloVerifyCropTop,
            startXU = soloVerifyStartXCur,
            startYU = soloVerifyStartYCur,
            endXU = soloVerifyEndXCur,
            endYU = soloVerifyEndYCur,
          )
        ScreenCaptureService.openSoloVerifyPickerWithPreset(index, preset)
      } catch (_: Throwable) {}
      try {
        hiddenForPicker = true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        findViewById<View>(R.id.root)?.visibility = View.INVISIBLE
        window.decorView.alpha = 0f
      } catch (_: Throwable) {}
    }

    // solo verify 실행전 클릭(1회) 좌표 선택
    btnSoloPreClickPick.setOnClickListener {
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      try {
        // (-1,-1)만 "미선택"으로 취급. 그 외(음수 포함)는 그대로 시작 좌표로 사용.
        val hasCur = !(soloPreClickXCur == -1 && soloPreClickYCur == -1)
        val px = if (hasCur) soloPreClickXCur else cx.coerceAtLeast(0)
        val py = if (hasCur) soloPreClickYCur else cy.coerceAtLeast(0)
        ScreenCaptureService.openSoloVerifyPreClickPicker(index, px, py)
      } catch (_: Throwable) {}
      try {
        hiddenForPicker = true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        findViewById<View>(R.id.root)?.visibility = View.INVISIBLE
        window.decorView.alpha = 0f
      } catch (_: Throwable) {}
    }

    // (요청) 서비스에서 설정창을 다시 띄울 때 intent로 전달된 pick 결과를 즉시 반영
    try {
      val hexFromIntent = intent?.getStringExtra(EXTRA_PICK_HEX)
      val xUFromIntent = intent?.getIntExtra(EXTRA_PICK_X_U, -1) ?: -1
      val yUFromIntent = intent?.getIntExtra(EXTRA_PICK_Y_U, -1) ?: -1
      if (!hexFromIntent.isNullOrBlank()) {
        etColorModuleHex.setText(hexFromIntent)
        try { vColorModuleSwatch.setBackgroundColor(Color.parseColor(hexFromIntent)) } catch (_: Throwable) {}
      }
      if (xUFromIntent >= 0) etColorModuleCheckX.setText(xUFromIntent.toString())
      if (yUFromIntent >= 0) etColorModuleCheckY.setText(yUFromIntent.toString())
    } catch (_: Throwable) {}

    // (요청) 이미지모듈 pick 결과 즉시 반영
    try {
      val purpose = intent?.getStringExtra(EXTRA_PICK_PURPOSE) ?: PICK_PURPOSE_IMAGE_MODULE
      val fileFromIntent = intent?.getStringExtra(EXTRA_PICK_FILE)
      try { logSvc("intentPick idx=$index purpose=$purpose file=$fileFromIntent") } catch (_: Throwable) {}
      val xUFromIntent = intent?.getIntExtra(EXTRA_PICK_X_U, -1) ?: -1
      val yUFromIntent = intent?.getIntExtra(EXTRA_PICK_Y_U, -1) ?: -1
      val x2UFromIntent = intent?.getIntExtra(EXTRA_PICK_X2_U, -1) ?: -1
      val y2UFromIntent = intent?.getIntExtra(EXTRA_PICK_Y2_U, -1) ?: -1
      val cropLeftFromIntent = intent?.getIntExtra(EXTRA_PICK_CROP_LEFT_U, -1) ?: -1
      val cropTopFromIntent = intent?.getIntExtra(EXTRA_PICK_CROP_TOP_U, -1) ?: -1
      val wFromIntent = intent?.getIntExtra(EXTRA_PICK_W, -1) ?: -1
      val hFromIntent = intent?.getIntExtra(EXTRA_PICK_H, -1) ?: -1
      if (purpose == PICK_PURPOSE_SOLO_VERIFY) {
        if (!fileFromIntent.isNullOrBlank()) {
          soloVerifyFileCur = fileFromIntent.trim()
          refreshSoloVerifyPreview(soloVerifyFileCur)
          refreshSoloVerifyPickButtonLabel()
          // (중요) 저장 전(기본 해제 상태)이라도 픽커에서 파일이 돌아오면 사용자가 원한 것이므로 자동으로 켠다.
          try { cbSoloVerifyUse.isChecked = true } catch (_: Throwable) {}
          forceShowSoloVerifyUi()
          refreshSoloVerifyPreviewSoon()
        }
        if (xUFromIntent >= 0) soloVerifyStartXCur = xUFromIntent
        if (yUFromIntent >= 0) soloVerifyStartYCur = yUFromIntent
        if (x2UFromIntent >= 0) soloVerifyEndXCur = x2UFromIntent
        if (y2UFromIntent >= 0) soloVerifyEndYCur = y2UFromIntent
        if (wFromIntent > 0) pickedSoloVerifyW = wFromIntent.coerceIn(8, 1024)
        if (hFromIntent > 0) pickedSoloVerifyH = hFromIntent.coerceIn(8, 1024)
        if (cropLeftFromIntent >= 0) pickedSoloVerifyCropLeft = cropLeftFromIntent
        if (cropTopFromIntent >= 0) pickedSoloVerifyCropTop = cropTopFromIntent
      } else {
        if (!fileFromIntent.isNullOrBlank()) {
          etImageModuleFile.setText(fileFromIntent)
          refreshImagePreview(fileFromIntent)
          refreshImagePickButtonLabel()
          refreshImagePreviewSoon()
        }
        if (xUFromIntent >= 0) etImageModuleCheckX.setText(xUFromIntent.toString())
        if (yUFromIntent >= 0) etImageModuleCheckY.setText(yUFromIntent.toString())
        if (x2UFromIntent >= 0) etImageModuleEndX.setText(x2UFromIntent.toString())
        if (y2UFromIntent >= 0) etImageModuleEndY.setText(y2UFromIntent.toString())
        if (wFromIntent > 0) pickedImageW = wFromIntent.coerceIn(8, 1024)
        if (hFromIntent > 0) pickedImageH = hFromIntent.coerceIn(8, 1024)
        if (cropLeftFromIntent >= 0) pickedCropLeft = cropLeftFromIntent
        if (cropTopFromIntent >= 0) pickedCropTop = cropTopFromIntent
      }
    } catch (_: Throwable) {}

    // (추가) 실행전 클릭(1회) 좌표 pick 결과 즉시 반영
    try {
      val px = intent?.getIntExtra(EXTRA_PICK_X_U, -1) ?: -1
      val py = intent?.getIntExtra(EXTRA_PICK_Y_U, -1) ?: -1
      val act = intent?.action ?: ""
      if (act == ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT) {
        try { logSvc("intentPreClickPick idx=$index xU=$px yU=$py") } catch (_: Throwable) {}
        // (-1,-1)은 취소로 취급(값 변경 없음). 그 외(음수 포함)는 저장/표시한다.
        if (!(px == -1 && py == -1)) {
          soloPreClickXCur = px
          soloPreClickYCur = py
          try { cbSoloPreClickUse.isChecked = true } catch (_: Throwable) {}
          refreshSoloPreClickCoordLabel()
          try {
            val k = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
            updateSoloVerifyVisibility(k)
          } catch (_: Throwable) {}
        }
      }
    } catch (_: Throwable) {}

    // 결과 수신(선택된 값/좌표를 설정창에 반영)
    if (pickReceiver == null) {
      pickReceiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            val it = intent ?: return
            if (it.action != ACTION_COLOR_MODULE_PICK_RESULT && it.action != ACTION_IMAGE_MODULE_PICK_RESULT && it.action != ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT) return
            val target = it.getIntExtra(EXTRA_PICK_TARGET_INDEX, 0)
            if (target != index) return
            val xU = it.getIntExtra(EXTRA_PICK_X_U, -1)
            val yU = it.getIntExtra(EXTRA_PICK_Y_U, -1)
            try { logSvc("recv action=${it.action} idx=$index xU=$xU yU=$yU purpose=${it.getStringExtra(EXTRA_PICK_PURPOSE)} file=${it.getStringExtra(EXTRA_PICK_FILE)}") } catch (_: Throwable) {}
            if (it.action == ACTION_COLOR_MODULE_PICK_RESULT) {
              val hex = it.getStringExtra(EXTRA_PICK_HEX)
              if (!hex.isNullOrBlank()) {
                try {
                  etColorModuleHex.setText(hex)
                  vColorModuleSwatch.setBackgroundColor(Color.parseColor(hex))
                } catch (_: Throwable) {}
              }
              if (xU >= 0) etColorModuleCheckX.setText(xU.toString())
              if (yU >= 0) etColorModuleCheckY.setText(yU.toString())
            } else if (it.action == ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT) {
              // (-1,-1)은 취소로 취급(값 변경 없음). 그 외(음수 포함)는 저장/표시한다.
              if (!(xU == -1 && yU == -1)) {
                soloPreClickXCur = xU
                soloPreClickYCur = yU
                try { cbSoloPreClickUse.isChecked = true } catch (_: Throwable) {}
                refreshSoloPreClickCoordLabel()
                try {
                  val k = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
                  updateSoloVerifyVisibility(k)
                } catch (_: Throwable) {}
              }
            } else if (it.action == ACTION_IMAGE_MODULE_PICK_RESULT) {
              val purpose = it.getStringExtra(EXTRA_PICK_PURPOSE) ?: PICK_PURPOSE_IMAGE_MODULE
              val file = it.getStringExtra(EXTRA_PICK_FILE)
              val w = it.getIntExtra(EXTRA_PICK_W, -1)
              val h = it.getIntExtra(EXTRA_PICK_H, -1)
              val x2U = it.getIntExtra(EXTRA_PICK_X2_U, -1)
              val y2U = it.getIntExtra(EXTRA_PICK_Y2_U, -1)
              val cropLeft = it.getIntExtra(EXTRA_PICK_CROP_LEFT_U, -1)
              val cropTop = it.getIntExtra(EXTRA_PICK_CROP_TOP_U, -1)
              if (purpose == PICK_PURPOSE_SOLO_VERIFY) {
                if (!file.isNullOrBlank()) {
                  soloVerifyFileCur = file.trim()
                  try { Log.i("ScreenCaptureService", "SoloVerifyPickResult idx=$index file=$soloVerifyFileCur") } catch (_: Throwable) {}
                  refreshSoloVerifyPreview(soloVerifyFileCur)
                  refreshSoloVerifyPickButtonLabel()
                  try { cbSoloVerifyUse.isChecked = true } catch (_: Throwable) {}
                  forceShowSoloVerifyUi()
                  refreshSoloVerifyPreviewSoon()
                }
                if (xU >= 0) soloVerifyStartXCur = xU
                if (yU >= 0) soloVerifyStartYCur = yU
                if (x2U >= 0) soloVerifyEndXCur = x2U
                if (y2U >= 0) soloVerifyEndYCur = y2U
                if (w > 0) pickedSoloVerifyW = w.coerceIn(8, 1024)
                if (h > 0) pickedSoloVerifyH = h.coerceIn(8, 1024)
                if (cropLeft >= 0) pickedSoloVerifyCropLeft = cropLeft
                if (cropTop >= 0) pickedSoloVerifyCropTop = cropTop
              } else {
                if (!file.isNullOrBlank()) etImageModuleFile.setText(file)
                if (!file.isNullOrBlank()) refreshImagePreview(file)
                if (!file.isNullOrBlank()) refreshImagePickButtonLabel()
                if (!file.isNullOrBlank()) refreshImagePreviewSoon()
                if (xU >= 0) etImageModuleCheckX.setText(xU.toString())
                if (yU >= 0) etImageModuleCheckY.setText(yU.toString())
                if (x2U >= 0) etImageModuleEndX.setText(x2U.toString())
                if (y2U >= 0) etImageModuleEndY.setText(y2U.toString())
                if (w > 0) pickedImageW = w.coerceIn(8, 1024)
                if (h > 0) pickedImageH = h.coerceIn(8, 1024)
                if (cropLeft >= 0) pickedCropLeft = cropLeft
                if (cropTop >= 0) pickedCropTop = cropTop
              }
            }

            // (요청) 보내기/닫기 후 설정창 visible ON 복구
            try {
              if (hiddenForPicker) {
                hiddenForPicker = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                findViewById<View>(R.id.root)?.visibility = View.VISIBLE
                window.decorView.alpha = 1f
                try { logSvc("restoreVisible idx=$index ok") } catch (_: Throwable) {}
              }
            } catch (_: Throwable) {}
          }
        }
    }
    try {
      val f = IntentFilter().apply {
        addAction(ACTION_COLOR_MODULE_PICK_RESULT)
        addAction(ACTION_IMAGE_MODULE_PICK_RESULT)
        addAction(ACTION_SOLO_VERIFY_PRECLICK_PICK_RESULT)
      }
      if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(pickReceiver, f, Context.RECEIVER_NOT_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        registerReceiver(pickReceiver, f)
      }
    } catch (_: Throwable) {}

    // module 패턴/모드(ATX2 동등)
    val modulePatterns =
      listOf(
        0 to I18n.modulePatternName(lang, 0),
        1 to I18n.modulePatternName(lang, 1),
        2 to I18n.modulePatternName(lang, 2),
        3 to I18n.modulePatternName(lang, 3),
        4 to I18n.modulePatternName(lang, 4),
        5 to I18n.modulePatternName(lang, 5),
        6 to I18n.modulePatternName(lang, 6),
        7 to I18n.modulePatternName(lang, 7),
        8 to I18n.modulePatternName(lang, 8),
        9 to I18n.modulePatternName(lang, 9),
        10 to I18n.modulePatternName(lang, 10),
      )
    spModulePattern.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        modulePatterns.map { it.second }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }

    val moduleDirModes =
      listOf(
        0 to I18n.moduleDirModeName(lang, 0),
        1 to I18n.moduleDirModeName(lang, 1),
      )
    // module 실행 방식(순번/단독) - 체크박스 대신 콤보박스(스피너)
    val moduleExecModes =
      listOf(
        0 to I18n.moduleExecModeName(lang, 0),
        1 to I18n.moduleExecModeName(lang, 1),
      )
    spModuleExecMode.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        moduleExecModes.map { it.second }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }

    spModuleDirMode.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        moduleDirModes.map { it.second }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }

    // 표시용 초기값 마이그레이션:
    // - modulePatternV2==0 && modulePattern 미존재면, legacy moduleDir을 v2 단일 패턴(6~9)으로 복원
    var modulePatternUi = modulePatternRaw0
    var modulePatternV2Ui = modulePatternV20
    if (kind0 == "module") {
      if (modulePatternV20 == 0 && !modulePatternPresent) {
        modulePatternV2Ui = 2
        modulePatternUi =
          when (moduleDir0.trim().uppercase()) {
            "U" -> 6
            "D" -> 7
            "L" -> 8
            else -> 9
          }
      } else if (modulePatternV20 == 0 && modulePatternRaw0 == 6) {
        // ATX2 v1 -> v2 랜덤 변환(6 -> 10)
        modulePatternUi = 10
      }
      modulePatternUi = modulePatternUi.coerceIn(0, 10)
    }

    val patIdx = modulePatterns.indexOfFirst { it.first == modulePatternUi }.let { if (it >= 0) it else 0 }
    spModulePattern.setSelection(patIdx, false)
    val modeIdx = moduleDirModes.indexOfFirst { it.first == moduleDirMode0 }.let { if (it >= 0) it else 0 }
    spModuleDirMode.setSelection(modeIdx, false)

    // module 길이/hold 기본값 표시
    // (요청) 방향모듈 스와이프 길이 기본값: 100(px)
    val defaultLenPx = 100
    etModuleLenPx.setText((moduleLenPx0.takeIf { it > 0 } ?: defaultLenPx).toString())
    etModuleMoveUpMs.setText(moduleMoveUpMs0.toString())
    spModuleExecMode.setSelection(if (moduleSoloExec0) 1 else 0, false)

    val swipeModes =
      listOf(
        0 to I18n.swipeModeName(lang, 0),
        1 to I18n.swipeModeName(lang, 1),
      )
    val swipeAdapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        swipeModes.map { it.second }
      ).apply {
        setDropDownViewResource(R.layout.spinner_marker_kind_dropdown)
      }
    spSwipeMode.adapter = swipeAdapter
    val swipeModeIndex = swipeModes.indexOfFirst { it.first == swipeMode0 }.let { if (it >= 0) it else 0 }
    spSwipeMode.setSelection(swipeModeIndex, false)

    // solo 전용(단위: ms/sec/min)
    val units =
      listOf(
        1 to I18n.unitName(lang, 1),
        1000 to I18n.unitName(lang, 1000),
        60000 to I18n.unitName(lang, 60000),
      )
    spSoloStartDelayUnit.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        units.map { it.second }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
    etSoloStartDelay.inputType = InputType.TYPE_CLASS_NUMBER
    // 표시 단위 자동 선택(기본: 12분)
    val (soloDisp, soloUnitIdx) =
      when {
        soloStartDelayMs0 >= 60000 && soloStartDelayMs0 % 60000 == 0 -> Pair((soloStartDelayMs0 / 60000).toString(), 2)
        soloStartDelayMs0 >= 1000 && soloStartDelayMs0 % 1000 == 0 -> Pair((soloStartDelayMs0 / 1000).toString(), 1)
        else -> Pair(soloStartDelayMs0.toString(), 0)
      }
    etSoloStartDelay.setText(soloDisp)
    spSoloStartDelayUnit.setSelection(soloUnitIdx, false)

    val comboCounts = (1..10).toList()
    spSoloComboCount.adapter =
      ArrayAdapter(
        this,
        R.layout.spinner_marker_kind_selected,
        comboCounts.map { it.toString() }
      ).apply { setDropDownViewResource(R.layout.spinner_marker_kind_dropdown) }
    spSoloComboCount.setSelection((soloComboCount0 - 1).coerceIn(0, 9), false)

    fun updateModuleVisibility(kind: String) {
      val show = kind == "module"
      val vis = if (show) View.VISIBLE else View.GONE

      // (ATX2) module은 패턴 기반이므로 moduleDir(레거시)은 기본 숨김
      tvModuleDirLabel.visibility = View.GONE
      spModuleDir.visibility = View.GONE

      tvModulePatternLabel.visibility = vis
      spModulePattern.visibility = vis
      tvModuleExecModeLabel.visibility = vis
      spModuleExecMode.visibility = vis
      tvModuleLenLabel.visibility = vis
      etModuleLenPx.visibility = vis
      tvModuleDirModeLabel.visibility = vis
      spModuleDirMode.visibility = vis
      tvModuleMoveUpLabel.visibility = vis
      etModuleMoveUpMs.visibility = vis
    }

    fun updateColorModuleVisibility(kind: String) {
      val show = kind == "color_module" && captureReady
      val vis = if (show) View.VISIBLE else View.GONE
      tvColorModuleLabel.visibility = vis
      colorModuleColorRow.visibility = vis
      tvColorModuleCoordLabel.visibility = vis
      colorModuleCoordRow.visibility = vis
      tvColorModuleAccuracyLabel.visibility = vis
      spColorModuleAccuracy.visibility = vis
      if (kind == "color_module" && !captureReady) {
        // 캡처 미허용이면 UI는 숨기되 안내는 남긴다.
        tvColorModuleLabel.visibility = View.VISIBLE
        tvColorModuleLabel.text = I18n.colorModuleNeedsShare(lang)
        colorModuleColorRow.visibility = View.GONE
        tvColorModuleCoordLabel.visibility = View.GONE
        colorModuleCoordRow.visibility = View.GONE
        tvColorModuleAccuracyLabel.visibility = View.GONE
        spColorModuleAccuracy.visibility = View.GONE
      }
    }

    fun updateImageModuleVisibility(kind: String) {
      val show = kind == "image_module" && captureReady
      val vis = if (show) View.VISIBLE else View.GONE
      tvImageModuleLabel.visibility = vis
      imageModuleFileRow.visibility = vis
      // (요청) 마커설정창에서는 파일이름/시작체크좌표/종료체크좌표/검색된중앙좌표 항목은 숨김 처리(기능은 유지)
      tvImageModuleCoordLabel.visibility = View.GONE
      imageModuleCoordRow.visibility = View.GONE
      tvImageModuleEndCoordLabel.visibility = View.GONE
      imageModuleEndCoordRow.visibility = View.GONE
      tvImageModuleFoundCenter.visibility = View.GONE
      tvImageModuleClickModeLabel.visibility = vis
      rgImageModuleClickMode.visibility = vis
      tvImageModuleLastMatch.visibility = vis
      tvImageModuleAccuracyLabel.visibility = vis
      spImageModuleAccuracy.visibility = vis
      if (kind == "image_module" && !captureReady) {
        tvImageModuleLabel.visibility = View.VISIBLE
        tvImageModuleLabel.text = "${I18n.markerKindName(lang, "image_module")} (${I18n.screenShareRequired(lang)})"
        imageModuleFileRow.visibility = View.GONE
        tvImageModuleCoordLabel.visibility = View.GONE
        imageModuleCoordRow.visibility = View.GONE
        tvImageModuleEndCoordLabel.visibility = View.GONE
        imageModuleEndCoordRow.visibility = View.GONE
        tvImageModuleFoundCenter.visibility = View.GONE
        tvImageModuleClickModeLabel.visibility = View.GONE
        rgImageModuleClickMode.visibility = View.GONE
        tvImageModuleLastMatch.visibility = View.GONE
        tvImageModuleAccuracyLabel.visibility = View.GONE
        spImageModuleAccuracy.visibility = View.GONE
      }
    }

    fun updateSwipeModeVisibility(kind: String) {
      val show = (kind == "swipe") && !isSwipeSub
      tvSwipeModeLabel.visibility = if (show) View.VISIBLE else View.GONE
      spSwipeMode.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun updateAiDefenseVisibility(kind: String) {
      // 제외: 단독 메인/서브, 스와이프 sub(링), 색상모듈/이미지모듈
      val eligible = !isSwipeSub && !isSoloItem && kind != "solo_main" && kind != "color_module" && kind != "image_module"
      cbAiDefense.visibility = if (eligible) View.VISIBLE else View.GONE
      if (!eligible) {
        // 숨기는 케이스에서는 오동작 방지를 위해 항상 ON으로 되돌려둔다(저장은 null 처리)
        cbAiDefense.isChecked = true
      }
    }

    fun updateDoubleClickVisibility(kind: String) {
      // 제외: 스와이프(메인/서브)
      val eligible = !isSwipeSub && kind != "swipe"
      cbDoubleClick.visibility = if (eligible) View.VISIBLE else View.GONE
      if (!eligible) {
        // 스와이프에서는 저장 자체를 하지 않도록 OFF로 고정
        cbDoubleClick.isChecked = false
      }
    }

    fun updateSoloVisibility(kind: String) {
      val show = (kind == "solo_main") && !isSoloItem && !isSwipeSub
      val vis = if (show) View.VISIBLE else View.GONE
      tvSoloStartDelayLabel.visibility = vis
      soloStartDelayRow.visibility = vis
      tvSoloComboLabel.visibility = vis
      spSoloComboCount.visibility = vis
      btnSoloCreateCombo.visibility = vis
      btnSoloTest.visibility = vis

      // 단독 설정창 구성(요청): 마커종류, 지연, 실행전 지연, 콤보개수, 콤보생성, TEST, 삭제/닫기/저장
      // 나머지(지터/누름/스와이프모드/모듈)는 숨김
      if (show) {
        tvSwipeModeLabel.visibility = View.GONE
        spSwipeMode.visibility = View.GONE
        tvModuleDirLabel.visibility = View.GONE
        spModuleDir.visibility = View.GONE
        tvJitterLabel.visibility = View.GONE
        sbJitter.visibility = View.GONE
        tvJitter.visibility = View.GONE
        tvPressLabel.visibility = View.GONE
        etPress.visibility = View.GONE
      }
    }
    updateModuleVisibility(kind0)
    updateColorModuleVisibility(kind0)
    updateImageModuleVisibility(kind0)
    updateSwipeModeVisibility(kind0)
    updateSoloVisibility(kind0)
    updateSoloVerifyVisibility(kind0)
    // AI탐지방어 초기값/가시성
    cbAiDefense.isChecked = aiDefense0
    updateAiDefenseVisibility(kind0)

    // 더블클릭 초기값/가시성
    cbDoubleClick.isChecked = doubleClick0
    updateDoubleClickVisibility(kind0)

    cbSoloVerifyUse.setOnCheckedChangeListener { _, _ ->
      try {
        val k = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
        updateSoloVerifyVisibility(k)
      } catch (_: Throwable) {}
    }
    cbSoloPreClickUse.setOnCheckedChangeListener { _, _ ->
      try {
        val k = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
        updateSoloVerifyVisibility(k)
      } catch (_: Throwable) {}
    }
    spKind.onItemSelectedListener =
      object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: View?,
          position: Int,
          id: Long
        ) {
          val k = kinds.getOrNull(position)?.first ?: kind0
          updateModuleVisibility(k)
          updateColorModuleVisibility(k)
          updateImageModuleVisibility(k)
          updateSwipeModeVisibility(k)
          updateSoloVisibility(k)
          updateSoloVerifyVisibility(k)
          updateAiDefenseVisibility(k)
          updateDoubleClickVisibility(k)

          // (요청) 방향모듈로 변경 시 기본값 자동 세팅:
          // - 지연시간: 1000ms
          // - Move up: 700ms
          // 기존에 사용자가 입력한 값이 있으면 존중한다.
          try {
            if (k == "module") {
              val curDelay = etDelay.text?.toString()?.trim().orEmpty()
              if (curDelay.isBlank() || (kind0 != "module" && curDelay == "300")) {
                etDelay.setText("1000")
              }
              val curLen = etModuleLenPx.text?.toString()?.trim().orEmpty()
              if (curLen.isBlank() || (kind0 != "module" && (curLen == "0" || curLen == "30"))) {
                etModuleLenPx.setText("100")
              }
              val curMoveUp = etModuleMoveUpMs.text?.toString()?.trim().orEmpty()
              if (curMoveUp.isBlank() || (kind0 != "module" && curMoveUp == "0")) {
                etModuleMoveUpMs.setText("700")
              }
            }

            // (요청) 이미지모듈로 변경 시 지연시간 기본값: 1500ms
            if (k == "image_module") {
              val curDelay = etDelay.text?.toString()?.trim().orEmpty()
              if (curDelay.isBlank() || (kind0 != "image_module" && curDelay == "300")) {
                etDelay.setText("1500")
              }
            }
          } catch (_: Throwable) {}
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }

    etDelay.setText(delay0.toString())
    sbJitter.progress = jitter0
    tvJitter.text = "${jitter0}%"
    etPress.setText(press0.toString())
    etMoveUp.setText(moveUp0.toString())

    // (요청) 모든 마커설정창에서 "랜덤지연/누름" UI는 숨김 처리(전역 화면설정에서만 제어)
    try {
      tvJitterLabel.visibility = View.GONE
      sbJitter.visibility = View.GONE
      tvJitter.visibility = View.GONE
      tvPressLabel.visibility = View.GONE
      etPress.visibility = View.GONE
    } catch (_: Throwable) {}

    sbJitter.setOnSeekBarChangeListener(
      object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          tvJitter.text = "${progress.coerceIn(0, 100)}%"
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
      }
    )

    fun applySwipeSubUi() {
      if (!isSwipeSub) return
      tvTitle.text = "스와이프 SUB 설정 (#$index)"

      // 구성: 정렬 버튼, 지연시간, move up, 닫기/저장
      tvCoord.visibility = View.GONE
      tvKindLabel.visibility = View.GONE
      spKind.visibility = View.GONE
      tvSwipeModeLabel.visibility = View.GONE
      spSwipeMode.visibility = View.GONE
      tvModuleDirLabel.visibility = View.GONE
      spModuleDir.visibility = View.GONE
      tvJitterLabel.visibility = View.GONE
      sbJitter.visibility = View.GONE
      tvJitter.visibility = View.GONE
      tvPressLabel.visibility = View.GONE
      etPress.visibility = View.GONE
      btnDelete.visibility = View.GONE

      swipeSubAlignRow.visibility = View.VISIBLE
      tvDelayLabel.visibility = View.VISIBLE
      etDelay.visibility = View.VISIBLE
      tvMoveUpLabel.visibility = View.VISIBLE
      etMoveUp.visibility = View.VISIBLE
    }

    // swipe_to(서브) UI 적용
    applySwipeSubUi()

    fun applySoloSubUi() {
      if (!isSoloItem) return
      val label = (m?.optString("soloLabel", "") ?: "").ifBlank { "SUB" }
      tvTitle.text = "단독 SUB $label 설정 (#$index)"

      // 구성: 지연시간, 랜덤시간, 누름, 삭제/닫기/저장
      tvCoord.visibility = View.GONE

      tvKindLabel.visibility = View.GONE
      spKind.visibility = View.GONE

      // solo_main 전용 숨김
      tvSoloStartDelayLabel.visibility = View.GONE
      soloStartDelayRow.visibility = View.GONE
      tvSoloComboLabel.visibility = View.GONE
      spSoloComboCount.visibility = View.GONE
      btnSoloCreateCombo.visibility = View.GONE
      btnSoloTest.visibility = View.GONE

      // swipe 전용 숨김
      tvSwipeModeLabel.visibility = View.GONE
      spSwipeMode.visibility = View.GONE
      swipeSubAlignRow.visibility = View.GONE
      tvMoveUpLabel.visibility = View.GONE
      etMoveUp.visibility = View.GONE

      // module 전용 숨김
      tvModuleDirLabel.visibility = View.GONE
      spModuleDir.visibility = View.GONE

      // 필요한 항목 표시
      tvDelayLabel.visibility = View.VISIBLE
      etDelay.visibility = View.VISIBLE
      // (요청) 랜덤지연/누름 UI는 항상 숨김
      tvJitterLabel.visibility = View.GONE
      sbJitter.visibility = View.GONE
      tvJitter.visibility = View.GONE
      tvPressLabel.visibility = View.GONE
      etPress.visibility = View.GONE

      btnDelete.visibility = View.VISIBLE
    }

    // solo_item(단독 SUB) UI 적용
    applySoloSubUi()

    btnClose.setOnClickListener { finishWithToolbarRestore() }

    fun alignSwipeChain(axis: String) {
      // axis: "H" -> y정렬, "V" -> x정렬
      val raw = prefs().getString("flutter.markers", "[]") ?: "[]"
      val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
      val map = HashMap<Int, JSONObject>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        map[o.optInt("index")] = o
      }
      val sub = map[index] ?: return
      val parentIdx = sub.optInt("parentIndex", 0)
      val parent = map[parentIdx] ?: return

      val baseX = parent.optInt("xPx", 0)
      val baseY = parent.optInt("yPx", 0)

      // 체인(메인->swipe_to->swipe_to...) 전체 정렬
      var cur = parent.optInt("toIndex", 0)
      var guard = 0
      while (cur != 0 && guard++ < 24) {
        val node = map[cur] ?: break
        if (node.optString("kind") != "swipe_to") break
        if (axis == "H") node.put("yPx", baseY) else node.put("xPx", baseX)
        cur = node.optInt("toIndex", 0)
      }

      commitMarkersJson(arr.toString())
      ScreenCaptureService.refreshMarkersFromPrefs()
    }

    btnAlignH.setOnClickListener { alignSwipeChain("H") }
    btnAlignV.setOnClickListener { alignSwipeChain("V") }

    btnSoloCreateCombo.setOnClickListener {
      val raw = prefs().getString("flutter.markers", "[]") ?: "[]"
      val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
      val used = HashSet<Int>()
      var mainObj: JSONObject? = null
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val idx = o.optInt("index")
        used.add(idx)
        if (idx == index) mainObj = o
      }
      val main = mainObj ?: return@setOnClickListener
      // 저장 전에 스피너에서 solo_main으로 바꾼 직후에도 콤보생성이 되도록,
      // 현재 선택 상태가 solo_main이면 JSON도 solo_main으로 간주한다.
      val selKind = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
      if (selKind != "solo_main") return@setOnClickListener
      if (main.optString("kind") != "solo_main") {
        main.put("kind", "solo_main")
      }

      // solo_main은 soloLabel이 있어야 단독 스케줄러에서 인식된다.
      // 값이 비어있으면 A~Z 중 사용되지 않은 글자를 자동 부여.
      fun usedSoloLetters(): HashSet<Char> {
        val set = HashSet<Char>()
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val k = o.optString("kind", "")
          if (k != "solo_main" && k != "solo") continue
          val idx = o.optInt("index")
          // soloLabel이 비어도 고정 index(20001~20026)면 글자를 복원해서 중복 배정을 막는다.
          val c =
            when {
              idx in 20001..20026 -> ('A'.code + (idx - 20001)).toChar()
              else -> {
                val lab = o.optString("soloLabel", "").trim().uppercase()
                lab.firstOrNull { it in 'A'..'Z' } ?: continue
              }
            }
          set.add(c)
        }
        return set
      }
      // 메인이 이미 고정 index(20001~20026)이면 그 index 기준으로 letter를 확정한다.
      // (저장 전/이전 버전 데이터에서 soloLabel 누락 시에도 A/B/C 건너뛰기 방지)
      val mainIdx = main.optInt("index")
      val picked =
        if (mainIdx in 20001..20026) {
          ('A'.code + (mainIdx - 20001)).toChar()
        } else {
          val curLab = main.optString("soloLabel", "").trim().uppercase()
          val curC = curLab.firstOrNull { it in 'A'..'Z' }
          curC ?: run {
            val usedL = usedSoloLetters()
            ('A'..'Z').firstOrNull { !usedL.contains(it) } ?: 'A'
          }
        }
      val letter = picked.toString()
      main.put("soloLabel", letter)

      val wantCount = (comboCounts.getOrNull(spSoloComboCount.selectedItemPosition) ?: soloComboCount0).coerceIn(1, 10)

      val existing = HashSet<String>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("kind") == "solo_item" && o.optInt("parentIndex") == index) {
          existing.add(o.optString("soloLabel", "").uppercase())
        }
      }

      fun takeNeg(): Int {
        var n = -1
        while (n == 0 || used.contains(n)) n--
        used.add(n)
        return n
      }

      val baseX = main.optInt("xPx", 0)
      val baseY = main.optInt("yPx", 0)
      // 요청: 콤보클릭 개수만큼만 생성(무조건 10개 생성 X)
      for (n in 1..wantCount) {
        val lab = "$letter$n"
        if (existing.contains(lab)) continue
        val child = JSONObject()
        child.put("index", takeNeg())
        child.put("kind", "solo_item")
        child.put("parentIndex", index)
        child.put("soloLabel", lab)
        child.put("delayMs", 1000) // 단독sub 기본 지연
        child.put("jitterPct", try { prefs().getInt("flutter.random_delay_pct", 50).coerceIn(0, 100) } catch (_: Throwable) { 50 })
        child.put("pressMs", 0)
        child.put("xPx", baseX)
        child.put("yPx", (baseY + 120 * n).coerceAtLeast(0))
        child.put("xPct", main.optDouble("xPct", 0.0))
        child.put("yPct", main.optDouble("yPct", 0.0))
        arr.put(child)
      }
      commitMarkersJson(arr.toString())
      ScreenCaptureService.refreshMarkersFromPrefs()
    }

    btnSoloTest.setOnClickListener {
      // (요청) TEST 버튼 클릭하면 창을 닫고, 3초 카운트다운(대기) 후
      // TEST 버튼을 누른 단독실행 마커를 1회 실행
      val target = index
      finish()
      Handler(Looper.getMainLooper()).postDelayed(
        {
          try {
            ScreenCaptureService.runSoloTest(target)
          } catch (_: Throwable) {}
        },
        3000L
      )
    }

    btnSave.setOnClickListener {
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      val kind = kinds.getOrNull(spKind.selectedItemPosition)?.first ?: kind0
      // (요구사항) 스와이프를 제외한 마커만 더블클릭 옵션 사용
      val doubleClick = if (kind == "swipe") null else try { cbDoubleClick.isChecked } catch (_: Throwable) { false }
      val moduleDir = moduleDirs.getOrNull(spModuleDir.selectedItemPosition)?.first ?: moduleDir0 // legacy
      val swipeMode =
        swipeModes.getOrNull(spSwipeMode.selectedItemPosition)?.first ?: swipeMode0
      val modulePattern = modulePatterns.getOrNull(spModulePattern.selectedItemPosition)?.first ?: modulePatternRaw0
      val moduleExecMode = moduleExecModes.getOrNull(spModuleExecMode.selectedItemPosition)?.first ?: 0
      val moduleSoloExec = (moduleExecMode == 1)
      val moduleDirMode = moduleDirModes.getOrNull(spModuleDirMode.selectedItemPosition)?.first ?: moduleDirMode0
      val moduleLenPx = etModuleLenPx.text?.toString()?.toIntOrNull()?.coerceIn(30, 5000) ?: moduleLenPx0.coerceAtLeast(0)
      val moduleMoveUpMs = etModuleMoveUpMs.text?.toString()?.toIntOrNull()?.coerceIn(0, 600000) ?: moduleMoveUpMs0
      val cmRgb = parseHexToRgb(etColorModuleHex.text?.toString() ?: "") ?: Triple(255, 255, 255)
      val cmCheckX = etColorModuleCheckX.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: (cx.takeIf { it >= 0 } ?: 0)
      val cmCheckY = etColorModuleCheckY.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: (cy.takeIf { it >= 0 } ?: 0)
      val cmAcc = accs.getOrNull(spColorModuleAccuracy.selectedItemPosition) ?: colorAcc0
      val imgAcc = accs.getOrNull(spImageModuleAccuracy.selectedItemPosition) ?: imageAcc0
      val imgFile = etImageModuleFile.text?.toString()?.trim().orEmpty()
      val imgStartX = etImageModuleCheckX.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: (cx.takeIf { it >= 0 } ?: 0)
      val imgStartY = etImageModuleCheckY.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: (cy.takeIf { it >= 0 } ?: 0)
      // (요청) 이미지모듈 검색영역 기본값: 200x200 -> 500x500
      val imgEndX0 = etImageModuleEndX.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: (imgStartX + 500)
      val imgEndY0 = etImageModuleEndY.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: (imgStartY + 500)
      val imgEndX = maxOf(imgStartX, imgEndX0)
      val imgEndY = maxOf(imgStartY, imgEndY0)
      val imgClickMode =
        try {
          when {
            rbImageClickFoundCenter.isChecked -> 1
            rbImageClickSound.isChecked -> 2
            rbImageClickVibrate.isChecked -> 3
            else -> 0
          }
        } catch (_: Throwable) {
          0
        }
      val delay = etDelay.text?.toString()?.toIntOrNull()?.coerceIn(0, 600000) ?: delay0
      // (요청) 랜덤지연은 전역 화면설정 값을 사용(기본 50%).
      val jitter = try { prefs().getInt("flutter.random_delay_pct", 50).coerceIn(0, 100) } catch (_: Throwable) { 50 }
      // (요청) 누름(ms)은 전역 화면설정(clickPressMsGlobal) 사용: 개별 마커 pressMs는 0으로 저장
      val press = 0
      val moveUp = etMoveUp.text?.toString()?.toIntOrNull()?.coerceIn(0, 600000) ?: moveUp0
      val unitMul = units.getOrNull(spSoloStartDelayUnit.selectedItemPosition)?.first ?: 1
      val soloStartDelayVal = etSoloStartDelay.text?.toString()?.toIntOrNull()?.coerceIn(0, 3600000) ?: soloStartDelayMs0
      val soloStartDelayMs = (soloStartDelayVal.toLong() * unitMul.toLong()).coerceIn(0L, 3600000L).toInt()
      val soloComboCount = comboCounts.getOrNull(spSoloComboCount.selectedItemPosition) ?: soloComboCount0
      val aiDefenseEligible = !isSwipeSub && !isSoloItem && kind != "solo_main" && kind != "color_module" && kind != "image_module"
      val randomClickUse = if (aiDefenseEligible) cbAiDefense.isChecked else null
      val finalKind =
        when {
          isSwipeSub && kind == "swipe" -> "swipe_to"
          isSoloItem && kind == "solo_main" -> "solo_item"
          else -> kind
        }
      val soloVerifyEligible = captureReady && !isSwipeSub && (finalKind == "solo_main" || finalKind == "solo_item")
      val soloVerifyUse = if (soloVerifyEligible) cbSoloVerifyUse.isChecked else false
      // (변경) 실행확인 방식은 1가지 고정(항상 0)
      val soloVerifyMode = 0
      val soloVerifyAcc = accs.getOrNull(spSoloVerifyAccuracy.selectedItemPosition) ?: soloVerifyAcc0
      val soloVerifyGotoOnStopMissing =
        if (soloVerifyEligible && soloVerifyUse) {
          try { soloGotoOpts.getOrNull(spSoloVerifyGoto1.selectedItemPosition)?.targetIndex ?: 0 } catch (_: Throwable) { 0 }
        } else {
          0
        }
      // (변경) found 재개(goto) 삭제됨
      val soloVerifyGotoOnStopFound = 0

      val soloPreClickUse =
        if (soloVerifyEligible && soloVerifyUse && (soloVerifyGotoOnStopMissing != 0)) {
          try { cbSoloPreClickUse.isChecked } catch (_: Throwable) { false }
        } else {
          false
        }
      val soloPreClickHasPicked = !(soloPreClickXCur == -1 && soloPreClickYCur == -1)
      val soloPreClickX =
        if (soloPreClickUse) {
          if (soloPreClickHasPicked) soloPreClickXCur else cx.coerceAtLeast(0)
        } else {
          -1
        }
      val soloPreClickY =
        if (soloPreClickUse) {
          if (soloPreClickHasPicked) soloPreClickYCur else cy.coerceAtLeast(0)
        } else {
          -1
        }
      try {
        logSvc(
          "save idx=$index kind=$finalKind soloVerifyEligible=$soloVerifyEligible soloVerifyUse=$soloVerifyUse " +
            "gotoMissing=$soloVerifyGotoOnStopMissing gotoFound=$soloVerifyGotoOnStopFound " +
            "preClickUse=$soloPreClickUse pre=($soloPreClickX,$soloPreClickY)"
        )
      } catch (_: Throwable) {}
      saveMarker(
        index,
        // swipe_to(서브)는 UI상 swipe로 보이더라도 저장은 swipe_to로 유지해야 체인이 깨지지 않음
        kind = finalKind,
        doubleClick = doubleClick,
        moduleDir = if (kind == "module") moduleDir else null, // legacy field (호환)
        modulePattern = if (kind == "module") modulePattern else null,
        modulePatternV2 = if (kind == "module") 2 else null,
        moduleLenPx = if (kind == "module") moduleLenPx else null,
        moduleDirMode = if (kind == "module") moduleDirMode else null,
        moduleMoveUpMs = if (kind == "module") moduleMoveUpMs else null,
        moduleSoloExec = if (kind == "module") moduleSoloExec else null,
        colorR = if (kind == "color_module") cmRgb.first else null,
        colorG = if (kind == "color_module") cmRgb.second else null,
        colorB = if (kind == "color_module") cmRgb.third else null,
        colorCheckXPx = if (kind == "color_module") cmCheckX else null,
        colorCheckYPx = if (kind == "color_module") cmCheckY else null,
        colorAccuracyPct = if (kind == "color_module") cmAcc else null,
        imageTemplateFile = if (kind == "image_module") imgFile else null,
        imageStartXPx = if (kind == "image_module") imgStartX else null,
        imageStartYPx = if (kind == "image_module") imgStartY else null,
        imageEndXPx = if (kind == "image_module") imgEndX else null,
        imageEndYPx = if (kind == "image_module") imgEndY else null,
        imageAccuracyPct = if (kind == "image_module") imgAcc else null,
        imageW = if (kind == "image_module") pickedImageW else null,
        imageH = if (kind == "image_module") pickedImageH else null,
        imageClickMode = if (kind == "image_module") imgClickMode else null,
        imageCropLeftXPx = if (kind == "image_module") pickedCropLeft.takeIf { it >= 0 } else null,
        imageCropTopYPx = if (kind == "image_module") pickedCropTop.takeIf { it >= 0 } else null,
        soloVerifyUse = if (soloVerifyEligible) soloVerifyUse else null,
        // (변경) 실행확인 방식은 1가지로 통일: mode는 더 이상 저장하지 않는다(정리).
        soloVerifyOnFoundMode = null,
        soloVerifyTemplateFile = if (soloVerifyEligible && soloVerifyUse) soloVerifyFileCur else null,
        soloVerifyStartXPx = if (soloVerifyEligible && soloVerifyUse) soloVerifyStartXCur else null,
        soloVerifyStartYPx = if (soloVerifyEligible && soloVerifyUse) soloVerifyStartYCur else null,
        soloVerifyEndXPx = if (soloVerifyEligible && soloVerifyUse) soloVerifyEndXCur else null,
        soloVerifyEndYPx = if (soloVerifyEligible && soloVerifyUse) soloVerifyEndYCur else null,
        soloVerifyAccuracyPct = if (soloVerifyEligible && soloVerifyUse) soloVerifyAcc else null,
        soloVerifyW = if (soloVerifyEligible && soloVerifyUse) pickedSoloVerifyW else null,
        soloVerifyH = if (soloVerifyEligible && soloVerifyUse) pickedSoloVerifyH else null,
        soloVerifyCropLeftXPx = if (soloVerifyEligible && soloVerifyUse) pickedSoloVerifyCropLeft.takeIf { it >= 0 } else null,
        soloVerifyCropTopYPx = if (soloVerifyEligible && soloVerifyUse) pickedSoloVerifyCropTop.takeIf { it >= 0 } else null,
        soloVerifyGotoOnStopMissing = if (soloVerifyEligible && soloVerifyUse) soloVerifyGotoOnStopMissing else null,
        // (변경) 실행확인 방식은 1가지로 통일: found용 goto는 더 이상 저장하지 않는다(정리).
        soloVerifyGotoOnStopFound = null,
        soloPreClickUse = if (soloVerifyEligible && soloVerifyUse) soloPreClickUse else null,
        soloPreClickXPx = if (soloVerifyEligible && soloVerifyUse && soloPreClickUse) soloPreClickX else null,
        soloPreClickYPx = if (soloVerifyEligible && soloVerifyUse && soloPreClickUse) soloPreClickY else null,
        swipeMode = if (kind == "swipe") swipeMode else null,
        moveUpMs = if (isSwipeSub) moveUp else null,
        soloStartDelayMs = if (kind == "solo_main" && !isSoloItem) soloStartDelayMs else null,
        soloComboCount = if (kind == "solo_main" && !isSoloItem) soloComboCount else null,
        delayMs = delay,
        jitterPct = jitter,
        pressMs = press,
        randomClickUse = randomClickUse,
      )
      ScreenCaptureService.refreshMarkersFromPrefs()
      finishWithToolbarRestore()
    }

    btnDelete.setOnClickListener {
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      deleteMarker(index)
      ScreenCaptureService.refreshMarkersFromPrefs()
      finishWithToolbarRestore()
    }

    btnClose.setOnClickListener {
      if (SystemClock.uptimeMillis() - openedAtMs < 350L) return@setOnClickListener
      finishWithToolbarRestore()
    }
  }

  override fun onStart() {
    super.onStart()
    ScreenCaptureService.pushMarkersHidden()
    ScreenCaptureService.pushMarkerMoveModeOffByModal()
    ScreenCaptureService.pushToolbarHiddenByModal()
  }

  override fun onStop() {
    // (요청) 닫기/저장/삭제로 종료될 때 툴바 복구가 확실히 반영되도록 보강
    try {
      if (isFinishing) restoreAccessibilityToolbarOnce()
    } catch (_: Throwable) {}
    ScreenCaptureService.popMarkerMoveModeOffByModal()
    ScreenCaptureService.popMarkersHidden()
    ScreenCaptureService.popToolbarHiddenByModal()
    super.onStop()
  }

  override fun onDestroy() {
    try {
      if (pickReceiver != null) unregisterReceiver(pickReceiver)
    } catch (_: Throwable) {}
    pickReceiver = null
    // (요청) 닫기/종료 경로에서 복구가 누락돼도 안전하게 1회 복구
    try {
      restoreAccessibilityToolbarOnce()
    } catch (_: Throwable) {}
    super.onDestroy()
  }

  private fun loadMarker(index: Int): JSONObject? {
    val raw = prefs().getString("flutter.markers", "[]") ?: "[]"
    return try {
      val arr = JSONArray(raw)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optInt("index") == index) return o
      }
      null
    } catch (_: Throwable) {
      null
    }
  }

  private fun saveMarker(
    index: Int,
    kind: String,
    doubleClick: Boolean?,
    moduleDir: String?,
    modulePattern: Int?,
    modulePatternV2: Int?,
    moduleLenPx: Int?,
    moduleDirMode: Int?,
    moduleMoveUpMs: Int?,
    moduleSoloExec: Boolean?,
    colorR: Int?,
    colorG: Int?,
    colorB: Int?,
    colorCheckXPx: Int?,
    colorCheckYPx: Int?,
    colorAccuracyPct: Int?,
    imageTemplateFile: String?,
    imageStartXPx: Int?,
    imageStartYPx: Int?,
    imageEndXPx: Int?,
    imageEndYPx: Int?,
    imageAccuracyPct: Int?,
    imageW: Int?,
    imageH: Int?,
    imageClickMode: Int?,
    imageCropLeftXPx: Int?,
    imageCropTopYPx: Int?,
    soloVerifyUse: Boolean?,
    soloVerifyOnFoundMode: Int?,
    soloVerifyTemplateFile: String?,
    soloVerifyStartXPx: Int?,
    soloVerifyStartYPx: Int?,
    soloVerifyEndXPx: Int?,
    soloVerifyEndYPx: Int?,
    soloVerifyAccuracyPct: Int?,
    soloVerifyW: Int?,
    soloVerifyH: Int?,
    soloVerifyCropLeftXPx: Int?,
    soloVerifyCropTopYPx: Int?,
    soloVerifyGotoOnStopMissing: Int?,
    soloVerifyGotoOnStopFound: Int?,
    soloPreClickUse: Boolean?,
    soloPreClickXPx: Int?,
    soloPreClickYPx: Int?,
    swipeMode: Int?,
    moveUpMs: Int?,
    soloStartDelayMs: Int?,
    soloComboCount: Int?,
    delayMs: Int,
    jitterPct: Int,
    pressMs: Int,
    randomClickUse: Boolean?,
  ) {
    val raw = prefs().getString("flutter.markers", "[]") ?: "[]"
    var arr =
      try {
        JSONArray(raw)
      } catch (_: Throwable) {
        JSONArray()
      }

    // 현재 index 찾기 + used index 수집
    val used = HashSet<Int>()
    var curObj: JSONObject? = null
    var prevKind: String? = null
    var prevToIndex: Int = 0
    var prevParentIndex: Int = 0
    var prevSoloLabel: String = ""
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      used.add(o.optInt("index"))
      if (o.optInt("index") != index) continue
      curObj = o
      prevKind = o.optString("kind", "click")
      prevToIndex = o.optInt("toIndex", 0)
      prevParentIndex = o.optInt("parentIndex", 0)
      prevSoloLabel = o.optString("soloLabel", "")
      o.put("kind", kind)
      o.put("delayMs", delayMs)
      o.put("jitterPct", jitterPct)
      o.put("pressMs", pressMs)
      // (요구사항) 스와이프는 더블클릭 제외
      if (doubleClick != null && kind != "swipe" && kind != "swipe_to") o.put("doubleClick", doubleClick) else o.remove("doubleClick")
      if (moduleDir != null) {
        o.put("moduleDir", moduleDir)
      } else {
        o.remove("moduleDir")
      }
      if (modulePattern != null) o.put("modulePattern", modulePattern.coerceIn(0, 10)) else o.remove("modulePattern")
      if (modulePatternV2 != null) o.put("modulePatternV2", modulePatternV2) else o.remove("modulePatternV2")
      if (moduleLenPx != null) o.put("moduleLenPx", moduleLenPx.coerceIn(30, 5000)) else o.remove("moduleLenPx")
      if (moduleDirMode != null) o.put("moduleDirMode", moduleDirMode.coerceIn(0, 1)) else o.remove("moduleDirMode")
      if (moduleMoveUpMs != null) o.put("moduleMoveUpMs", moduleMoveUpMs.coerceIn(0, 600000)) else o.remove("moduleMoveUpMs")
      if (moduleSoloExec != null) {
        if (moduleSoloExec) o.put("moduleSoloExec", true) else o.remove("moduleSoloExec")
      } else {
        o.remove("moduleSoloExec")
      }

      if (colorR != null) o.put("colorR", colorR.coerceIn(0, 255)) else if (kind != "color_module") o.remove("colorR")
      if (colorG != null) o.put("colorG", colorG.coerceIn(0, 255)) else if (kind != "color_module") o.remove("colorG")
      if (colorB != null) o.put("colorB", colorB.coerceIn(0, 255)) else if (kind != "color_module") o.remove("colorB")
      if (colorCheckXPx != null) o.put("colorCheckXPx", colorCheckXPx.coerceAtLeast(0)) else if (kind != "color_module") o.remove("colorCheckXPx")
      if (colorCheckYPx != null) o.put("colorCheckYPx", colorCheckYPx.coerceAtLeast(0)) else if (kind != "color_module") o.remove("colorCheckYPx")
      if (colorAccuracyPct != null) o.put("colorAccuracyPct", colorAccuracyPct.coerceIn(50, 100)) else if (kind != "color_module") o.remove("colorAccuracyPct")

      if (imageTemplateFile != null) o.put("imageTemplateFile", imageTemplateFile) else if (kind != "image_module") o.remove("imageTemplateFile")
      if (imageStartXPx != null) o.put("imageStartXPx", imageStartXPx.coerceAtLeast(0)) else if (kind != "image_module") o.remove("imageStartXPx")
      if (imageStartYPx != null) o.put("imageStartYPx", imageStartYPx.coerceAtLeast(0)) else if (kind != "image_module") o.remove("imageStartYPx")
      if (imageEndXPx != null) o.put("imageEndXPx", imageEndXPx.coerceAtLeast(0)) else if (kind != "image_module") o.remove("imageEndXPx")
      if (imageEndYPx != null) o.put("imageEndYPx", imageEndYPx.coerceAtLeast(0)) else if (kind != "image_module") o.remove("imageEndYPx")
      if (imageAccuracyPct != null) o.put("imageAccuracyPct", imageAccuracyPct.coerceIn(50, 100)) else if (kind != "image_module") o.remove("imageAccuracyPct")
      if (imageW != null) o.put("imageW", imageW.coerceIn(8, 1024)) else if (kind != "image_module") o.remove("imageW")
      if (imageH != null) o.put("imageH", imageH.coerceIn(8, 1024)) else if (kind != "image_module") o.remove("imageH")
      if (imageClickMode != null) o.put("imageClickMode", imageClickMode.coerceIn(0, 3)) else if (kind != "image_module") o.remove("imageClickMode")
      if (imageCropLeftXPx != null) o.put("imageCropLeftXPx", imageCropLeftXPx.coerceAtLeast(0)) else if (kind != "image_module") o.remove("imageCropLeftXPx")
      if (imageCropTopYPx != null) o.put("imageCropTopYPx", imageCropTopYPx.coerceAtLeast(0)) else if (kind != "image_module") o.remove("imageCropTopYPx")
      // (호환) 예전 키는 더 이상 저장하지 않음(깨끗이 유지)
      if (kind != "image_module") {
        o.remove("imageCheckXPx")
        o.remove("imageCheckYPx")
      }

      // solo verify(클릭실행확인) 저장/정리
      if (soloVerifyUse == true) o.put("soloVerifyUse", true) else o.remove("soloVerifyUse")
      if (soloVerifyOnFoundMode != null) o.put("soloVerifyOnFoundMode", soloVerifyOnFoundMode.coerceIn(0, 1)) else o.remove("soloVerifyOnFoundMode")
      if (!soloVerifyTemplateFile.isNullOrBlank()) o.put("soloVerifyTemplateFile", soloVerifyTemplateFile.trim()) else o.remove("soloVerifyTemplateFile")
      if (soloVerifyStartXPx != null) o.put("soloVerifyStartXPx", soloVerifyStartXPx.coerceAtLeast(0)) else o.remove("soloVerifyStartXPx")
      if (soloVerifyStartYPx != null) o.put("soloVerifyStartYPx", soloVerifyStartYPx.coerceAtLeast(0)) else o.remove("soloVerifyStartYPx")
      if (soloVerifyEndXPx != null) o.put("soloVerifyEndXPx", soloVerifyEndXPx.coerceAtLeast(0)) else o.remove("soloVerifyEndXPx")
      if (soloVerifyEndYPx != null) o.put("soloVerifyEndYPx", soloVerifyEndYPx.coerceAtLeast(0)) else o.remove("soloVerifyEndYPx")
      if (soloVerifyAccuracyPct != null) o.put("soloVerifyAccuracyPct", soloVerifyAccuracyPct.coerceIn(50, 100)) else o.remove("soloVerifyAccuracyPct")
      if (soloVerifyW != null) o.put("soloVerifyW", soloVerifyW.coerceIn(8, 1024)) else o.remove("soloVerifyW")
      if (soloVerifyH != null) o.put("soloVerifyH", soloVerifyH.coerceIn(8, 1024)) else o.remove("soloVerifyH")
      if (soloVerifyCropLeftXPx != null) o.put("soloVerifyCropLeftXPx", soloVerifyCropLeftXPx.coerceAtLeast(0)) else o.remove("soloVerifyCropLeftXPx")
      if (soloVerifyCropTopYPx != null) o.put("soloVerifyCropTopYPx", soloVerifyCropTopYPx.coerceAtLeast(0)) else o.remove("soloVerifyCropTopYPx")
      if (soloVerifyGotoOnStopMissing != null && soloVerifyGotoOnStopMissing != 0) o.put("soloVerifyGotoOnStopMissing", soloVerifyGotoOnStopMissing)
      else o.remove("soloVerifyGotoOnStopMissing")
      if (soloVerifyGotoOnStopFound != null && soloVerifyGotoOnStopFound != 0) o.put("soloVerifyGotoOnStopFound", soloVerifyGotoOnStopFound)
      else o.remove("soloVerifyGotoOnStopFound")

      // solo verify 실행 전 클릭(1회) 저장/정리
      // - goto가 꺼진(둘 다 0) 경우에는 preClick 데이터를 남기지 않는다.
      val gotoEnabled =
        (soloVerifyGotoOnStopMissing != null && soloVerifyGotoOnStopMissing != 0) ||
          (soloVerifyGotoOnStopFound != null && soloVerifyGotoOnStopFound != 0)
      if (gotoEnabled && soloPreClickUse == true && soloPreClickXPx != null && soloPreClickYPx != null) {
        o.put("soloPreClickUse", true)
        // (중요) 좌표선택 오버레이가 화면 전체(네비바/컷아웃 포함)까지 이동 가능하므로
        // usable 기준 좌표는 음수/범위초과가 될 수 있다. 그대로 저장한다.
        o.put("soloPreClickXPx", soloPreClickXPx)
        o.put("soloPreClickYPx", soloPreClickYPx)
      } else {
        o.remove("soloPreClickUse")
        o.remove("soloPreClickXPx")
        o.remove("soloPreClickYPx")
      }
      if (swipeMode != null) {
        o.put("swipeMode", swipeMode.coerceIn(0, 1))
      } else {
        o.remove("swipeMode")
      }
      if (moveUpMs != null) {
        o.put("moveUpMs", moveUpMs.coerceIn(0, 600000))
      }
      if (soloStartDelayMs != null) {
        o.put("soloStartDelayMs", soloStartDelayMs.coerceIn(0, 3600000))
      }
      if (soloComboCount != null) {
        o.put("soloComboCount", soloComboCount.coerceIn(1, 10))
      }

      // (요청) AI탐지방어(랜덤 실행) 저장:
      // - 단독/서브/색상모듈은 제외 => 키 제거
      if (randomClickUse != null) {
        o.put("randomClickUse", randomClickUse)
      } else {
        o.remove("randomClickUse")
      }
      break
    }

    // module -> 다른 종류로 변경 시 module 필드 정리(데이터 고아 방지)
    if (prevKind == "module" && kind != "module") {
      val cur = curObj
      if (cur != null) {
        cur.remove("moduleDir")
        cur.remove("modulePattern")
        cur.remove("modulePatternV2")
        cur.remove("moduleLenPx")
        cur.remove("moduleMoveUpMs")
        cur.remove("moduleDirMode")
        cur.remove("moduleSoloExec")
      }
    }

    // color_module -> 다른 종류로 변경 시 color_module 필드 정리
    if (prevKind == "color_module" && kind != "color_module") {
      val cur = curObj
      if (cur != null) {
        cur.remove("colorR")
        cur.remove("colorG")
        cur.remove("colorB")
        cur.remove("colorCheckXPx")
        cur.remove("colorCheckYPx")
        cur.remove("colorAccuracyPct")
      }
    }

    // image_module -> 다른 종류로 변경 시 image_module 필드 정리
    if (prevKind == "image_module" && kind != "image_module") {
      val cur = curObj
      if (cur != null) {
        cur.remove("imageTemplateFile")
        cur.remove("imageStartXPx")
        cur.remove("imageStartYPx")
        cur.remove("imageEndXPx")
        cur.remove("imageEndYPx")
        cur.remove("imageAccuracyPct")
        cur.remove("imageW")
        cur.remove("imageH")
        cur.remove("imageClickMode")
        cur.remove("imageCropLeftXPx")
        cur.remove("imageCropTopYPx")
        // (호환) 예전 키도 정리
        cur.remove("imageCheckXPx")
        cur.remove("imageCheckYPx")
      }
    }

    // solo_main/solo_item -> 다른 종류로 변경 시 solo verify 필드 정리
    if ((prevKind == "solo_main" || prevKind == "solo_item") && (kind != "solo_main" && kind != "solo_item")) {
      val cur = curObj
      if (cur != null) {
        cur.remove("soloVerifyUse")
        cur.remove("soloVerifyOnFoundMode")
        cur.remove("soloVerifyTemplateFile")
        cur.remove("soloVerifyStartXPx")
        cur.remove("soloVerifyStartYPx")
        cur.remove("soloVerifyEndXPx")
        cur.remove("soloVerifyEndYPx")
        cur.remove("soloVerifyAccuracyPct")
        cur.remove("soloVerifyW")
        cur.remove("soloVerifyH")
        cur.remove("soloVerifyCropLeftXPx")
        cur.remove("soloVerifyCropTopYPx")
        cur.remove("soloVerifyGotoOnStopMissing")
        cur.remove("soloVerifyGotoOnStopFound")
        cur.remove("soloPreClickUse")
        cur.remove("soloPreClickXPx")
        cur.remove("soloPreClickYPx")
      }
    }

    // (요청) solo_main(단독 메인) -> 다른 종류로 변경 시:
    // - 해당 solo_main에 매달린 solo_item(단독 sub) 전체 삭제(고아 데이터 방지)
    // - solo 관련 필드 정리
    if ((prevKind == "solo_main" || prevKind == "solo") && kind != "solo_main") {
      val cur = curObj
      if (cur != null) {
        cur.remove("soloLabel")
        cur.remove("soloStartDelayMs")
        cur.remove("soloComboCount")
      }

      val removeIdx = HashSet<Int>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("kind", "") == "solo_item" && o.optInt("parentIndex", 0) == index) {
          removeIdx.add(o.optInt("index"))
        }
      }
      if (removeIdx.isNotEmpty()) {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val idx = o.optInt("index")
          if (removeIdx.contains(idx)) {
            used.remove(idx)
            continue
          }
          out.put(o)
        }
        arr = out
      }
    }

    // (보강) solo_item(단독 sub) -> 다른 종류로 변경 시:
    // - parentIndex/soloLabel 제거
    // - 형제 solo_item 라벨 재정렬 + 메인 콤보개수 조정
    if (prevKind == "solo_item" && kind != "solo_item") {
      val cur = curObj
      if (cur != null) {
        cur.remove("parentIndex")
        cur.remove("soloLabel")
      }

      val parentIdx = prevParentIndex
      val letter = prevSoloLabel.trim().uppercase().firstOrNull { it.isLetter() }?.toString() ?: "A"

      fun suffixNum(lab: String): Int? {
        val s = lab.trim().uppercase()
        if (!s.startsWith(letter)) return null
        val tail = s.substring(letter.length)
        return tail.toIntOrNull()
      }

      // 형제 수집(변경된 현재 마커는 kind가 바뀌었으므로 자동 제외됨)
      val siblings = mutableListOf<JSONObject>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("kind") == "solo_item" && o.optInt("parentIndex", 0) == parentIdx) {
          siblings.add(o)
        }
      }

      siblings.sortWith { a, b ->
        val na = suffixNum(a.optString("soloLabel", "")) ?: Int.MAX_VALUE
        val nb = suffixNum(b.optString("soloLabel", "")) ?: Int.MAX_VALUE
        if (na != nb) return@sortWith na.compareTo(nb)
        val ya = a.optInt("yPx", 0)
        val yb = b.optInt("yPx", 0)
        if (ya != yb) return@sortWith ya.compareTo(yb)
        a.optString("soloLabel", "").compareTo(b.optString("soloLabel", ""))
      }

      for (i in siblings.indices) {
        siblings[i].put("soloLabel", "$letter${i + 1}")
      }

      val remainCount = siblings.size.coerceIn(0, 10)
      val newCombo = (if (remainCount <= 0) 1 else remainCount).coerceIn(1, 10)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optInt("index") == parentIdx && o.optString("kind") == "solo_main") {
          o.put("soloComboCount", newCombo)
          break
        }
      }
    }

    // 스와이프 메인 -> 다른 종류로 변경 시:
    // - 기존 스와이프 메인/서브(swipe_to) 체인 데이터를 정리(삭제)하고
    // - 선택된 종류 마커만 남기기
    //
    // (버그픽스) prevKind가 "swipe"로 정확히 남아있지 않은(데이터 꼬임/호환) 경우에도,
    // "스와이프 메인으로 판단되는 흔적(toIndex 체인 또는 parentIndex로 매달린 swipe_to)"이 있으면
    // 변환 시 swipe_to를 반드시 삭제한다.
    if (kind != "swipe" && prevKind != "swipe_to" && kind != "swipe_to") {
      var hasSwipeChild = false
      try {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          if (o.optString("kind", "") == "swipe_to" && o.optInt("parentIndex", 0) == index) {
            hasSwipeChild = true
            break
          }
        }
      } catch (_: Throwable) {}
      val shouldCleanSwipe = (prevKind == "swipe") || hasSwipeChild || (prevToIndex != 0)
      if (shouldCleanSwipe) {
        val main = curObj
        if (main != null) {
          // 링크 해제(메인에 남은 흔적 제거)
          main.put("toIndex", 0)
          main.remove("swipeMode")
          main.remove("parentIndex")
        }

        // 체인 추적 + parentIndex 기반으로 swipe_to 전부 제거
        val removeIdx = HashSet<Int>()
        // 1) 체인(toIndex)으로 연결된 swipe_to 제거
        var cur = prevToIndex
        var guard = 0
        while (cur != 0 && guard++ < 60) {
          var node: JSONObject? = null
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("index") == cur) {
              node = o
              break
            }
          }
          val n = node ?: break
          if (n.optString("kind", "") != "swipe_to") break
          removeIdx.add(cur)
          cur = n.optInt("toIndex", 0)
        }
        // 2) parentIndex로 매달린 swipe_to도 제거(고아 데이터 방지)
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          if (o.optString("kind", "") == "swipe_to" && o.optInt("parentIndex", 0) == index) {
            removeIdx.add(o.optInt("index"))
          }
        }

        if (removeIdx.isNotEmpty()) {
          val out = JSONArray()
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("index")
            if (removeIdx.contains(idx)) {
              used.remove(idx)
              continue
            }
            out.put(o)
          }
          arr = out
        }
      }
    }

    // solo_main 변환/저장: soloLabel(A~Z) + 고정 index(20001~20026) 자동 부여
    // - soloLabel이 비어 있으면 스케줄러에서 단독메인으로 인식되지 않는다.
    // - 고정 index는 A=20001 ... Z=20026
    val oldIndex = index
    var effectiveIndex = index
    if (kind == "solo_main") {
      val main = curObj
      if (main != null) {
        fun fixedIndex(letter: Char): Int = 20000 + (letter - 'A' + 1)

        fun usedLettersAndFixedIndexes(): Pair<HashSet<Char>, HashSet<Int>> {
          val letters = HashSet<Char>()
          val fixed = HashSet<Int>()
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("index")
            val k = o.optString("kind", "")
            if (k != "solo_main" && k != "solo") continue
            if (idx == oldIndex) continue
            // soloLabel이 비어도 고정 index(20001~20026)면 글자 복원
            val c =
              when {
                idx in 20001..20026 -> ('A'.code + (idx - 20001)).toChar()
                else -> {
                  val lab = o.optString("soloLabel", "").trim().uppercase()
                  lab.firstOrNull { it in 'A'..'Z' } ?: continue
                }
              }
            letters.add(c)
            fixed.add(fixedIndex(c))
          }
          return Pair(letters, fixed)
        }

        val idxNow = main.optInt("index")
        val curLab = main.optString("soloLabel", "").trim().uppercase()
        val curC =
          when {
            idxNow in 20001..20026 -> ('A'.code + (idxNow - 20001)).toChar()
            else -> curLab.firstOrNull { it in 'A'..'Z' }
          }
        val (usedLetters, usedFixed) = usedLettersAndFixedIndexes()

        val picked =
          if (curC != null && !usedFixed.contains(fixedIndex(curC))) curC
          else ('A'..'Z').firstOrNull { !usedFixed.contains(fixedIndex(it)) } ?: 'A'

        val newIndex = fixedIndex(picked)
        val newLetter = picked.toString()
        // soloLabel 보강(누락/불일치 방지)
        main.put("soloLabel", newLetter)

        // index 변경 시: 기존 자식(parentIndex) 및 라벨도 함께 보정
        if (newIndex != oldIndex) {
          main.put("index", newIndex)
          effectiveIndex = newIndex
          used.remove(oldIndex)
          used.add(newIndex)

          // solo_item parentIndex 갱신 + 라벨 첫 글자도 갱신(A1.. -> B1..)
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("kind") != "solo_item") continue
            if (o.optInt("parentIndex", 0) != oldIndex) continue
            o.put("parentIndex", newIndex)
            val lab = o.optString("soloLabel", "").trim().uppercase()
            val n = lab.dropWhile { it.isLetter() }.toIntOrNull()
            if (n != null && n in 1..10) {
              o.put("soloLabel", "$newLetter$n")
            }
          }
        } else {
          // index는 그대로인데 라벨이 바뀌는 경우(누락/구버전 복원 등)에도
          // 자식 라벨의 첫 글자를 메인 라벨에 맞춰 보정한다.
          val oldLetter = curC?.toString() ?: ""
          if (oldLetter.isNotBlank() && oldLetter != newLetter) {
            for (i in 0 until arr.length()) {
              val o = arr.optJSONObject(i) ?: continue
              if (o.optString("kind") != "solo_item") continue
              if (o.optInt("parentIndex", 0) != oldIndex) continue
              val lab = o.optString("soloLabel", "").trim().uppercase()
              val n = lab.dropWhile { it.isLetter() }.toIntOrNull()
              if (n != null && n in 1..10) {
                o.put("soloLabel", "$newLetter$n")
              }
            }
          }
        }
      }
    }

    // 단독 메인: 콤보 개수 저장 시, 기존 solo_item을 검사하여 개수에 맞게 추가/삭제
    if (kind == "solo_main" && soloComboCount != null) {
      val main = curObj
      if (main != null) {
        val letter =
          main.optString("soloLabel", "A")
            .trim()
            .uppercase()
            .take(1)
            .ifBlank { "A" }
        val desired = soloComboCount.coerceIn(1, 10)

        fun childNum(o: JSONObject): Int? {
          if (o.optString("kind") != "solo_item") return null
          if (o.optInt("parentIndex", 0) != effectiveIndex) return null
          val lab = o.optString("soloLabel", "").trim().uppercase()
          if (!lab.startsWith(letter)) return null
          val tail = lab.substring(letter.length)
          val n = tail.toIntOrNull() ?: return null
          if (n !in 1..10) return null
          return n
        }

        val has = HashSet<Int>()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val n = childNum(o)
          if (n != null) {
            if (n > desired) {
              // 삭제 대상(개수 초과)
              continue
            }
            has.add(n)
          }
          out.put(o)
        }

        fun takeNeg(): Int {
          var n = -1
          while (n == 0 || used.contains(n)) n--
          used.add(n)
          return n
        }

        val baseX = main.optInt("xPx", 0)
        val baseY = main.optInt("yPx", 0)
        val xp = main.optDouble("xPct", 0.0)
        val yp = main.optDouble("yPct", 0.0)
        for (n in 1..desired) {
          if (has.contains(n)) continue
          val child = JSONObject()
          child.put("index", takeNeg())
          child.put("kind", "solo_item")
          child.put("parentIndex", effectiveIndex)
          child.put("soloLabel", "$letter$n")
          child.put("delayMs", 1000) // 단독sub 기본 지연
          child.put("jitterPct", try { prefs().getInt("flutter.random_delay_pct", 50).coerceIn(0, 100) } catch (_: Throwable) { 50 })
          child.put("pressMs", 0)
          child.put("xPx", baseX)
          child.put("yPx", (baseY + 120 * n).coerceAtLeast(0))
          child.put("xPct", xp)
          child.put("yPct", yp)
          out.put(child)
        }

        arr = out
      }
    }

    // 스와이프(메인) 선택 시: 메인(toIndex) + 서브(swipe_to) 1개 자동 생성
    // - 메인은 kind=="swipe" 로 저장(실행 루프에서 click과 같이 처리됨)
    // - 서브는 kind=="swipe_to" 링으로 표시됨
    if (kind == "swipe") {
      val main = curObj
      if (main != null) {
        // 기본: 순번실행
        if (!main.has("swipeMode")) main.put("swipeMode", 0)
        val toIndex = main.optInt("toIndex", 0)
        val hasValidFirst =
          if (toIndex == 0) false
          else {
            var ok = false
            for (i in 0 until arr.length()) {
              val o = arr.optJSONObject(i) ?: continue
              if (o.optInt("index") == toIndex && o.optString("kind") == "swipe_to") {
                ok = true
                break
              }
            }
            ok
          }

        if (!hasValidFirst) {
          var nextNeg = -1
          while (nextNeg == 0 || used.contains(nextNeg)) nextNeg--

          val cx = main.optInt("xPx", 0)
          val cy = main.optInt("yPx", 0)
          val xp = main.optDouble("xPct", Double.NaN)
          val yp = main.optDouble("yPct", Double.NaN)

          val sub = JSONObject()
          sub.put("index", nextNeg)
          sub.put("kind", "swipe_to")
          sub.put("parentIndex", index)
          sub.put("toIndex", 0)
          sub.put("moveUpMs", 700)

          // 첫 서브는 메인 옆에 기본 배치(usable 기준)
          sub.put("xPx", (cx + 140).coerceAtLeast(0))
          sub.put("yPx", cy.coerceAtLeast(0))
          sub.put("xPct", if (!xp.isNaN()) xp else 0.0)
          sub.put("yPct", if (!yp.isNaN()) yp else 0.0)

          sub.put("delayMs", 300)
          sub.put("jitterPct", try { prefs().getInt("flutter.random_delay_pct", 50).coerceIn(0, 100) } catch (_: Throwable) { 50 })
          sub.put("pressMs", 0)

          arr.put(sub)
          main.put("toIndex", nextNeg)
        }
      }
    }

    commitMarkersJson(arr.toString())
  }

  private fun deleteMarker(index: Int) {
    val raw = prefs().getString("flutter.markers", "[]") ?: "[]"
    val arr =
      try {
        JSONArray(raw)
      } catch (_: Throwable) {
        JSONArray()
      }

    var target: JSONObject? = null
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      if (o.optInt("index") == index) {
        target = o
        break
      }
    }
    val t = target ?: return
    val kind = t.optString("kind", "click")

    // 1) 단독메인 삭제: 해당 단독SUB 전체 삭제
    if (kind == "solo_main") {
      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val idx = o.optInt("index")
        if (idx == index) continue
        if (o.optString("kind") == "solo_item" && o.optInt("parentIndex", 0) == index) continue
        out.put(o)
      }
      commitMarkersJson(out.toString())
      return
    }

    // 1-2) 스와이프(메인) 삭제: 연결된 swipe_to(SUB) 체인도 함께 삭제
    if (kind == "swipe") {
      // index -> obj 맵(체인 추적용)
      val map = HashMap<Int, JSONObject>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        map[o.optInt("index")] = o
      }

      val toDelete = HashSet<Int>()
      toDelete.add(index)

      // 1) main.toIndex로 연결된 체인 삭제
      var cur = t.optInt("toIndex", 0)
      var guard = 0
      while (cur != 0 && guard++ < 60) {
        val node = map[cur] ?: break
        if (node.optString("kind") != "swipe_to") break
        toDelete.add(cur)
        cur = node.optInt("toIndex", 0)
      }

      // 2) 혹시 링크가 깨져도 parentIndex로 매달린 SUB는 모두 삭제(고아 방지)
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("kind") == "swipe_to" && o.optInt("parentIndex", 0) == index) {
          toDelete.add(o.optInt("index"))
        }
      }

      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val idx = o.optInt("index")
        if (toDelete.contains(idx)) continue
        out.put(o)
      }
      commitMarkersJson(out.toString())
      return
    }

    // 2) 단독SUB 삭제: 남은 SUB 순서 재정렬 + 메인 콤보개수 조정
    if (kind == "solo_item") {
      val parentIdx = t.optInt("parentIndex", 0)
      val label = t.optString("soloLabel", "").trim().uppercase()
      val letter = label.firstOrNull { it.isLetter() }?.toString() ?: "A"

      fun suffixNum(lab: String): Int? {
        val s = lab.trim().uppercase()
        if (!s.startsWith(letter)) return null
        val tail = s.substring(letter.length)
        return tail.toIntOrNull()
      }

      // 먼저 타겟 삭제
      val withoutTarget = JSONArray()
      val siblings = mutableListOf<JSONObject>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optInt("index") == index) continue
        withoutTarget.put(o)
        if (o.optString("kind") == "solo_item" && o.optInt("parentIndex", 0) == parentIdx) {
          siblings.add(o)
        }
      }

      // 형제들을 기존 번호 기준으로 정렬(없으면 라벨/좌표로 fallback)
      siblings.sortWith { a, b ->
        val na = suffixNum(a.optString("soloLabel", "")) ?: Int.MAX_VALUE
        val nb = suffixNum(b.optString("soloLabel", "")) ?: Int.MAX_VALUE
        if (na != nb) return@sortWith na.compareTo(nb)
        val ya = a.optInt("yPx", 0)
        val yb = b.optInt("yPx", 0)
        if (ya != yb) return@sortWith ya.compareTo(yb)
        a.optString("soloLabel", "").compareTo(b.optString("soloLabel", ""))
      }

      // A1..A# 라벨 재부여(인덱스는 유지)
      for (i in siblings.indices) {
        siblings[i].put("soloLabel", "$letter${i + 1}")
      }

      // 메인 콤보개수 조정(남은 SUB 개수, 최소 1)
      val remainCount = siblings.size.coerceIn(0, 10)
      val newCombo = (if (remainCount <= 0) 1 else remainCount).coerceIn(1, 10)
      for (i in 0 until withoutTarget.length()) {
        val o = withoutTarget.optJSONObject(i) ?: continue
        if (o.optInt("index") == parentIdx && o.optString("kind") == "solo_main") {
          o.put("soloComboCount", newCombo)
          break
        }
      }

      commitMarkersJson(withoutTarget.toString())
      return
    }

    // 2-2) 스와이프(SUB) 삭제: 체인에서 자신을 제거하고(링크 복구), 자신만 삭제
    if (kind == "swipe_to") {
      val myIdx = index
      val myNext = t.optInt("toIndex", 0)
      val out = JSONArray()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val idx = o.optInt("index")
        if (idx == myIdx) continue
        // 나를 가리키는 링크가 있으면 next로 우회(메인/서브 공통)
        if (o.optInt("toIndex", 0) == myIdx) {
          o.put("toIndex", myNext)
        }
        out.put(o)
      }
      commitMarkersJson(out.toString())
      return
    }

    // 3) 그 외: 단순 삭제
    val kept = JSONArray()
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      if (o.optInt("index") == index) continue
      kept.put(o)
    }
    commitMarkersJson(kept.toString())
  }
}

