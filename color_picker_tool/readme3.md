# readme17.md — 설정(⋮) 메뉴 아이콘 항목 전체 소스(도움말/스크립터/평가/공유/피드백/크레딧/개인정보)

이 문서는 ATX2 프로젝트의 **설정 화면 상단 우측 “메뉴(⋮)” 아이콘**을 눌렀을 때 나오는 항목:

- 도움말
- 스크립터(Scripter)
- 앱 평가
- 앱 공유
- 피드백
- 크레딧
- 개인정보보호정책

…을 **다른 앱에서도 똑같이** 구현할 수 있도록, 실제 동작에 필요한 소스(원본 기반)를 한 곳에 모아둔 것입니다.

---
언어  아랍어 추가

## 1) 원본 위치(ATX2)

- **메뉴/항목 구현 핵심**: `android/app/src/main/kotlin/com/example/atx_toolbar/SettingsActivity.kt`
- **다국어 문자열 선택(필수 의존)**: `android/app/src/main/kotlin/com/example/atx_toolbar/I18n.kt`

또한 도움말 화면에서 이미지를 표시하기 위해 아래 에셋/리소스를 사용합니다.

- Flutter 에셋(권장): `submenu.jpg`, `help.jpg`, `scr.jpg` (프로젝트 `pubspec.yaml`에 assets로 등록되어 있어야 함)
- 또는 Android drawable 폴백: `android/app/src/main/res/drawable/submenu.jpg` 등

---

## 2) 그대로 복사해서 쓰는 소스

### 2-1) `I18n.kt` (필수)

```kotlin
package com.jws.atx_toolbar

import android.content.Context
import java.util.Locale

object I18n {
    fun lang(context: Context): String {
        return try {
            val sp = context.getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
            val saved = sp.getString("uiLang", null)?.trim()?.lowercase()
            if (!saved.isNullOrBlank()) return saved

            // 저장된 값이 없으면 시스템 언어를 기본으로 사용(지원 언어만)
            val sys = Locale.getDefault().language.trim().lowercase()
            when (sys) {
                "ko", "en", "ja", "zh" -> sys
                else -> "ko"
            }
        } catch (_: Exception) {
            "ko"
        }
    }

    fun t(context: Context, ko: String, en: String, ja: String = ko, zh: String = ko): String {
        return when (lang(context)) {
            "en" -> en
            "ja" -> ja
            "zh" -> zh
            else -> ko
        }
    }
}
```

---

### 2-2) `SettingsActivity.kt` — (1) 상단 우측 ⋮ 버튼 UI + (2) 팝업 메뉴 + (3) 각 메뉴 동작

아래 코드는 ATX2의 `SettingsActivity.kt`에서 **해당 기능에 직접 연결되는 부분들**입니다.

#### A. 개인정보보호정책 URL 상수(반드시 실제 URL로 교체)

```kotlin
companion object {
    // TODO: 실제 개인정보보호정책 URL로 교체 필요
    private const val PRIVACY_POLICY_URL = "https://www.jwssoft.com/"
}
```

#### B. 설정 화면 상단(제목 + ⋮ 메뉴 버튼)

```kotlin
// 상단 제목 + ⋮ 메뉴
addView(
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(context).apply {
                text = t("설정", "Settings", "設定", "设置")
                textSize = 18f * scale
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_more)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = t("메뉴", "Menu", "メニュー", "菜单")
                val s = dp((34 * scale).roundToInt().coerceAtLeast(28))
                layoutParams = LinearLayout.LayoutParams(s, s).apply { leftMargin = dp(6) }
                setOnClickListener { showOverflowMenu(this) }
            }
        )
    }
)
```

#### C. 팝업 메뉴 생성/항목 추가/클릭 처리(요청하신 7개 항목)

```kotlin
private fun showOverflowMenu(anchor: View) {
    try {
        val sp = getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
        val uiLang = I18n.lang(this)
        fun t(ko: String, en: String, ja: String = ko, zh: String = ko): String =
            I18n.t(this@SettingsActivity, ko, en, ja, zh)

        val menu = PopupMenu(this, anchor)
        val ID_HELP = 100
        val ID_SCRIPTER = 101
        val ID_REVIEW = 1
        val ID_SHARE = 2
        val ID_FEEDBACK = 3
        val ID_CREDITS = 4
        val ID_PRIVACY = 5

        // 요청: 앱평가 위에 도움말/스크립터 추가(기존 버튼은 제거)
        menu.menu.add(0, ID_HELP, 0, t("도움말", "Help", "ヘルプ", "帮助"))
        menu.menu.add(0, ID_SCRIPTER, 1, t("스크립터", "Scripter", "スクリプター", "脚本"))
        menu.menu.add(0, ID_REVIEW, 2, t("앱 평가", "Rate app", "アプリを評価", "应用评分"))
        menu.menu.add(0, ID_SHARE, 3, t("앱 공유", "Share app", "アプリを共有", "分享应用"))
        menu.menu.add(0, ID_FEEDBACK, 4, t("피드백", "Feedback", "フィードバック", "反馈"))
        menu.menu.add(0, ID_CREDITS, 5, t("크레딧", "Credits", "クレジット", "致谢"))
        menu.menu.add(0, ID_PRIVACY, 6, t("개인정보보호정책", "Privacy policy", "プライバシーポリシー", "隐私政策"))

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_HELP -> {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val scale = if (isLandscape) 0.72f else 1.0f
                    showHelpDialog(scale)
                    true
                }
                ID_SCRIPTER -> {
                    val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val scale = if (isLandscape) 0.72f else 1.0f
                    showRoutineDialog(scale)
                    true
                }
                ID_REVIEW -> {
                    openAppReview()
                    true
                }
                ID_SHARE -> {
                    shareApp()
                    true
                }
                ID_FEEDBACK -> {
                    sendFeedbackEmail()
                    true
                }
                ID_CREDITS -> {
                    showCreditsDialog()
                    true
                }
                ID_PRIVACY -> {
                    openPrivacyPolicy()
                    true
                }
                else -> false
            }
        }
        menu.show()
    } catch (_: Exception) {
    }
}
```

#### D. 앱 평가(Play 스토어로 이동)

```kotlin
private fun openAppReview() {
    val pkg = packageName
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    } catch (_: Exception) {
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
```

#### E. 앱 공유(스토어 URL을 텍스트로 공유)

```kotlin
private fun shareApp() {
    val pkg = packageName
    val url = "https://play.google.com/store/apps/details?id=$pkg"
    val sp = getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
    val uiLang = I18n.lang(this)
    fun t(ko: String, en: String, ja: String = ko, zh: String = ko): String =
        I18n.t(this@SettingsActivity, ko, en, ja, zh)
    val text = t("앱 공유: $url", "Share app: $url", "アプリ共有: $url", "分享应用: $url")
    try {
        val i =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(i, t("앱 공유", "Share app", "共有", "分享")))
    } catch (_: Exception) {
    }
}
```

#### F. 피드백(이메일 앱 열기 + 버전/패키지 정보 자동 첨부)

```kotlin
private fun sendFeedbackEmail() {
    val sp = getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
    val uiLang = I18n.lang(this)
    fun t(ko: String, en: String, ja: String = ko, zh: String = ko): String =
        I18n.t(this@SettingsActivity, ko, en, ja, zh)
    val subject = t("ATX Toolbar 피드백", "ATX Toolbar Feedback", "ATX Toolbar フィードバック", "ATX Toolbar 反馈")
    val body =
        buildString {
            appendLine(t("내용을 입력해주세요:", "Please write your feedback:", "内容を入力してください:", "请填写反馈内容："))
            appendLine()
            appendLine("-----")
            try {
                appendLine("versionName=${BuildConfig.VERSION_NAME}")
                appendLine("versionCode=${BuildConfig.VERSION_CODE}")
                appendLine("buildTime=${BuildConfig.BUILD_TIME}")
            } catch (_: Exception) {
            }
            appendLine("package=$packageName")
        }
    try {
        val i = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(i, t("피드백 보내기", "Send feedback", "フィードバック送信", "发送反馈")))
    } catch (_: Exception) {
    }
}
```

#### G. 크레딧(간단 다이얼로그)

```kotlin
private fun showCreditsDialog() {
    val sp = getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
    val uiLang = I18n.lang(this)
    fun t(ko: String, en: String, ja: String = ko, zh: String = ko): String =
        I18n.t(this@SettingsActivity, ko, en, ja, zh)
    val msg =
        t(
            "ATX Toolbar\n\n- Android AccessibilityService 기반 오토클릭/스와이프 도구\n- © 2026",
            "ATX Toolbar\n\n- Auto tap/swipe tool based on Android AccessibilityService\n- © 2026",
            "ATX Toolbar\n\n- Android のユーザー補助サービスに基づく自動タップ/スワイプツール\n- © 2026",
            "ATX Toolbar\n\n- 基于 Android 无障碍服务的自动点击/滑动工具\n- © 2026"
        )
    try {
        AlertDialog.Builder(this)
            .setTitle(t("크레딧", "Credits", "クレジット", "致谢"))
            .setMessage(msg)
            .setPositiveButton(t("닫기", "Close", "閉じる", "关闭"), null)
            .show()
    } catch (_: Exception) {
    }
}
```

#### H. 개인정보보호정책(브라우저로 URL 열기)

```kotlin
private fun openPrivacyPolicy() {
    val sp = getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
    val uiLang = I18n.lang(this)
    fun t(ko: String, en: String, ja: String = ko, zh: String = ko): String =
        I18n.t(this@SettingsActivity, ko, en, ja, zh)
    if (PRIVACY_POLICY_URL.startsWith("https://example.com")) {
        Toast.makeText(
            this,
            t("개인정보보호정책 URL이 아직 설정되지 않았습니다", "Privacy policy URL is not set", "プライバシーポリシーURLが未設定です", "隐私政策链接尚未设置"),
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}
```

---

## 3) “도움말” 항목 소스(이미지 표시 포함)

ATX2의 도움말은 `showHelpDialog(scale)`이 담당합니다. 이 함수 내부에는:

- `submenu.jpg`(메뉴 설명 이미지)
- `help.jpg`(도움말 이미지)
- `scr.jpg`(화면설정 이미지)

…를 표시하는 로직이 포함되어 있으며, **Android drawable → 없으면 Flutter 에셋(flutter_assets)에서 로드**하는 폴백 구조입니다.

> 아래는 핵심 로딩/팝업 부분(원본 기반)입니다.  
> 전체 Help 레이아웃/텍스트가 필요하면 `SettingsActivity.kt`의 `showHelpDialog()` 본문을 그대로 복사해 사용하세요.

```kotlin
// pubspec.yaml 에 등록된 에셋은 APK 내부 flutter_assets/ 아래에 포함된다.
fun mkImageFromFlutterAssetOrNull(assetFileName: String, padDp: Int = 10): ImageView? {
    val candidates =
        listOf(
            "flutter_assets/$assetFileName",
            assetFileName,
            "flutter_assets/assets/$assetFileName",
            "assets/$assetFileName"
        )
    for (p in candidates) {
        try {
            assets.open(p).use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return@use
                return ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp2(padDp), 0, dp2(padDp), 0)
                }
            }
        } catch (_: Exception) {
            // try next
        }
    }
    return null
}

fun mkDrawableImageOrNull(drawableName: String, padDp: Int = 10): ImageView? {
    return try {
        val resId = resources.getIdentifier(drawableName, "drawable", packageName)
        if (resId == 0) return null
        ImageView(this).apply {
            setImageResource(resId)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp2(padDp), 0, dp2(padDp), 0)
        }
    } catch (_: Exception) {
        null
    }
}

fun mkAppImageOrNull(drawableName: String, assetFileName: String, padDp: Int = 10): ImageView? {
    // 1) res/drawable 먼저 시도, 없으면 2) Flutter 에셋 시도
    return mkDrawableImageOrNull(drawableName, padDp) ?: mkImageFromFlutterAssetOrNull(assetFileName, padDp)
}

fun showHelpImageDialog(
    title: String,
    drawableName: String,
    assetFileName: String,
    fallbackFilename: String,
    descLines: List<String>
) {
    try {
        val col =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2(14), dp2(12), dp2(14), dp2(10))
            }
        val img = mkAppImageOrNull(drawableName, assetFileName, padDp = 0)
        if (img != null) {
            col.addView(img)
        } else {
            col.addView(mkMissingImageHint(fallbackFilename))
        }
        col.addView(space(10))
        col.addView(
            TextView(this).apply {
                textSize = 13.5f * scale
                setTextColor(0xFFFFFFFF.toInt())
                text = descLines.joinToString("\n")
            }
        )
        val sv =
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    col,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton(t("닫기", "Close", "閉じる", "关闭"), null)
            .show()
    } catch (_: Exception) {
    }
}
```

---

## 4) “스크립터(Scripter)” 항목 소스(현재 마커를 실행 텍스트로 변환)

스크립터 메뉴는 `showRoutineDialog(scale)`를 열고, 언어에 따라 `buildRoutineText()` 또는 `buildRoutineTextEn()` 등을 호출합니다.

특징:

- `FlutterSharedPreferences` 의 `flutter.markers` JSON을 읽어서 요약 텍스트를 생성
- 좌표는 **usable area(네비게이션바 제외)** 기준 px 계산을 사용 (`AutoClickAccessibilityService.getUsableScreenSizePx()`)
- 순번(양수 index), 멀티(음수 index), 단독(solo_main/solo_item), 모듈(module) 등을 분류하여 출력

> 아래는 원본 기반의 핵심 흐름입니다.  
> **완전히 동일한 출력/분류/문구까지 똑같이** 하려면 `SettingsActivity.kt`의 `showRoutineDialog()`와 `buildRoutineText*()` 전체를 그대로 복사하세요.

```kotlin
private fun showRoutineDialog(scale: Float) {
    try {
        val sp = getSharedPreferences("atx_settings", Context.MODE_PRIVATE)
        val uiLang = I18n.lang(this)
        fun t(ko: String, en: String, ja: String = ko, zh: String = ko): String =
            I18n.t(this@SettingsActivity, ko, en, ja, zh)
        fun dp2(v: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                resources.displayMetrics
            ).roundToInt()
        }

        val text =
            when (uiLang) {
                "en" -> buildRoutineTextEn()
                "ja" -> buildRoutineTextJa()
                "zh" -> buildRoutineTextZh()
                else -> buildRoutineText()
            }
        val tv =
            TextView(this).apply {
                textSize = 14f * scale
                // 요청: 스크립터 글자색이 어두움 -> 흰색으로 표시
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(
                    dp2((14 * scale).roundToInt()),
                    dp2((10 * scale).roundToInt()),
                    dp2((14 * scale).roundToInt()),
                    dp2((10 * scale).roundToInt())
                )
                this.text = text
            }
        val sv =
            ScrollView(this).apply {
                isFillViewport = true
                addView(
                    tv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

        val d =
            AlertDialog.Builder(this)
                .setTitle(t("스크립터(script)", "Scripter (script)", "スクリプター(script)", "脚本(script)"))
                .setView(sv)
                .setPositiveButton(t("닫기", "Close", "閉じる", "关闭"), null)
                .create()
        d.show()
    } catch (_: Exception) {
        Toast.makeText(this, "Unable to open scripter", Toast.LENGTH_SHORT).show()
    }
}
```

---

## 5) 적용 체크리스트(다른 앱에 이식할 때)

- **핵심 파일 복사**: `SettingsActivity.kt`(해당 함수들), `I18n.kt`
- **개인정보 URL 수정**: `PRIVACY_POLICY_URL`
- **(선택) 도움말 이미지**:
  - Flutter 에셋을 그대로 쓰면 `pubspec.yaml`의 `assets:`에 `submenu.jpg`, `help.jpg`, `scr.jpg` 등록
  - 또는 Android `res/drawable/`에 동일 파일을 넣기(우선순위가 drawable)
- **(중요) 스크립터 좌표 기준**: `AutoClickAccessibilityService.getUsableScreenSizePx()` 기반이라, 다른 앱에서도 “네비게이션바 제외(usable)” 계산 함수가 동일해야 출력이 완전히 같아집니다.

