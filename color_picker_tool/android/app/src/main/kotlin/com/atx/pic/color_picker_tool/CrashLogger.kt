package com.atx.pic.color_picker_tool

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 런타임 크래시 분석용: 마지막 크래시 스택을 앱 전용 external files에 저장한다.
 * - 사용자가 "앱 실행 시 팅김"을 겪을 때, logcat 없이도 원인 스택을 회수 가능
 * - 예외를 "막을" 수는 없지만, 다음 수정에 필요한 근거를 남긴다.
 */
object CrashLogger {
  private val installed = AtomicBoolean(false)

  fun install(context: Context, who: String) {
    if (!installed.compareAndSet(false, true)) return
    try {
      val prev = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try {
          writeCrash(context, who, t, e)
        } catch (_: Throwable) {}
        try {
          prev?.uncaughtException(t, e)
        } catch (_: Throwable) {}
      }
    } catch (_: Throwable) {}
  }

  private fun writeCrash(context: Context, who: String, thread: Thread, e: Throwable) {
    val dir = try { context.getExternalFilesDir(null) } catch (_: Throwable) { null }
    val f = File(dir ?: context.filesDir, "autoclick_crash_last.txt")

    val sw = StringWriter()
    e.printStackTrace(PrintWriter(sw))
    val stack = sw.toString()

    val header =
      buildString {
        appendLine("=== AutoClickZzang Crash ===")
        appendLine("who=$who")
        appendLine("timeMs=${System.currentTimeMillis()}")
        appendLine("thread=${thread.name}")
        appendLine("sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}")
        appendLine("appId=${context.packageName}")
        appendLine("--- stack ---")
      }

    try {
      f.parentFile?.mkdirs()
      f.writeText(header + stack, Charsets.UTF_8)
    } catch (_: Throwable) {}

    try {
      Log.e("CrashLogger", "crash saved to ${f.absolutePath}")
    } catch (_: Throwable) {}
  }
}

