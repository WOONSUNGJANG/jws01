package com.atx.pic.color_picker_tool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.max

/**
 * 오버레이 UI 스케일(ATX2 scaleFactor 호환).
 * - ScreenCaptureService의 markerScalePercent를 기반으로 갱신된다.
 */
object OverlayUiScale {
  @Volatile
  var scaleFactor: Float = 1f
}

/**
 * ATX2 스타일의 "버블(원 + 라벨)" 마커.
 * - kind: 일반 실행 마커(순번/독립/단독/모듈)
 * - blink: 실행 시 잠깐 색 변경
 */
class MarkerBubbleView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {

  // image_module 전용: 원 내부 채우지 않음 + 중앙 파란 숫자
  var hollowCircle: Boolean = false
    set(value) {
      field = value
      invalidate()
    }

  var label: String = "0"
    set(value) {
      field = value
      invalidate()
    }

  var baseColor: Int = Color.BLUE
    set(value) {
      field = value
      invalidate()
    }

  var blinkColor: Int? = null
    set(value) {
      field = value
      invalidate()
    }

  // 모듈 전용: 방향 표시 화살표
  var arrowDir: String? = null // U,D,L,R,UL,UR,DL,DR
    set(value) {
      field = value
      invalidate()
    }

  // 모듈 전용: 이중 원(outer+inner)
  var doubleRing: Boolean = false
    set(value) {
      field = value
      invalidate()
    }

  // 색상모듈 전용: 십자선 표시
  var crosshair: Boolean = false
    set(value) {
      field = value
      invalidate()
    }

  // 색상모듈 전용: 원/테두리/라벨 없이 십자선만 표시
  var crosshairOnly: Boolean = false
    set(value) {
      field = value
      invalidate()
    }

  var crosshairColor: Int = Color.parseColor("#A855F7") // purple
    set(value) {
      field = value
      invalidate()
    }

  private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val paintStrokeOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(2f)
    color = Color.WHITE
  }
  private val paintStrokeInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(1f)
    color = Color.BLACK
  }
  private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textAlign = Paint.Align.CENTER
    textSize = dp(16f)
    isFakeBoldText = true
  }

  // image_module 숫자(파란색) 더 찐하게: 외곽선 + 채움
  private val paintTextStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = dp(2.2f)
    color = Color.parseColor("#1D4ED8") // 진한 파랑(외곽선)
    textAlign = Paint.Align.CENTER
    textSize = dp(16f)
    isFakeBoldText = true
  }

  private val paintArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL_AND_STROKE
    strokeWidth = dp(1.5f)
    color = Color.WHITE
  }

  private val paintCross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeWidth = dp(2f)
    color = Color.parseColor("#A855F7")
  }

  private fun dp(v: Float): Float = v * resources.displayMetrics.density
  private fun dpScaled(v: Float): Float = v * resources.displayMetrics.density * OverlayUiScale.scaleFactor.coerceAtLeast(0.5f)

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    val w = width.toFloat()
    val h = height.toFloat()
    // (중요) 방향모듈은 "창 크기"를 크게(패딩 포함) 만들지만,
    // 원(외부원) 크기는 기존 마커 원 크기와 동일해야 한다.
    // 따라서 모듈일 때는 패딩을 제외한 영역으로 원 반지름을 계산한다.
    val modulePad = if (doubleRing) dpScaled(18f) else 0f
    val size = (min(w, h) - modulePad * 2f).coerceAtLeast(dp(10f))
    val cx = w / 2f
    val cy = h / 2f
    val r = size / 2f - dp(1f)

    val fill = blinkColor ?: baseColor
    if (!crosshairOnly && !hollowCircle) {
      paintFill.color = fill
      canvas.drawCircle(cx, cy, r, paintFill)
    }

    // (요청) 방향모듈은 화살표(좌/우/상/하)도 마커 색상으로 칠한다.
    // 일반 마커의 단일 화살표(arrowDir)는 기존처럼 흰색 유지.
    paintArrow.color = if (doubleRing) fill else Color.WHITE

    // (요청) 색상모듈: 속은 투명, 원 테두리는 보라색으로 표시
    if (crosshairOnly) {
      paintCross.color = crosshairColor
      // 기존 마커 원 크기에 맞춘 외곽 링
      canvas.drawCircle(cx, cy, (r - dp(0.5f)).coerceAtLeast(0f), paintCross)
    }

    // (요청) 색상모듈은 보라색 십자선 표시
    if (crosshair) {
      paintCross.color = crosshairColor
      val len = r * 0.78f
      canvas.drawLine(cx - len, cy, cx + len, cy, paintCross)
      canvas.drawLine(cx, cy - len, cx, cy + len, paintCross)
    }

    if (!crosshairOnly) {
      if (hollowCircle) {
        // image_module: 빨간 원(채움 없음)
        paintStrokeOuter.color = fill
        paintStrokeOuter.strokeWidth = dp(2.2f)
        canvas.drawCircle(cx, cy, (r - dp(0.5f)).coerceAtLeast(0f), paintStrokeOuter)
      } else {
        // 기본: 테두리(흰+검)
        paintStrokeOuter.color = Color.WHITE
        paintStrokeOuter.strokeWidth = dp(2f)
        canvas.drawCircle(cx, cy, r - dp(0.5f), paintStrokeOuter)
        canvas.drawCircle(cx, cy, r - dp(2.5f), paintStrokeInner)
      }
    }

    if (doubleRing) {
      canvas.drawCircle(cx, cy, r - dp(6f), paintStrokeOuter)
      canvas.drawCircle(cx, cy, r - dp(8f), paintStrokeInner)
    }

    // 텍스트
    if (!crosshairOnly && label.isNotBlank()) {
      val textBounds = Rect()
      // image_module: 중앙 숫자 파란색
      val isImg = hollowCircle
      paintText.color = if (isImg) Color.parseColor("#3B82F6") else Color.WHITE
      // textSize는 paint마다 맞춰준다(리사이즈/스케일 반영)
      val ts = dpScaled(if (isImg) 17f else 16f)
      paintText.textSize = ts
      paintTextStroke.textSize = ts
      paintText.getTextBounds(label, 0, label.length, textBounds)
      val textY = cy - (paintText.descent() + paintText.ascent()) / 2f
      if (isImg) {
        // 외곽선 → 채움 순서
        canvas.drawText(label, cx, textY, paintTextStroke)
      }
      canvas.drawText(label, cx, textY, paintText)
    }

    fun drawArrow(dx: Float, dy: Float) {
      if (dx == 0f && dy == 0f) return
      val arrowLen = r * 0.75f
      val tipDist = r + dp(10f)
      val tipX = cx + dx * tipDist
      val tipY = cy + dy * tipDist
      val baseX = cx + dx * (tipDist - arrowLen)
      val baseY = cy + dy * (tipDist - arrowLen)

      // 삼각형 화살촉
      val perpX = -dy
      val perpY = dx
      val halfW = dp(6f)

      val p =
        Path().apply {
          moveTo(tipX, tipY)
          lineTo(baseX + perpX * halfW, baseY + perpY * halfW)
          lineTo(baseX - perpX * halfW, baseY - perpY * halfW)
          close()
        }
      canvas.drawPath(p, paintArrow)
    }

    // 방향 표시:
    // - module(이중원)일 때는 ATX2처럼 4방향 화살표(↑→↓←)를 항상 표시
    // - 그 외에는 arrowDir 1개만 표시(호환)
    if (doubleRing) {
      drawArrow(0f, -1f) // U
      drawArrow(1f, 0f) // R
      drawArrow(0f, 1f) // D
      drawArrow(-1f, 0f) // L
      return
    }

    val dir = arrowDir ?: return
    val (dx, dy) =
      when (dir) {
        "U" -> Pair(0f, -1f)
        "D" -> Pair(0f, 1f)
        "L" -> Pair(-1f, 0f)
        "R" -> Pair(1f, 0f)
        "UL" -> Pair(-0.7f, -0.7f)
        "UR" -> Pair(0.7f, -0.7f)
        "DL" -> Pair(-0.7f, 0.7f)
        "DR" -> Pair(0.7f, 0.7f)
        else -> Pair(0f, 0f)
      }
    drawArrow(dx, dy)
  }
}

/**
 * ATX2 스타일의 "링(테두리만)" 마커.
 * - kind == "color" 또는 kind == "swipe_to"
 */
class ColorRingView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {

  private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(220, 255, 255, 255)
  }
  private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(220, 0, 0, 0)
  }

  private fun dpScaled(v: Float): Float = v * resources.displayMetrics.density * OverlayUiScale.scaleFactor.coerceAtLeast(0.5f)

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    val size = min(w, h)
    val cx = w / 2f
    val cy = h / 2f
    val stroke = dpScaled(2.6f)
    paintOuter.strokeWidth = stroke
    paintInner.strokeWidth = stroke

    val r = size / 2f
    val outer = (r - stroke / 2f).coerceAtLeast(0f)
    canvas.drawCircle(cx, cy, outer, paintOuter)
    val inner = (outer - 1f).coerceAtLeast(0f) // ATX2: 바깥 원보다 반지름 1px 작은 원
    canvas.drawCircle(cx, cy, inner, paintInner)
  }
}

data class LinkSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * 편집모드에서 swipe 체인을 연결선으로 시각화.
 */
class LinkLineView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : View(context, attrs) {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = 0x6600A8FF.toInt() // ATX2: 반투명 파란색
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  private var x1: Float = 0f
  private var y1: Float = 0f
  private var x2: Float = 0f
  private var y2: Float = 0f

  fun setPoints(x1: Float, y1: Float, x2: Float, y2: Float) {
    this.x1 = x1
    this.y1 = y1
    this.x2 = x2
    this.y2 = y2
    invalidate()
  }

  private fun dp(v: Float): Float = v * resources.displayMetrics.density

  private fun updateStrokeWidth() {
    // 기준 지름: 34dp * density * scaleFactor, 최소 14dp * density
    val base = 34f * OverlayUiScale.scaleFactor.coerceAtLeast(0.5f)
    val diameter = max(dp(14f), dp(base))
    paint.strokeWidth = (diameter / 2f).coerceAtLeast(1f)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    updateStrokeWidth()
    canvas.drawLine(x1, y1, x2, y2, paint)
  }
}

