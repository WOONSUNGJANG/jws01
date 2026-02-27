package com.atx.pic.color_picker_tool

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Flutter 초기화면을 띄우지 않고(MediaProjection 권한 다이얼로그만),
 * 화면공유(캡처) 권한을 요청하기 위한 "투명" Activity.
 *
 * Manifest에서 `@style/NoUiTransparentTheme`를 사용한다.
 */
class ScreenSharePermissionActivity : Activity() {
  private val REQ_MEDIA_PROJECTION = 7001
  private val REQ_NOTIF = 7002

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      overridePendingTransition(0, 0)
    } catch (_: Throwable) {}

    // 오버레이 권한이 없으면 먼저 설정으로 유도(서비스가 권한 없이 동작 불가)
    if (!Settings.canDrawOverlays(this)) {
      try {
        val uri = android.net.Uri.parse("package:$packageName")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
      } catch (_: Throwable) {}
      finishNoAnim()
      return
    }

    // Android 13+ 알림 권한: FGS 알림 표시를 위해 필요
    if (Build.VERSION.SDK_INT >= 33) {
      val granted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        try {
          ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIF
          )
          return
        } catch (_: Throwable) {
          // ignore
        }
      }
    }

    startProjectionRequest()
  }

  private fun finishNoAnim() {
    try {
      finish()
      overridePendingTransition(0, 0)
    } catch (_: Throwable) {}
  }

  private fun startProjectionRequest() {
    try {
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
      @Suppress("DEPRECATION")
      startActivityForResult(captureIntent, REQ_MEDIA_PROJECTION)
    } catch (_: Throwable) {
      finishNoAnim()
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQ_MEDIA_PROJECTION) return

    if (resultCode == RESULT_OK && data != null) {
      try {
        val i = ScreenCaptureService.startIntent(this, resultCode, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
      } catch (_: Throwable) {}
    }
    finishNoAnim()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQ_NOTIF) return
    val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
    if (granted) startProjectionRequest() else finishNoAnim()
  }
}

