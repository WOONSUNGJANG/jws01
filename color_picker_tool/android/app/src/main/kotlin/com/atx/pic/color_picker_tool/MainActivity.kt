package com.atx.pic.color_picker_tool

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.text.TextUtils
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class MainActivity : FlutterActivity() {
  companion object {
    private const val REQ_MEDIA_PROJECTION = 9001
  }

  private val channelName = "system_color_picker"
  private fun flutterPrefs() = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)

  private var pendingStartResult: MethodChannel.Result? = null
  private var pendingStartFromOverlay: Boolean = false

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    try { CrashLogger.install(this, "MainActivity") } catch (_: Throwable) {}
    super.onCreate(savedInstanceState)
  }

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)

    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
      .setMethodCallHandler { call, result ->
        when (call.method) {
          "getLastNativeCrash" -> {
            try {
              val dir = try { getExternalFilesDir(null) } catch (_: Throwable) { null }
              val f = File(dir ?: filesDir, "autoclick_crash_last.txt")
              if (!f.exists()) {
                result.success(null)
                return@setMethodCallHandler
              }
              val txt = try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
              if (txt.isBlank()) {
                result.success(null)
                return@setMethodCallHandler
              }
              // MethodChannel payload 과대 방지(필요하면 뒤쪽 위주로)
              val max = 60000
              val out = if (txt.length > max) txt.takeLast(max) else txt
              result.success(out)
              return@setMethodCallHandler
            } catch (_: Throwable) {
              result.success(null)
              return@setMethodCallHandler
            }
          }
          "clearLastNativeCrash" -> {
            try {
              val dir = try { getExternalFilesDir(null) } catch (_: Throwable) { null }
              val f = File(dir ?: filesDir, "autoclick_crash_last.txt")
              try { if (f.exists()) f.delete() } catch (_: Throwable) {}
              result.success(true)
              return@setMethodCallHandler
            } catch (_: Throwable) {
              result.success(false)
              return@setMethodCallHandler
            }
          }
          "getLanguage" -> {
            val code = try { flutterPrefs().getString("flutter.lang", "ko") } catch (_: Throwable) { "ko" }
            result.success((code ?: "ko").ifBlank { "ko" })
          }
          "openAccessibilitySettings" -> {
            // 시작 화면의 "접근성 설정 확인" 버튼에서 사용
            try {
              startActivity(Intent(this, AccessibilityIntroActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              result.success(true)
              return@setMethodCallHandler
            } catch (_: Throwable) {}
            try {
              startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
            result.success(true)
          }
          "ensurePermissionsAndShowToolbar" -> {
            // 1) 접근성이 꺼져있으면 설정/도움 화면부터 띄우게 함(권한 먼저)
            if (!isAccessibilityServiceEnabled()) {
              try {
                val intent = Intent(this, AccessibilityIntroActivity::class.java).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
              } catch (_: Exception) {
                // 폴백: 접근성 설정 화면
                try {
                  val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                  startActivity(i)
                } catch (_: Exception) {
                }
              }
              result.success(false)
              return@setMethodCallHandler
            }
            // 2) 접근성이 켜져있으면 OK
            // (요청) 메뉴바를 접근성 TYPE_ACCESSIBILITY_OVERLAY 방식으로 사용
            try {
              flutterPrefs().edit().putBoolean("flutter.use_accessibility_toolbar", true).apply()
            } catch (_: Throwable) {}
            result.success(true)
          }
          "showAccessibilityToolbar" -> {
            if (!isAccessibilityServiceEnabled()) {
              result.success(false)
              return@setMethodCallHandler
            }
            // (요청) 시작 시 기본 상태:
            // - 3번째(편집/이동) OFF
            // - 5번째(객체보기) ON
            try {
              flutterPrefs().edit()
                .putBoolean("flutter.marker_edit_mode", false)
                .putBoolean("flutter.objects_visible", true)
                .apply()
            } catch (_: Throwable) {}
            // 실행 중인 서비스가 있으면 상태도 즉시 반영
            try {
              startService(Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_TOOL_SET_EDIT_MODE
                putExtra(ScreenCaptureService.EXTRA_BOOL_VALUE, false)
              })
              startService(Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_TOOL_SET_OBJECTS_VISIBLE
                putExtra(ScreenCaptureService.EXTRA_BOOL_VALUE, true)
              })
            } catch (_: Throwable) {}
            try {
              // (안전장치) 숨김 카운트가 꼬여도 시작 직후엔 무조건 표시
              AutoClickAccessibilityService.requestForceShowToolbar()
            } catch (_: Throwable) {}
            result.success(true)
          }
          "hideAccessibilityToolbar" -> {
            try {
              AutoClickAccessibilityService.requestHideToolbar()
            } catch (_: Throwable) {}
            result.success(true)
          }
          "startBasicPicker" -> {
            // (요청) 시작창에서 "기본 시작": 화면공유(MediaProjection) 권한 묻지 않고 서비스/오버레이만 시작
            if (!Settings.canDrawOverlays(this)) {
              val uri = Uri.parse("package:$packageName")
              val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
              startActivity(intent)
              result.success(mapOf("started" to false, "reason" to "needs_overlay_permission"))
              return@setMethodCallHandler
            }

            try {
              val i = ScreenCaptureService.startBasicIntent(this)
              // startForegroundService를 쓰면 5초 내 startForeground가 필요하므로(기본 시작은 캡처 FGS 아님),
              // 여기서는 startService로 시작한다.
              startService(i)
              result.success(mapOf("started" to true))
            } catch (_: Throwable) {
              result.success(mapOf("started" to false, "reason" to "start_failed"))
            }
          }
          "startSystemPicker" -> startSystemPicker(result)
          "stopSystemPicker" -> {
            val i = ScreenCaptureService.stopIntent(this)
            stopService(i)
            result.success(true)
          }
          "getLastColorArgb" -> result.success(ScreenCaptureService.lastPickedArgb)
          "getLastMacroName" -> result.success(flutterPrefs().getString("flutter.last_macro_name", "") ?: "")
          "loadLastMacro" -> {
            val sp = flutterPrefs()
            val name = sp.getString("flutter.last_macro_name", "") ?: ""
            val json = sp.getString("flutter.last_macro_json", null)
            if (json.isNullOrBlank()) {
              result.success(mapOf("loaded" to false, "reason" to "no_macro"))
              return@setMethodCallHandler
            }
            sp.edit().putString("flutter.markers", json).apply()
            ScreenCaptureService.refreshMarkersFromPrefs()
            result.success(mapOf("loaded" to true, "name" to name))
          }
          "saveLastMacroFromCurrentMarkers" -> {
            val sp = flutterPrefs()
            val rawMarkers = sp.getString("flutter.markers", "[]") ?: "[]"

            // "메크로파일" 형태의 JSON인지 최소한만 검사 (배열이면 그대로 저장)
            val normalizedJson =
              try {
                JSONArray(rawMarkers).toString()
              } catch (_: Throwable) {
                "[]"
              }

            val argName = call.argument<String>("name")?.trim().orEmpty()
            val name =
              if (argName.isNotEmpty()) argName
              else {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
                "마지막 메크로 (${fmt.format(Date())})"
              }

            sp.edit()
              .putString("flutter.last_macro_name", name)
              .putString("flutter.last_macro_json", normalizedJson)
              .apply()

            result.success(mapOf("saved" to true, "name" to name))
          }
          else -> result.notImplemented()
        }
      }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    val i = intent
    if (i?.getBooleanExtra("auto_start_picker", false) == true) {
      i.removeExtra("auto_start_picker")
      startSystemPickerFromOverlay()
    }

    // (안전) 공유 chooser에서 툴바를 숨겼던 경우, 앱으로 복귀하면 툴바를 복구한다.
    // (API/기기별로 chooser callback이 오지 않는 경우 대비)
    try {
      val sp = flutterPrefs()
      if (sp.getBoolean("flutter.restore_toolbar_after_share", false)) {
        sp.edit().putBoolean("flutter.restore_toolbar_after_share", false).apply()
        try { AutoClickAccessibilityService.requestShowToolbar() } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}

    // 앱 시작/복귀 시 접근성 서비스 안내(한 번만)
    maybePromptAccessibilityOnce()
  }

  private fun maybePromptAccessibilityOnce() {
    // 이미 켜져 있으면 종료
    if (isAccessibilityServiceEnabled()) return
    val sp = getSharedPreferences("atx_pic_prefs", MODE_PRIVATE)
    if (sp.getBoolean("asked_accessibility", false)) return
    sp.edit().putBoolean("asked_accessibility", true).apply()

    try {
      startActivity(Intent(this, AccessibilityIntroActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {}
  }

  private fun isAccessibilityServiceEnabled(): Boolean {
    val expected = "$packageName/${AutoClickAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    if (enabled.isNullOrBlank()) return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) {
      if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
  }

  private fun startSystemPickerFromOverlay() {
    // 오버레이 버튼에서 호출되는 경로: Flutter 채널 응답이 없으니 내부적으로만 시작
    if (!Settings.canDrawOverlays(this)) {
      val uri = Uri.parse("package:$packageName")
      val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
      startActivity(intent)
      return
    }

    if (Build.VERSION.SDK_INT >= 33) {
      val granted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        ActivityCompat.requestPermissions(
          this,
          arrayOf(Manifest.permission.POST_NOTIFICATIONS),
          1001
        )
        return
      }
    }

    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    // (요청) 가능하면 "전체 화면 공유" 흐름으로 더 직관적으로 유도.
    // Android 14+에서는 시스템 UI에서 "단일 앱/전체 화면"을 사용자가 선택해야 하며,
    // 앱이 강제로 기본값(전체화면)을 미리 선택하는 것은 제한된다.
    // 다만 API 34+에서는 config 기반 intent를 사용해 "기본 디스플레이" 캡처로 제한할 수 있다.
    val captureIntent =
      if (Build.VERSION.SDK_INT >= 34) {
        try {
          mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } catch (_: Throwable) {
          mgr.createScreenCaptureIntent()
        }
      } else {
        mgr.createScreenCaptureIntent()
      }
    pendingStartFromOverlay = true
    @Suppress("DEPRECATION")
    startActivityForResult(captureIntent, REQ_MEDIA_PROJECTION)
  }

  private fun startSystemPicker(result: MethodChannel.Result) {
    // 오버레이 권한 확인
    if (!Settings.canDrawOverlays(this)) {
      val uri = Uri.parse("package:$packageName")
      val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
      startActivity(intent)
      result.success(mapOf("started" to false, "reason" to "needs_overlay_permission"))
      return
    }

    // Android 13+ 알림 권한: 포그라운드 서비스는 반드시 알림이 필요하므로,
    // 미허용 상태에서 startForeground가 크래시/예외가 날 수 있어 먼저 확보한다.
    if (Build.VERSION.SDK_INT >= 33) {
      val granted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        ActivityCompat.requestPermissions(
          this,
          arrayOf(Manifest.permission.POST_NOTIFICATIONS),
          1001
        )
        result.success(mapOf("started" to false, "reason" to "needs_notification_permission"))
        return
      }
    }

    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val captureIntent =
      if (Build.VERSION.SDK_INT >= 34) {
        try {
          mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } catch (_: Throwable) {
          mgr.createScreenCaptureIntent()
        }
      } else {
        mgr.createScreenCaptureIntent()
      }
    pendingStartResult = result
    @Suppress("DEPRECATION")
    startActivityForResult(captureIntent, REQ_MEDIA_PROJECTION)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQ_MEDIA_PROJECTION) return

    val methodResult = pendingStartResult
    pendingStartResult = null
    val fromOverlay = pendingStartFromOverlay
    pendingStartFromOverlay = false

    if (methodResult == null && !fromOverlay) return

    if (resultCode != RESULT_OK || data == null) {
      methodResult?.success(mapOf("started" to false, "reason" to "media_projection_denied"))
      return
    }

    val intent = ScreenCaptureService.startIntent(this, resultCode, data)
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    methodResult?.success(mapOf("started" to true))
  }
}
