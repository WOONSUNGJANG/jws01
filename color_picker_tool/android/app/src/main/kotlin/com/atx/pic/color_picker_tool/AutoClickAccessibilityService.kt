package com.atx.pic.color_picker_tool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.Configuration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

class AutoClickAccessibilityService : AccessibilityService() {
  private var gestureDispatchThread: HandlerThread? = null
  private var gestureDispatchHandler: Handler? = null
  private var gestureCbThread: HandlerThread? = null
  private var gestureCbHandler: Handler? = null

  // -------- 메뉴 툴바(TYPE_ACCESSIBILITY_OVERLAY) --------
  private val uiH by lazy { Handler(Looper.getMainLooper()) }
  private var toolbarRoot: View? = null
  private var toolbarLp: WindowManager.LayoutParams? = null
  private var toolbarCollapsed: Boolean = false

  private var btnPlay: ImageButton? = null
  private var btnPlus: ImageButton? = null
  private var btnEdit: ImageButton? = null
  private var btnTrash: ImageButton? = null
  private var btnObjects: ImageButton? = null
  private var btnSettings: ImageButton? = null
  private var btnClose: ImageButton? = null
  private var tvHandle: TextView? = null
  private var tvFav1: ImageButton? = null
  private var tvFav2: ImageButton? = null
  private var tvFav3: ImageButton? = null
  private var row1: LinearLayout? = null
  private var row2: LinearLayout? = null

  companion object {
    private const val TAG = "AutoClickAS"

    @Volatile
    private var instance: AutoClickAccessibilityService? = null

    // 최근 foreground 이벤트 패키지(타겟 앱이 실제로 전면인지 진단용)
    @Volatile
    private var lastEventPackage: String? = null

    // 최근 활성(전면) 윈도우 패키지(진단용)
    @Volatile
    private var lastActiveWindowPackage: String? = null

    // dispatchGesture 결과(완료/취소) 통계
    private val dispatchedCount = AtomicLong(0)
    private val completedCount = AtomicLong(0)
    private val cancelledCount = AtomicLong(0)
    private val immediateFailCount = AtomicLong(0) // dispatchGesture가 false/throw 등 즉시 실패
    private val timeoutCount = AtomicLong(0)

    @Volatile
    private var lastFailReason: String? = null

    private val lastAction = AtomicReference<String?>()

    private val gestureMutex = Any()

    // (메모리/성능) 제스처마다 Handler(Looper.getMainLooper())를 새로 만들지 않도록 재사용
    private val mainH by lazy { Handler(Looper.getMainLooper()) }

    private val pending = ConcurrentHashMap<Long, Long>() // id -> startedAtMs
    private val seq = AtomicLong(0)

    private data class GestureResult(
      val dispatchOk: Boolean,
      val completed: Boolean,
      val cancelled: Boolean,
      val timedOut: Boolean,
    )

    fun isReady(): Boolean = instance != null

    @Volatile
    private var pendingShowToolbar: Boolean = false

    // 설정창/모달이 여러 개 겹칠 수 있어 ref-count로 숨김/복구를 관리한다.
    private val toolbarHideCount = AtomicInteger(0)

    /**
     * (안전장치) 숨김 카운트가 꼬였을 때도 "무조건 표시"하기 위한 강제 show.
     * 시작(색상/기본) 직후에는 사용자 기대가 "바로 뜸"이므로 count를 0으로 리셋한다.
     */
    fun requestForceShowToolbar() {
      try {
        toolbarHideCount.set(0)
      } catch (_: Throwable) {}
      pendingShowToolbar = false
      val svc = instance
      if (svc != null) {
        try {
          svc.uiH.post { try { svc.showToolbarOverlay(full = true) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
      } else {
        pendingShowToolbar = true
      }
    }

    fun requestShowToolbar() {
      // show는 "숨김 해제(pop)" 역할: 여러 모달이 열리면 count가 2,3...이 될 수 있다.
      // count가 0이 될 때만 실제로 표시한다.
      while (true) {
        val cur = toolbarHideCount.get()
        if (cur <= 0) {
          toolbarHideCount.set(0)
          break
        }
        if (toolbarHideCount.compareAndSet(cur, cur - 1)) break
      }

      if (toolbarHideCount.get() > 0) {
        // 아직 다른 모달이 열려있음 -> 표시 예약만
        pendingShowToolbar = true
        return
      }
      val svc = instance
      if (svc != null) {
        try {
          svc.uiH.post { try { svc.showToolbarOverlay(full = true) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
      } else {
        pendingShowToolbar = true
      }
    }

    fun resetGestureStats() {
      dispatchedCount.set(0)
      completedCount.set(0)
      cancelledCount.set(0)
      immediateFailCount.set(0)
      timeoutCount.set(0)
      lastFailReason = null
      pending.clear()
      seq.set(0)
    }

    data class GestureStats(
      val dispatched: Long,
      val completed: Long,
      val cancelled: Long,
      val immediateFail: Long,
      val timeout: Long,
      val lastFailReason: String?,
    )

    fun getGestureStats(): GestureStats {
      return GestureStats(
        dispatched = dispatchedCount.get(),
        completed = completedCount.get(),
        cancelled = cancelledCount.get(),
        immediateFail = immediateFailCount.get(),
        timeout = timeoutCount.get(),
        lastFailReason = lastFailReason,
      )
    }

    fun getLastAction(): String? = lastAction.get()

    fun getLastEventPackage(): String? = lastEventPackage

    fun getLastActiveWindowPackage(): String? = lastActiveWindowPackage

    private fun dispatchWithStats(
      svc: AutoClickAccessibilityService,
      gesture: GestureDescription,
    ): Boolean {
      synchronized(gestureMutex) {
        // (진단) 현재 활성 윈도우 패키지 기록
        try {
          val pkg = svc.rootInActiveWindow?.packageName?.toString()
          if (!pkg.isNullOrBlank()) lastActiveWindowPackage = pkg
        } catch (_: Throwable) {}

        val id = seq.incrementAndGet()
        dispatchedCount.incrementAndGet()
        pending[id] = android.os.SystemClock.uptimeMillis()

        val dispatchH = svc.gestureDispatchHandler
        // 일부 기기/OS에서 dispatchGesture를 메인(UI)에서 호출하지 않으면
        // onCancelled가 즉시 반복되는 사례가 있어, 호출/콜백 모두 UI로 고정한다.
        val uiH = mainH
        val cbH = mainH

        // 결과를 기다릴 수 있게 latch를 사용(대기는 dispatch thread에서만 수행)
        val done = java.util.concurrent.CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference<GestureResult?>()

        val runDispatch = Runnable {
          var dispatchOk = true
          var completed = false
          var cancelled = false
          var timedOut = false
          try {
            val cb =
              object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                  pending.remove(id)
                  completedCount.incrementAndGet()
                  completed = true
                  // (중요) await를 깨우기 전에 결과를 먼저 기록(레이스 방지)
                  resultRef.set(GestureResult(dispatchOk = true, completed = true, cancelled = false, timedOut = false))
                  done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                  pending.remove(id)
                  cancelledCount.incrementAndGet()
                  lastFailReason = "cancelled"
                  cancelled = true
                  // (중요) await를 깨우기 전에 결과를 먼저 기록(레이스 방지)
                  resultRef.set(GestureResult(dispatchOk = true, completed = false, cancelled = true, timedOut = false))
                  done.countDown()
                }
              }

            // dispatchGesture는 UI 스레드에서 호출
            val dispatchReturned = java.util.concurrent.CountDownLatch(1)
            val okRef = java.util.concurrent.atomic.AtomicBoolean(true)
            uiH.post {
              try {
                okRef.set(svc.dispatchGesture(gesture, cb, cbH))
              } catch (_: Throwable) {
                okRef.set(false)
              } finally {
                dispatchReturned.countDown()
              }
            }
            // UI 포스트 자체가 밀리면 실패로 처리
            val postedOk = dispatchReturned.await(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            dispatchOk = postedOk && okRef.get()
            if (!dispatchOk) {
              pending.remove(id)
              immediateFailCount.incrementAndGet()
              lastFailReason =
                when {
                  !postedOk -> "dispatch_post_timeout"
                  !okRef.get() -> "dispatch_returned_false"
                  else -> "dispatchGesture=false"
                }
              // (중요) await를 깨우기 전에 결과를 먼저 기록(레이스 방지)
              resultRef.set(GestureResult(dispatchOk = false, completed = false, cancelled = false, timedOut = false))
              done.countDown()
            }

            // (중요) 이전 제스처가 끝나기 전에 다음을 dispatch하면 cancel이 자주 나므로,
            // dispatch thread에서 완료/취소까지 기다려 직렬화한다.
            if (dispatchOk) {
              val waited = done.await(4500L, java.util.concurrent.TimeUnit.MILLISECONDS)
              if (!waited) {
                if (pending.remove(id) != null) {
                  timeoutCount.incrementAndGet()
                  lastFailReason = "timeout"
                }
                timedOut = true
                // (중요) await를 깨우기 전에 결과를 먼저 기록(레이스 방지)
                resultRef.set(GestureResult(dispatchOk = true, completed = false, cancelled = false, timedOut = true))
                done.countDown()
              }
            }
          } catch (t: Throwable) {
            pending.remove(id)
            immediateFailCount.incrementAndGet()
            lastFailReason = t.javaClass.simpleName
            Log.e(TAG, "dispatchGesture failed", t)
            dispatchOk = false
            // (중요) await를 깨우기 전에 결과를 먼저 기록(레이스 방지)
            resultRef.set(GestureResult(dispatchOk = false, completed = false, cancelled = false, timedOut = false))
            done.countDown()
          } finally {
            // callback 경로에서 이미 set 했을 수 있음(레이스 방지)
            if (resultRef.get() == null) {
              resultRef.set(GestureResult(dispatchOk, completed, cancelled, timedOut))
            }
          }
        }

        if (dispatchH != null) {
          // UI 스레드에서는 즉시 반환(멈춤 방지), 백그라운드에서는 결과를 기다린다.
          dispatchH.post(runDispatch)
          if (Looper.myLooper() == Looper.getMainLooper()) return true

          // 결과를 기다림(매크로 스레드)
          try {
            val waited = done.await(5000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!waited) {
              lastFailReason = "caller_wait_timeout"
              timeoutCount.incrementAndGet()
              pending.remove(id)
              return false
            }
          } catch (_: InterruptedException) {
            lastFailReason = "InterruptedException"
            return false
          }
          val r = resultRef.get()
          if (r == null && lastFailReason == null) {
            // latch가 먼저 풀리고 결과 set이 늦는 레이스를 방지(진단용)
            lastFailReason = "result_null_race"
          }
          return r?.dispatchOk == true && r.cancelled == false && r.timedOut == false
        }

        // fallback: dispatch thread가 없으면 현재 스레드에서 실행(권장 아님)
        runDispatch.run()
        val r = resultRef.get()
        if (r == null && lastFailReason == null) lastFailReason = "result_null_race"
        return r?.dispatchOk == true && r.cancelled == false && r.timedOut == false
      }
    }

    fun click(screenX: Int, screenY: Int, pressMs: Long = 90L, delayMs: Long = 0L): Boolean {
      val svc =
        instance ?: run {
          immediateFailCount.incrementAndGet()
          lastFailReason = "svc=null"
          return false
        }
      if (Build.VERSION.SDK_INT < 24) {
        immediateFailCount.incrementAndGet()
        lastFailReason = "sdk<24"
        return false
      }

      return try {
        lastAction.set("click($screenX,$screenY) press=$pressMs delay=$delayMs")
        // 일부 앱/기기에서 "0 길이 path" 탭이 무시되는 경우가 있어,
        // 1px 미세 이동으로 path 길이를 보장한다.
        val x2 = if (screenX > 0) screenX - 1 else screenX + 1
        val p =
          Path().apply {
            moveTo(screenX.toFloat(), screenY.toFloat())
            lineTo(x2.toFloat(), screenY.toFloat())
          }
        val stroke = GestureDescription.StrokeDescription(p, delayMs, pressMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchWithStats(svc, gesture)
        if (!ok && lastFailReason == null) lastFailReason = "false_unknown"
        ok
      } catch (t: Throwable) {
        immediateFailCount.incrementAndGet()
        lastFailReason = t.javaClass.simpleName
        Log.e(TAG, "dispatchGesture failed", t)
        false
      }
    }

    fun swipe(
      fromX: Int,
      fromY: Int,
      toX: Int,
      toY: Int,
      durationMs: Long = 200L,
      delayMs: Long = 0L,
    ): Boolean {
      val svc =
        instance ?: run {
          immediateFailCount.incrementAndGet()
          lastFailReason = "svc=null"
          return false
        }
      if (Build.VERSION.SDK_INT < 24) {
        immediateFailCount.incrementAndGet()
        lastFailReason = "sdk<24"
        return false
      }

      return try {
        // 일부 앱/기기에서 0길이 swipe가 무시될 수 있어 최소 1px 이동 보장
        val fx = fromX
        val fy = fromY
        var tx = toX
        var ty = toY
        if (tx == fx && ty == fy) {
          tx = if (fx > 0) fx - 1 else fx + 1
        }
        lastAction.set("swipe($fx,$fy->$tx,$ty) dur=$durationMs delay=$delayMs")
        val p =
          Path().apply {
            moveTo(fx.toFloat(), fy.toFloat())
            lineTo(tx.toFloat(), ty.toFloat())
          }
        val stroke = GestureDescription.StrokeDescription(p, delayMs, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchWithStats(svc, gesture)
        if (!ok && lastFailReason == null) lastFailReason = "false_unknown"
        ok
      } catch (t: Throwable) {
        immediateFailCount.incrementAndGet()
        lastFailReason = t.javaClass.simpleName
        Log.e(TAG, "dispatchGesture swipe failed", t)
        false
      }
    }

    fun swipePathPx(
      points: List<Pair<Int, Int>>,
      moveDurationMs: Long = 300L,
      holdMs: Long = 0L,
      delayMs: Long = 0L,
    ): Boolean {
      val svc =
        instance ?: run {
          immediateFailCount.incrementAndGet()
          lastFailReason = "svc=null"
          return false
        }
      if (Build.VERSION.SDK_INT < 24) {
        immediateFailCount.incrementAndGet()
        lastFailReason = "sdk<24"
        return false
      }
      if (points.size < 2) return false

      return try {
        // 0길이/중복점 경로는 일부 앱에서 무시될 수 있어 정규화한다.
        val norm = ArrayList<Pair<Int, Int>>(points.size)
        for (pt in points) {
          val last = norm.lastOrNull()
          if (last == null || last.first != pt.first || last.second != pt.second) {
            norm.add(pt)
          }
        }
        if (norm.size < 2) {
          val p0 = points.first()
          val p1 = Pair(if (p0.first > 0) p0.first - 1 else p0.first + 1, p0.second)
          norm.clear()
          norm.add(p0)
          norm.add(p1)
        }
        // 마지막 구간이 0이면 1px 보정
        val a = norm[norm.size - 2]
        val b = norm[norm.size - 1]
        if (a.first == b.first && a.second == b.second) {
          norm[norm.size - 1] = Pair(if (b.first > 0) b.first - 1 else b.first + 1, b.second)
        }

        lastAction.set("swipePath(${norm.size}) dur=$moveDurationMs hold=$holdMs delay=$delayMs")
        val p =
          Path().apply {
            moveTo(norm[0].first.toFloat(), norm[0].second.toFloat())
            for (i in 1 until norm.size) {
              lineTo(norm[i].first.toFloat(), norm[i].second.toFloat())
            }
          }

        // 일부 기기에서 continueStroke(hold)가 즉시 cancelled 되는 사례가 있어,
        // 단일 stroke로 안정화(hold는 duration에 합산)
        val moveDur = max(80L, moveDurationMs)
        val holdDur = holdMs.coerceAtLeast(0L)
        val totalDur = (moveDur + holdDur).coerceAtLeast(80L)
        val stroke = GestureDescription.StrokeDescription(p, delayMs, totalDur, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchWithStats(svc, gesture)
        if (!ok && lastFailReason == null) lastFailReason = "false_unknown"
        ok
      } catch (t: Throwable) {
        immediateFailCount.incrementAndGet()
        lastFailReason = t.javaClass.simpleName
        Log.e(TAG, "dispatchGesture swipePath failed", t)
        false
      }
    }

    fun requestHideToolbar() {
      toolbarHideCount.incrementAndGet()
      val svc = instance
      if (svc != null) {
        try {
          svc.uiH.post { try { svc.removeToolbarOverlay() } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
      }
    }

    fun requestRefreshToolbarLayout() {
      val svc = instance ?: return
      try {
        svc.uiH.post {
          try {
            svc.updateToolbarLayout()
            svc.applyToolbarState()
            svc.clampToolbarIfNeeded()
            val root = svc.toolbarRoot
            val lp = svc.toolbarLp
            if (root != null && lp != null) {
              (svc.getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(root, lp)
            }
          } catch (_: Throwable) {}
        }
      } catch (_: Throwable) {}
    }

    fun requestSyncMoveHandleTint() {
      val svc = instance ?: return
      try {
        svc.uiH.post { try { svc.syncMoveHandleTintFromPrefs() } catch (_: Throwable) {} }
      } catch (_: Throwable) {}
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    try { CrashLogger.install(this, "AutoClickAccessibilityService") } catch (_: Throwable) {}
    try {
      if (gestureDispatchThread == null) {
        gestureDispatchThread = HandlerThread("GestureDispatch").apply { start() }
        gestureDispatchHandler = Handler(gestureDispatchThread!!.looper)
      }
      if (gestureCbThread == null) {
        gestureCbThread = HandlerThread("GestureCb").apply { start() }
        gestureCbHandler = Handler(gestureCbThread!!.looper)
      }
    } catch (_: Throwable) {}
    Log.i(TAG, "Accessibility service connected")

    // (요청) 시작 시 툴바는 기본 숨김.
    // 사용자가 "색상 시작/기본 시작"을 누를 때만 requestShowToolbar()로 표시한다.
    try {
      if (pendingShowToolbar && toolbarHideCount.get() <= 0) {
        pendingShowToolbar = false
        uiH.post { try { showToolbarOverlay(full = true) } catch (_: Throwable) {} }
      }
    } catch (_: Throwable) {}
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    try {
      val pkg = event?.packageName?.toString()
      if (!pkg.isNullOrBlank()) {
        lastEventPackage = pkg
      }
    } catch (_: Throwable) {}
  }

  override fun onInterrupt() {
    // no-op
  }

  override fun onDestroy() {
    instance = null
    try {
      removeToolbarOverlay()
    } catch (_: Throwable) {}
    try {
      gestureDispatchThread?.quitSafely()
    } catch (_: Throwable) {}
    gestureDispatchThread = null
    gestureDispatchHandler = null
    try {
      gestureCbThread?.quitSafely()
    } catch (_: Throwable) {}
    gestureCbThread = null
    gestureCbHandler = null
    super.onDestroy()
  }

  private fun flutterPrefs(): SharedPreferences {
    return getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
  }

  private fun syncMoveHandleTintFromPrefs() {
    try {
      val ready = flutterPrefs().getBoolean("flutter.capture_ready", false)
      val c = if (ready) Color.parseColor("#EF4444") else Color.parseColor("#111827")
      tvHandle?.setTextColor(c)
    } catch (_: Throwable) {}
  }

  private fun syncToggleIconsFromPrefs() {
    try {
      val p = flutterPrefs()
      val running = p.getBoolean("flutter.macro_running", false)
      val editOn = p.getBoolean("flutter.marker_edit_mode", false)
      val objOn = p.getBoolean("flutter.objects_visible", true)
      // tag도 같이 맞춰야 "즉시 토글(UX)" 로직이 뒤틀리지 않는다.
      try { btnPlay?.tag = running } catch (_: Throwable) {}
      try { btnEdit?.tag = editOn } catch (_: Throwable) {}
      try { btnObjects?.tag = objOn } catch (_: Throwable) {}
      btnPlay?.setImageResource(if (running) android.R.drawable.ic_media_pause else R.drawable.cp_play)
      btnEdit?.setImageResource(if (editOn) R.drawable.cp_edit_on else R.drawable.cp_edit_off)
      btnObjects?.setImageResource(if (objOn) R.drawable.cp_eye_open else R.drawable.cp_eye_off)
    } catch (_: Throwable) {}
  }

  private fun isMacroRunningForUi(): Boolean {
    val tag = (btnPlay?.tag as? Boolean)
    if (tag != null) return tag
    return try { flutterPrefs().getBoolean("flutter.macro_running", false) } catch (_: Throwable) { false }
  }

  private data class InsetsPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

  private fun getUsableRectPx(): android.graphics.Rect {
    val wmLocal = getSystemService(WINDOW_SERVICE) as WindowManager
    val (w, h) =
      if (Build.VERSION.SDK_INT >= 30) {
        val b = wmLocal.maximumWindowMetrics.bounds
        b.width() to b.height()
      } else {
        val dm = resources.displayMetrics
        dm.widthPixels to dm.heightPixels
      }

    val insets =
      if (Build.VERSION.SDK_INT >= 30) {
        val i =
          wmLocal.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
          )
        InsetsPx(i.left, i.top, i.right, i.bottom)
      } else {
        val res = resources
        val topId = res.getIdentifier("status_bar_height", "dimen", "android")
        val bottomId = res.getIdentifier("navigation_bar_height", "dimen", "android")
        val rightId = res.getIdentifier("navigation_bar_width", "dimen", "android")
        val top = if (topId > 0) res.getDimensionPixelSize(topId) else 0
        val bottom = if (bottomId > 0) res.getDimensionPixelSize(bottomId) else 0
        val right = if (rightId > 0) res.getDimensionPixelSize(rightId) else 0
        val landscape = res.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        InsetsPx(0, top, if (landscape) right else 0, if (landscape) 0 else bottom)
      }

    val left = insets.left
    val top = insets.top
    val right = (w - insets.right).coerceAtLeast(left + 1)
    val bottom = (h - insets.bottom).coerceAtLeast(top + 1)
    return android.graphics.Rect(left, top, right, bottom)
  }

  private fun dpToPx(dp: Float): Int {
    return (dp * resources.displayMetrics.density).toInt()
  }

  private fun isLandscapeNow(): Boolean {
    return resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  }

  private fun toolbarItemsInOrder(): List<View> {
    val out = ArrayList<View>(8)
    val allowFavsInCollapsed =
      try { flutterPrefs().getBoolean("flutter.fav_add_to_collapsed_toolbar", false) } catch (_: Throwable) { false }
    val running = isMacroRunningForUi()
    tvHandle?.let { out.add(it) }
    btnPlay?.let { out.add(it) }
    if (toolbarCollapsed) {
      if (allowFavsInCollapsed && !running) {
        tvFav1?.let { out.add(it) }
        tvFav2?.let { out.add(it) }
        tvFav3?.let { out.add(it) }
      }
      return out
    }
    // (기본) 전체 모드에서는 기존 버튼들 표시
    btnPlus?.let { out.add(it) }
    btnEdit?.let { out.add(it) }
    btnTrash?.let { out.add(it) }
    btnObjects?.let { out.add(it) }
    btnSettings?.let { out.add(it) }
    btnClose?.let { out.add(it) }
    return out
  }

  private fun syncFavButtonsFromPrefs() {
    fun applyOne(v: ImageButton?, which: Int) {
      if (v == null) return
      val name = try { flutterPrefs().getString("flutter.fav$which", "")?.trim().orEmpty() } catch (_: Throwable) { "" }
      val enabled = name.isNotBlank()
      try { v.isEnabled = enabled } catch (_: Throwable) {}
      try { v.alpha = if (enabled) 1.0f else 0.25f } catch (_: Throwable) {}
    }
    applyOne(tvFav1, 1)
    applyOne(tvFav2, 2)
    applyOne(tvFav3, 3)
  }

  private fun sendToScreenService(action: String) {
    try {
      startService(Intent(this, ScreenCaptureService::class.java).apply { this.action = action })
    } catch (_: Throwable) {}
  }

  private fun showToolbarOverlay(full: Boolean) {
    val wmLocal = getSystemService(WINDOW_SERVICE) as WindowManager
    if (toolbarRoot != null) {
      toolbarCollapsed = !full
      applyToolbarState()
      clampToolbarIfNeeded()
      try {
        wmLocal.updateViewLayout(toolbarRoot, toolbarLp)
      } catch (_: Throwable) {}
      return
    }

    // root container (세로 1열)
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.overlay_toolbar_bg)
        isClickable = true
        isFocusable = false
        // (요청) 버튼 간격을 더 촘촘하게
        setPadding(dpToPx(2f), dpToPx(2f), dpToPx(2f), dpToPx(2f))
      }

    fun makeBtn(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
      return ImageButton(this).apply {
        setImageResource(iconRes)
        contentDescription = desc
        // (요청) 버튼 사각 테두리/박스 제거
        background = null
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(0, 0, 0, 0)
        imageTintList = null
        setOnClickListener { onClick() }
      }
    }

    // 드래그 핸들("≡") - 탭: 최소화 토글, 드래그: 이동
    val handle =
      TextView(this).apply {
        text = "≡"
        setTextColor(Color.parseColor("#111827"))
        textSize = 18f
        gravity = Gravity.CENTER
        // (요청) 핸들도 사각 테두리 제거
        background = null
        isClickable = true
      }
    tvHandle = handle
    // 캡처(화면저장) 권한 상태에 따라 이동 핸들 색상 반영
    syncMoveHandleTintFromPrefs()

    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    handle.setOnTouchListener(
      object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
          val lp = toolbarLp ?: return false
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              downX = event.rawX
              downY = event.rawY
              startX = lp.x
              startY = lp.y
              dragging = false
              return true
            }
            MotionEvent.ACTION_MOVE -> {
              val dxF = event.rawX - downX
              val dyF = event.rawY - downY
              if (!dragging) {
                if (kotlin.math.abs(dxF) < touchSlop && kotlin.math.abs(dyF) < touchSlop) return true
                dragging = true
              }
              lp.x = startX + dxF.toInt()
              lp.y = startY + dyF.toInt()
              try {
                wmLocal.updateViewLayout(root, lp)
              } catch (_: Throwable) {}
              return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
              val wasDragging = dragging
              dragging = false
              if (!wasDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                // 탭: 최소화 토글
                val wasCollapsed = toolbarCollapsed
                toolbarCollapsed = !toolbarCollapsed
                // 레이아웃/표시 갱신
                try {
                  updateToolbarLayout()
                  applyToolbarState()
                  try { root.requestLayout() } catch (_: Throwable) {}
                } catch (_: Throwable) {}

                // (요청) 작게 했다가 "크게"로 돌아올 때는 항상 좌측벽/세로중앙 정렬
                if (wasCollapsed && !toolbarCollapsed) {
                  try {
                    // 측정 타이밍을 위해 post로 1번 더 스냅(정확한 높이로 중앙 정렬)
                    root.post {
                      try {
                        snapToolbarToLeftCenter()
                        clampToolbarIfNeeded()
                      } catch (_: Throwable) {}
                    }
                  } catch (_: Throwable) {
                    try { snapToolbarToLeftCenter() } catch (_: Throwable) {}
                    try { clampToolbarIfNeeded() } catch (_: Throwable) {}
                  }
                } else {
                  try { clampToolbarIfNeeded() } catch (_: Throwable) {}
                }

                // 상태 저장(다음 실행/회전에서도 동일)
                try {
                  val lp0 = toolbarLp
                  val e = flutterPrefs().edit().putBoolean("flutter.as_toolbar_collapsed", toolbarCollapsed)
                  if (lp0 != null) {
                    e.putInt("flutter.as_toolbar_x", lp0.x).putInt("flutter.as_toolbar_y", lp0.y)
                  }
                  e.apply()
                } catch (_: Throwable) {}

                try { wmLocal.updateViewLayout(root, toolbarLp) } catch (_: Throwable) {}
              }
              return true
            }
          }
          return false
        }
      }
    )

    // 버튼들(현 메뉴 기능 유지)
    btnPlay =
      makeBtn(R.drawable.cp_play, "play") {
        // (요청) 재생 버튼 클릭 시마다 캡처(화면저장) 권한 상태를 다시 확인하여
        // 이동(드래그) 핸들 색상을 즉시 반영
        try { syncMoveHandleTintFromPrefs() } catch (_: Throwable) {}
        sendToScreenService(ScreenCaptureService.ACTION_TOOL_TOGGLE_MACRO)
        // 아이콘은 즉시 토글(실제 상태는 서비스에서 반영되지만 UX 지연 방지)
        updateToolbarIconsOptimistic()
      }.apply {
        setOnLongClickListener {
          sendToScreenService(ScreenCaptureService.ACTION_TOOL_RESTART_PROJECTION)
          true
        }
      }

    // 즐겨찾기 1~3 (요청: "메뉴에추가" 체크 시, 축소 상태에서도 표시)
    tvFav1 = makeBtn(R.drawable.cp_fav_m1, "fav1") { sendToScreenService(ScreenCaptureService.ACTION_TOOL_OPEN_FAV1) }
    tvFav2 = makeBtn(R.drawable.cp_fav_m2, "fav2") { sendToScreenService(ScreenCaptureService.ACTION_TOOL_OPEN_FAV2) }
    tvFav3 = makeBtn(R.drawable.cp_fav_m3, "fav3") { sendToScreenService(ScreenCaptureService.ACTION_TOOL_OPEN_FAV3) }
    btnPlus = makeBtn(R.drawable.cp_plus, "plus") { sendToScreenService(ScreenCaptureService.ACTION_TOOL_ADD_MARKER) }
    btnEdit =
      makeBtn(R.drawable.cp_edit_off, "edit") {
        sendToScreenService(ScreenCaptureService.ACTION_TOOL_TOGGLE_EDIT_MODE)
        // 아이콘 즉시 토글(UX), 이후 prefs값으로 동기화(정확)
        try {
          val cur = (btnEdit?.tag as? Boolean) ?: false
          val next = !cur
          btnEdit?.tag = next
          btnEdit?.setImageResource(if (next) R.drawable.cp_edit_on else R.drawable.cp_edit_off)
        } catch (_: Throwable) {}
        uiH.postDelayed({ syncToggleIconsFromPrefs() }, 250L)
      }
    btnTrash = makeBtn(R.drawable.cp_trash, "trash") { sendToScreenService(ScreenCaptureService.ACTION_TOOL_DELETE_ALL_MARKERS) }
    btnObjects =
      makeBtn(R.drawable.cp_eye_open, "objects") {
        sendToScreenService(ScreenCaptureService.ACTION_TOOL_TOGGLE_OBJECTS_VISIBLE)
        try {
          val cur = (btnObjects?.tag as? Boolean) ?: true
          val next = !cur
          btnObjects?.tag = next
          btnObjects?.setImageResource(if (next) R.drawable.cp_eye_open else R.drawable.cp_eye_off)
        } catch (_: Throwable) {}
        uiH.postDelayed({ syncToggleIconsFromPrefs() }, 250L)
      }
    btnSettings = makeBtn(R.drawable.cp_gear, "settings") { sendToScreenService(ScreenCaptureService.ACTION_OPEN_PANEL) }
    btnClose =
      makeBtn(R.drawable.cp_close, "close") {
        // (요청) 7번째 "종료"는 서비스 종료뿐 아니라 툴바도 즉시 내려야 한다.
        try {
          removeToolbarOverlay()
        } catch (_: Throwable) {}
        sendToScreenService(ScreenCaptureService.ACTION_STOP)
      }

    // (요청) 가로모드에서는 1줄/2줄(4개×2줄)로 자동 전환해야 하므로
    // 버튼은 row 컨테이너에 재배치한다(updateToolbarLayout에서 구성).
    row1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(row1)
    root.addView(row2)

    val lp =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      )
    // (중요) "좌측 벽 중앙" 요구는 RTL에서도 물리적 LEFT 기준이어야 한다.
    // Gravity.START는 RTL(ar)에서 RIGHT로 해석되어 툴바가 오른쪽에 붙을 수 있으므로,
    // 여기서는 항상 LEFT 고정으로 둔다.
    lp.gravity = Gravity.TOP or Gravity.LEFT

    // 초기 위치/상태 복원
    val p = flutterPrefs()
    val x = p.getInt("flutter.as_toolbar_x", 0)
    val y = p.getInt("flutter.as_toolbar_y", 0)
    toolbarCollapsed = if (full) false else p.getBoolean("flutter.as_toolbar_collapsed", false)
    lp.x = x
    lp.y = y

    toolbarRoot = root
    toolbarLp = lp

    // 크기 산정(usable 기준)
    updateToolbarLayout()
    applyToolbarState()
    clampToolbarIfNeeded()
    // 초기 아이콘 동기화
    syncToggleIconsFromPrefs()
    // 초기 즐겨 상태 동기화
    syncFavButtonsFromPrefs()

    try {
      wmLocal.addView(root, lp)
    } catch (t: Throwable) {
      Log.e(TAG, "addView(toolbar) failed", t)
      toolbarRoot = null
      toolbarLp = null
    }
  }

  private fun updateToolbarLayout() {
    val root = toolbarRoot as? LinearLayout ?: return
    val r1 = row1 ?: return
    val r2 = row2 ?: return

    val usable = getUsableRectPx()
    // 가로/세로 모두 "세로 1열" 배치로 고정(요청)
    val isLandscape = isLandscapeNow()

    // (요청) "툴바가로크기(세로유지) %" 값을 기준으로
    // 접근성 메뉴툴바도 가로/세로 크기를 함께 자동 조절한다.
    val scalePct = flutterPrefs().getInt("flutter.toolbar_scale_x_percent", 100).coerceIn(50, 200)
    // (요청) 회전(세로/가로) 시 "크기 변경"이 없도록, 방향에 따른 추가 배율을 적용하지 않는다.
    val scale = (scalePct / 100f).coerceIn(0.50f, 2.00f)

    val gap = (dpToPx(2f) * scale).toInt().coerceIn(dpToPx(1f), dpToPx(10f))
    val pad = (dpToPx(2f) * scale).toInt().coerceIn(dpToPx(1f), dpToPx(12f))
    try {
      root.setPadding(pad, pad, pad, pad)
    } catch (_: Throwable) {}

    val items = toolbarItemsInOrder()
    val itemCount = items.size
    if (itemCount == 0) return

    // (요청) 가로모드에서도 메뉴바를 "세로로 세워서" 좌측 벽 중앙 정렬.
    // 따라서 가로모드용 1줄/2줄(4x2) 배치는 사용하지 않는다.
    val forceGrid4x2 = false

    // 버튼 크기 산정(회전에 따라 변하지 않게 "고정 기준" 사용)
    var btnH = (dpToPx(40f) * scale).toInt().coerceAtLeast(dpToPx(22f))
    var btnW = (btnH * 1.05f).toInt().coerceAtLeast(dpToPx(22f))

    val useTwoRows =
      if (forceGrid4x2) {
        val needOneRowW = (pad * 2) + (8 * btnW) + (7 * gap)
        needOneRowW > usable.width()
      } else {
        false
      }

    // 화면 안에 반드시 들어가야 하는 경우에만(정말 필요할 때만) 축소한다.
    if (forceGrid4x2) {
      val rows = if (useTwoRows) 2 else 1
      val cols = if (useTwoRows) 4 else 8
      val maxW = usable.width().coerceAtLeast(1)
      val maxH = (usable.height() * 0.98f).toInt().coerceAtLeast(1)
      val needW = (pad * 2) + (cols * btnW) + ((cols - 1) * gap)
      val needH = (pad * 2) + (rows * btnH) + ((rows - 1) * gap)
      if (needW > maxW || needH > maxH) {
        val maxBtnW = ((maxW - pad * 2 - (cols - 1) * gap).toFloat() / cols.toFloat()).toInt()
        val maxBtnH = ((maxH - pad * 2 - (rows - 1) * gap).toFloat() / rows.toFloat()).toInt()
        val s = kotlin.math.min(maxBtnW, maxBtnH).coerceAtLeast(dpToPx(18f))
        btnH = s
        btnW = (s * 1.05f).toInt().coerceAtLeast(s)
      }
    } else {
      // 세로 1열(기존): 화면 높이 안으로만 맞춤
      val maxTotalH = (usable.height() * 0.98f).toInt().coerceAtLeast(1)
      val needTotalH = itemCount * btnH + (itemCount - 1) * gap + pad * 2
      if (needTotalH > maxTotalH) {
        val ratio = (maxTotalH.toFloat() / needTotalH.toFloat()).coerceIn(0.2f, 1.0f)
        btnH = (btnH * ratio).toInt().coerceAtLeast(dpToPx(18f))
        btnW = (btnH * 1.05f).toInt().coerceAtLeast(dpToPx(18f))
      }
    }

    // row 구성
    r1.removeAllViews()
    r2.removeAllViews()

    if (forceGrid4x2) {
      // 가로모드: 1줄(8개) 또는 2줄(4+4)
      r1.visibility = View.VISIBLE
      r1.orientation = LinearLayout.HORIZONTAL
      r2.visibility = if (useTwoRows) View.VISIBLE else View.GONE
      r2.orientation = LinearLayout.HORIZONTAL

      fun lpCell(col: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(btnW, btnH).apply {
          leftMargin = if (col == 0) 0 else gap
        }
      }

      for (i in 0 until itemCount) {
        val v = items[i]
        if (useTwoRows) {
          val rowIdx = i / 4
          val colIdx = i % 4
          v.layoutParams = lpCell(colIdx)
          if (rowIdx == 0) r1.addView(v) else r2.addView(v)
        } else {
          v.layoutParams = lpCell(i)
          r1.addView(v)
        }
      }
    } else {
      // 세로모드/가로모드 모두: 1열(세로)
      r1.visibility = View.VISIBLE
      r1.orientation = LinearLayout.VERTICAL
      r2.visibility = View.GONE
      for (i in 0 until itemCount) {
        val v = items[i]
        v.layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply {
          topMargin = if (i == 0) 0 else gap
        }
        r1.addView(v)
      }
    }
  }

  private fun applyToolbarState() {
    val running = isMacroRunningForUi()
    val showFavsInCollapsed =
      try {
        toolbarCollapsed &&
          flutterPrefs().getBoolean("flutter.fav_add_to_collapsed_toolbar", false) &&
          !running
      } catch (_: Throwable) {
        false
      }
    // COLLAPSED: 핸들 + 재생 + (옵션) 즐겨1~3
    for (v in toolbarItemsInOrder()) {
      val isHandle = (v === tvHandle)
      val isPlay = (v === btnPlay)
      val isFav = (v === tvFav1) || (v === tvFav2) || (v === tvFav3)
      v.visibility =
        if (toolbarCollapsed) {
          if (isHandle || isPlay || (showFavsInCollapsed && isFav)) View.VISIBLE else View.GONE
        } else {
          View.VISIBLE
        }
    }
    // 안전: 리스트에서 제외되어도 상태는 맞춰둔다.
    if (!toolbarCollapsed || !showFavsInCollapsed) {
      try { tvFav1?.visibility = View.GONE } catch (_: Throwable) {}
      try { tvFav2?.visibility = View.GONE } catch (_: Throwable) {}
      try { tvFav3?.visibility = View.GONE } catch (_: Throwable) {}
    }
    // 즐겨 슬롯 비어있으면 비활성(흐리게)
    syncFavButtonsFromPrefs()
  }

  private fun clampToolbarIfNeeded() {
    if (toolbarCollapsed) return
    val root = toolbarRoot ?: return
    val lp = toolbarLp ?: return
    val usable = getUsableRectPx()

    val w = (root.width.takeIf { it > 0 } ?: root.measuredWidth).takeIf { it > 0 } ?: dpToPx(56f)
    val h = (root.height.takeIf { it > 0 } ?: root.measuredHeight).takeIf { it > 0 } ?: dpToPx(260f)

    val minX = usable.left
    val maxX = (usable.right - w).coerceAtLeast(minX)
    val minY = usable.top
    val maxY = (usable.bottom - h).coerceAtLeast(minY)

    lp.x = lp.x.coerceIn(minX, maxX)
    lp.y = lp.y.coerceIn(minY, maxY)
    // 저장
    try {
      flutterPrefs().edit()
        .putInt("flutter.as_toolbar_x", lp.x)
        .putInt("flutter.as_toolbar_y", lp.y)
        .putBoolean("flutter.as_toolbar_collapsed", toolbarCollapsed)
        .apply()
    } catch (_: Throwable) {}
  }

  private fun snapToolbarToLeftCenter() {
    val root = toolbarRoot ?: return
    val lp = toolbarLp ?: return
    val wmLocal = getSystemService(WINDOW_SERVICE) as WindowManager
    val usable = getUsableRectPx()
    try {
      // (요청) 가로모드에서 카메라홀(컷아웃) 계산: 좌측 시작점을 0이 아니라 usable.left로 맞춘다.
      // y는 usable 기준 중앙 정렬.
      val w = (root.width.takeIf { it > 0 } ?: root.measuredWidth).takeIf { it > 0 } ?: dpToPx(56f)
      val h = (root.height.takeIf { it > 0 } ?: root.measuredHeight).takeIf { it > 0 } ?: dpToPx(260f)

      // RTL에서도 항상 LEFT에 붙이기
      lp.gravity = Gravity.TOP or Gravity.LEFT
      lp.x = usable.left
      lp.y = (usable.centerY() - h / 2).coerceIn(usable.top, (usable.bottom - h).coerceAtLeast(usable.top))
      wmLocal.updateViewLayout(root, lp)
    } catch (_: Throwable) {}
    // 저장(다음 실행에서도 동일)
    try {
      flutterPrefs().edit()
        .putInt("flutter.as_toolbar_x", lp.x)
        .putInt("flutter.as_toolbar_y", lp.y)
        .apply()
    } catch (_: Throwable) {}
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // (요청) 가로/세로 전환 시 항상 "좌측 벽 중앙" 정렬
    uiH.post {
      try {
        updateToolbarLayout()
        applyToolbarState()
        // 레이아웃 재측정 타이밍 보장(가로->세로 전환 시 가로 배치가 남는 현상 방지)
        val root = toolbarRoot
        try {
          root?.requestLayout()
        } catch (_: Throwable) {}
        try {
          root?.post {
            try {
              // 세로모드에서는 updateToolbarLayout이 1열(세로)로 재구성한다.
              snapToolbarToLeftCenter()
              clampToolbarIfNeeded()
            } catch (_: Throwable) {}
          }
        } catch (_: Throwable) {
          // post가 불가능한 경우 즉시 스냅
          snapToolbarToLeftCenter()
          clampToolbarIfNeeded()
        }
      } catch (_: Throwable) {}
    }
  }

  private fun updateToolbarIconsOptimistic() {
    // 최소 구현: play 버튼만 즉시 바꿔 "눌렸는데 반응 없음"을 줄인다.
    val b = btnPlay ?: return
    val cur = (b.tag as? Boolean) ?: false
    val next = !cur
    b.tag = next
    b.setImageResource(if (next) android.R.drawable.ic_media_pause else R.drawable.cp_play)

    // (요청) 재생 중(축소모드)에는 즐겨1~3 숨김, 정지 시 다시 표시.
    // 즉시 반영 + 레이아웃도 재구성(버튼 크기/간격 계산에 영향).
    try {
      updateToolbarLayout()
      applyToolbarState()
      val root = toolbarRoot
      val lp = toolbarLp
      if (root != null && lp != null) {
        (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(root, lp)
      }
    } catch (_: Throwable) {}

    // (요청) "보기 ON/편집 ON" 상태에서 재생을 시작하면 자동으로 OFF로 토글 후 재생.
    // 접근성 툴바는 서비스 반영까지 딜레이가 있을 수 있어 UX상 즉시 OFF로 보이게 한다.
    if (next) {
      try {
        btnEdit?.tag = false
        btnEdit?.setImageResource(R.drawable.cp_edit_off)
      } catch (_: Throwable) {}
      try {
        btnObjects?.tag = false
        btnObjects?.setImageResource(R.drawable.cp_eye_off)
      } catch (_: Throwable) {}
      // 서비스가 prefs에 실제 값을 반영한 뒤 정확하게 재동기화
      uiH.postDelayed({ syncToggleIconsFromPrefs() }, 250L)
    }
  }

  private fun removeToolbarOverlay() {
    val root = toolbarRoot ?: return
    toolbarRoot = null
    toolbarLp = null
    btnPlay = null
    btnPlus = null
    btnEdit = null
    btnTrash = null
    btnObjects = null
    btnSettings = null
    btnClose = null
    tvHandle = null
    try {
      (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(root)
    } catch (_: Throwable) {}
  }
}

