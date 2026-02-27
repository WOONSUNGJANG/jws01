package com.atx.pic.color_picker_tool

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class AccessibilityIntroActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // (요청) 앱 언어 설정(flutter.lang)을 우선 사용(시스템 Locale과 무관).
    // 지원: ko/en/ja/zh/ar
    val lang =
      try {
        getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
          .getString("flutter.lang", "ko")
          ?.trim()
          ?.ifBlank { "ko" } ?: "ko"
      } catch (_: Throwable) {
        "ko"
      }

    val root =
      ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Color.WHITE)
        addView(
          LinearLayout(this@AccessibilityIntroActivity).apply {
            orientation = LinearLayout.VERTICAL
            // (요청) 상단 텍스트 잘림 방지: 시작을 3줄 정도 아래로 내림
            setPadding(dp(16), dp(64), dp(16), dp(16))
            layoutParams =
              ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              )

            addView(
              TextView(this@AccessibilityIntroActivity).apply {
                setTextColor(Color.parseColor("#111111"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                text =
                  t(
                    lang,
                    "접근성 권한 안내",
                    "Accessibility permission info",
                    "アクセシビリティ権限の案内",
                    "无障碍权限说明",
                    "معلومات إذن تسهيلات الاستخدام",
                  )
                setPadding(0, 0, 0, dp(12))
              }
            )

            // 1.1 Play Console "명시적 공개(Disclosure)" 문구(앱 내 표시용)
            val disclosure =
              TextView(this@AccessibilityIntroActivity).apply {
                setTextColor(Color.parseColor("#333333"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                text =
                  t(
                    lang,
                    "이 앱은 사용자가 지정한 마커와 지연시간 설정에 따라 화면을 자동으로 탭하거나 스와이프하고, 필요 시 키 입력을 수행하기 위해 Android 접근성 서비스를 사용합니다.\n" +
                      "접근성 서비스는 사용자가 설정에서 직접 켜야 동작하며, 언제든지 설정에서 끌 수 있습니다.\n" +
                      "이 기능을 위해 개인정보나 민감한 사용자 데이터를 수집하거나 외부로 전송하지 않습니다.\n" +
                      "사용자는 앱의 재생/정지 버튼으로 기능을 시작하거나 즉시 중지할 수 있습니다.",
                    "This app uses Android Accessibility services to automatically tap, swipe, and (if needed) perform key input based on the markers and delay settings you configure.\n" +
                      "The Accessibility service works only when you enable it in system settings, and you can turn it off at any time.\n" +
                      "We do not collect or transmit personal or sensitive user data for this feature.\n" +
                      "You can start or stop the feature immediately using the app’s Play/Stop button.",
                    "このアプリは、設定したマーカーと遅延設定に基づき、画面の自動タップ/スワイプ（必要に応じてキー入力）を行うために Android のユーザー補助サービスを使用します。\n" +
                      "ユーザー補助サービスは、システム設定で有効にした場合のみ動作し、いつでも無効にできます。\n" +
                      "この機能のために個人情報や機微なデータを収集・送信しません。\n" +
                      "再生/停止ボタンでいつでも開始・即時停止できます。",
                    "本应用使用 Android 无障碍服务，根据你设置的标记与延迟自动执行点击、滑动（如需也可进行按键输入）。\n" +
                      "无障碍服务仅在你于系统设置中开启后才会工作，并且可随时关闭。\n" +
                      "此功能不会收集或传输任何个人信息或敏感用户数据。\n" +
                      "你可以通过应用的播放/停止按钮随时开始或立即停止。",
                    "يستخدم هذا التطبيق خدمة تسهيلات الاستخدام في Android لتنفيذ النقر أو السحب تلقائياً وفقاً للعلامات وأزمنة التأخير التي تحددها، ولتنفيذ إدخال المفاتيح عند الحاجة.\n" +
                      "تعمل الخدمة فقط عند تفعيلها يدوياً من الإعدادات، ويمكنك إيقافها في أي وقت.\n" +
                      "لا نقوم بجمع أو إرسال بيانات شخصية أو حساسة لهذه الميزة.\n" +
                      "يمكنك البدء أو الإيقاف فوراً عبر زر التشغيل/الإيقاف.",
                  )

                // 라운드 박스 스타일
                background =
                  GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#F6F6F6"))
                    setStroke(dp(1), Color.parseColor("#E6E6E6"))
                  }
              }
            addView(disclosure)

            addView(space(dp(14)))

            // 1.2 접근성 경고(필수 활성화 안내) 문구
            val body =
              TextView(this@AccessibilityIntroActivity).apply {
                setTextColor(Color.parseColor("#444444"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, 0, 0, dp(6))
                text =
                  t(
                    lang,
                    "접근성 서비스를 활성화 시켜야 이앱을 사용할수있습니다.\n" +
                      "확인을 탭한 후, 목록에서 오토클릭짱을 선택하여 서비스를 활성화시켜주시기 바랍니다.",
                    "To use this app, you must enable the Accessibility service.\n" +
                      "Tap OK, then select AutoClickzzang in the list and enable the service.",
                    "このアプリを使用するには、ユーザー補助サービスを有効にする必要があります。\n" +
                      "OK をタップし、一覧から オートクリックちゃん を選択して有効にしてください。",
                    "要使用本应用，你必须启用无障碍服务。\n" +
                      "点击“确定”，然后在列表中选择 自动点击王 并启用该服务。",
                    "لاستخدام هذا التطبيق، يجب تفعيل خدمة تسهيلات الاستخدام.\n" +
                      "اضغط \"موافق\" ثم اختر \"النقر التلقائي\" من القائمة وفعّل الخدمة.",
                  )
              }
            addView(body)

            addView(space(dp(14)))

            // 2) 권한설정(접근성 설정) 이동 코드(원문 그대로)
            val btnOk =
              mkAction(t(lang, "확인", "OK", "OK", "确定", "موافق")) {
                try {
                  startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                  return@mkAction
                } catch (_: Exception) {
                }
                try {
                  startActivity(
                    Intent(
                      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                      Uri.parse("package:$packageName"),
                    ),
                  )
                } catch (_: Exception) {
                }
              }
            btnOk.layoutParams =
              LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              )
            addView(btnOk)

            addView(space(dp(8)))

            val btnClose =
              mkAction(t(lang, "닫기", "Close", "Close", "关闭", "إغلاق")) {
                finish()
              }
            btnClose.layoutParams =
              LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              )
            addView(btnClose)
          },
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        )
      }

    setContentView(root)
  }

  private fun space(h: Int): View =
    View(this).apply {
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

  private fun dp(v: Int): Int =
    (v.toFloat() * resources.displayMetrics.density).toInt()

  private fun t(code: String, ko: String, en: String, ja: String, zh: String, ar: String): String {
    val lang = code.lowercase(Locale.ROOT)
    return when (lang) {
      "ko" -> ko
      "ja" -> ja
      "zh" -> zh
      "ar" -> ar
      else -> en
    }
  }

  private fun mkAction(label: String, onClick: () -> Unit): Button =
    Button(this).apply {
      text = label
      isAllCaps = false
      gravity = Gravity.CENTER
      setOnClickListener { onClick() }
    }
}

