package com.atx.pic.color_picker_tool

import android.content.SharedPreferences

/**
 * 앱 내부 오버레이/설정창용 간단 i18n.
 * - 시스템 Locale과 무관하게 flutter.lang(prefs) 설정을 기준으로 문구를 바꾼다.
 * - 지원: ko/en/ja/zh/ar (아랍어는 RTL)
 */
object I18n {
  const val PREF_LANG = "flutter.lang"

  fun langFromPrefs(p: SharedPreferences): String {
    val code = try { p.getString(PREF_LANG, "ko") } catch (_: Throwable) { "ko" }
    return (code ?: "ko").ifBlank { "ko" }
  }

  fun isRtl(lang: String): Boolean = lang.equals("ar", ignoreCase = true)

  private fun pick(lang: String, ko: String, en: String, ja: String, zh: String, ar: String): String {
    return when (lang.lowercase()) {
      "en" -> en
      "ja" -> ja
      "zh" -> zh
      "ar" -> ar
      else -> ko
    }
  }

  fun languageOptionsUi(lang: String): List<Pair<String, String>> {
    // 라벨 자체도 UI 언어에 맞춰 표시
    return when (lang.lowercase()) {
      "en" -> listOf("ko" to "1.Korean", "en" to "2.English", "ja" to "3.Japanese", "zh" to "4.Chinese", "ar" to "5.Arabic")
      "ja" -> listOf("ko" to "1.韓国語", "en" to "2.英語", "ja" to "3.日本語", "zh" to "4.中国語", "ar" to "5.アラビア語")
      "zh" -> listOf("ko" to "1.韩语", "en" to "2.英语", "ja" to "3.日语", "zh" to "4.中文", "ar" to "5.阿拉伯语")
      "ar" -> listOf("ko" to "1.الكورية", "en" to "2.الإنجليزية", "ja" to "3.اليابانية", "zh" to "4.الصينية", "ar" to "5.العربية")
      else -> listOf("ko" to "1.한국어", "en" to "2.영어", "ja" to "3.일본어", "zh" to "4.중국어", "ar" to "5.아랍어")
    }
  }

  /**
   * 언어 스피너 항목은 "각 언어 자체 표기"로 고정(빠른 선택/가독성).
   * 예) 한국어/English/日本語/中文/العربية
   */
  fun languageOptionsSelf(): List<Pair<String, String>> {
    // RTL(ar)에서도 "1." 같은 순번이 뒤로 밀리지 않게 LRM으로 시작 방향을 LTR로 고정한다.
    val lrm = "\u200E"
    return listOf(
      "ko" to "${lrm}1.한국어",
      "en" to "${lrm}2.English",
      "ja" to "${lrm}3.日本語",
      "zh" to "${lrm}4.中文",
      "ar" to "${lrm}5.العربية",
    )
  }

  fun settingsTitle(lang: String) = pick(lang, "설정", "Settings", "設定", "设置", "الإعدادات")
  fun menu(lang: String) = pick(lang, "메뉴", "Menu", "メニュー", "菜单", "القائمة")
  fun close(lang: String) = pick(lang, "닫기", "Close", "閉じる", "关闭", "إغلاق")
  fun help(lang: String) = pick(lang, "도움말", "Help", "ヘルプ", "帮助", "مساعدة")
  fun scripter(lang: String) = pick(lang, "스크립터", "Scripter", "スクリプター", "脚本", "السكربتر")
  fun rateApp(lang: String) = pick(lang, "앱 평가", "Rate app", "アプリを評価", "应用评分", "قيّم التطبيق")
  fun shareApp(lang: String) = pick(lang, "앱 공유", "Share app", "アプリを共有", "分享应用", "مشاركة التطبيق")
  fun feedback(lang: String) = pick(lang, "피드백", "Feedback", "フィードバック", "反馈", "ملاحظات")
  fun credits(lang: String) = pick(lang, "크레딧", "Credits", "クレジット", "致谢", "الشكر")
  fun privacyPolicy(lang: String) = pick(lang, "개인정보보호정책", "Privacy policy", "プライバシーポリシー", "隐私政策", "سياسة الخصوصية")
  fun language(lang: String) = pick(lang, "언어", "Language", "言語", "语言", "اللغة")
  fun screenSettings(lang: String) = pick(lang, "화면설정", "Screen settings", "画面設定", "屏幕设置", "إعدادات الشاشة")
  fun colorPanel(lang: String) = pick(lang, "색상패널", "Color panel", "カラーパネル", "颜色面板", "لوحة الألوان")
  fun stopCondition(lang: String) = pick(lang, "중지조건", "Stop condition", "停止条件", "停止条件", "شرط الإيقاف")
  fun stopInfinite(lang: String) = pick(lang, "무한대", "Infinite", "無限", "无限", "غير محدود")
  fun stopTimeLimit(lang: String) = pick(lang, "시간 제한", "Time limit", "時間制限", "时间限制", "حدّ زمني")
  fun stopCycles(lang: String) = pick(lang, "사이클 수", "Cycle count", "サイクル数", "循环次数", "عدد الدورات")
  fun macroSave(lang: String) = pick(lang, "메크로 저장", "Save macro", "マクロ保存", "保存宏", "حفظ الماكرو")
  fun macroOpen(lang: String) = pick(lang, "메크로 열기", "Open macro", "マクロを開く", "打开宏", "فتح الماكرو")
  fun accessibilitySettings(lang: String) = pick(lang, "접근성 설정", "Accessibility settings", "ユーザー補助設定", "无障碍设置", "إعدادات تسهيلات الاستخدام")
  fun screenSharePermissionSettings(lang: String) =
    pick(lang, "화면 공유 권한 설정", "Screen share permission", "画面共有の権限設定", "设置屏幕共享权限", "إعداد إذن مشاركة الشاشة")
  fun logView(lang: String) = pick(lang, "로그보기", "View logs", "ログ表示", "查看日志", "عرض السجل")
  fun debugging(lang: String) = pick(lang, "디버깅", "Debug", "デバッグ", "调试", "تصحيح")
  fun logWindowTitle(lang: String) = pick(lang, "실행 로그", "Run log", "実行ログ", "运行日志", "سجل التنفيذ")
  fun logStart(lang: String) = pick(lang, "시작", "Start", "開始", "开始", "بدء")
  fun logStop(lang: String) = pick(lang, "중지", "Stop", "停止", "停止", "إيقاف")
  fun logClose(lang: String) = pick(lang, "닫기", "Close", "閉じる", "关闭", "إغلاق")
  fun logClearScreen(lang: String) = pick(lang, "화면정리", "Clear", "クリア", "清空", "مسح")
  fun logDetail(lang: String) = pick(lang, "상세", "Detail", "詳細", "详细", "تفصيل")
  fun logSummary(lang: String) = pick(lang, "요약", "Summary", "要約", "摘要", "ملخص")
  fun logSmall(lang: String) = pick(lang, "작게", "Small", "小さく", "缩小", "تصغير")
  fun logLarge(lang: String) = pick(lang, "크게", "Large", "大きく", "放大", "تكبير")

  fun soloVerifyLabel(lang: String) = pick(lang, "클릭실행확인", "Click verify", "クリック確認", "点击确认", "تأكيد النقر")
  fun soloVerifyUse(lang: String) = pick(lang, "클릭실행확인 체크", "Enable click verify", "確認を有効", "启用点击确认", "تفعيل تأكيد النقر")
  fun soloVerifyModeLabel(lang: String) = pick(lang, "실행확인 방식", "Verify mode", "確認方式", "验证方式", "وضع التحقق")
  // (호환) 함수명은 유지하되, 문구는 최신 solo verify 명세로 변경
  fun soloVerifyModeFailRetry(lang: String) = pick(lang, "이미지가 있으면 클릭 / 없으면 재개", "If image exists: Click / else resume", "画像があればクリック / なければ再開", "有图片则点击 / 无则继续", "إذا وُجدت الصورة: انقر / وإلا استأنف")
  // (호환) 함수명은 유지하되, 문구는 최신 solo verify 명세로 변경
  fun soloVerifyModeSuccessPass(lang: String) = pick(lang, "이미지가 없으면 클릭 / 있으면 재개", "If image missing: Click / else resume", "画像がなければクリック / あれば再開", "无图片则点击 / 有则继续", "إذا لم توجد الصورة: انقر / وإلا استأنف")

  // (추가) solo verify 실행 전 1회 클릭
  fun soloPreClickLabel(lang: String) = pick(lang, "실행전 클릭", "Pre-click", "事前クリック", "执行前点击", "نقرة قبل التنفيذ")
  fun soloPreClickUse(lang: String) = pick(lang, "실행전 클릭(기본 해제)", "Enable pre-click (default off)", "事前クリック(既定OFF)", "执行前点击(默认关闭)", "تفعيل نقرة قبل التنفيذ (افتراضي إيقاف)")
  fun soloPreClickPick(lang: String) = pick(lang, "좌표선택", "Pick coord", "座標選択", "选择坐标", "اختر الإحداثيات")
  fun next(lang: String) = pick(lang, "넘기기", "Next", "次へ", "下一步", "التالي")
  fun select(lang: String) = pick(lang, "선택", "Select", "選択", "选择", "اختيار")
  fun center(lang: String) = pick(lang, "중앙", "Center", "中央", "居中", "الوسط")

  fun logFilterShort(lang: String, kind: String): String {
    return when (kind) {
      "click" -> pick(lang, "순번", "Ord", "順番", "顺序", "ترتيب")
      "independent" -> pick(lang, "독립", "Ind", "独立", "独立", "مستقل")
      "swipe" -> pick(lang, "스와이프", "Swipe", "スワイプ", "滑动", "سحب")
      "solo_main" -> pick(lang, "단독", "Solo", "単独", "单独", "منفرد")
      "module" -> pick(lang, "방향", "Dir", "方向", "方向", "اتجاه")
      "color_module" -> pick(lang, "색상", "Color", "色", "颜色", "لون")
      "image_module" -> pick(lang, "이미지", "Image", "画像", "图片", "صورة")
      else -> kind
    }
  }

  fun logCatName(lang: String, cat: Int): String {
    return when (cat) {
      1 -> "1.${logFilterShort(lang, "click")}"
      2 -> "2.${logFilterShort(lang, "independent")}"
      3 -> "3.${logFilterShort(lang, "swipe")}"
      4 -> "4.${logFilterShort(lang, "solo_main")}"
      5 -> "5.${logFilterShort(lang, "module")}"
      6 -> "6.${logFilterShort(lang, "color_module")}"
      7 -> "7.${logFilterShort(lang, "image_module")}"
      else -> cat.toString()
    }
  }

  fun screenSettingsTitle(lang: String) = pick(lang, "기본설정(화면설정)", "Basic (Screen settings)", "基本設定(画面)", "基本设置(屏幕)", "الإعدادات الأساسية (الشاشة)")
  fun toolbarOpacity(lang: String, percent: Int) =
    pick(lang, "툴바 투명도: ${percent}%", "Toolbar opacity: ${percent}%", "ツールバー透明度: ${percent}%", "工具栏透明度: ${percent}%", "شفافية الشريط: ${percent}%")
  fun toolbarScaleX(lang: String, percent: Int) =
    pick(lang, "툴바 가로크기(세로유지): ${percent}%", "Toolbar width (keep height): ${percent}%", "ツールバー幅(高さ維持): ${percent}%", "工具栏宽度(保持高度): ${percent}%", "عرض الشريط (مع تثبيت الارتفاع): ${percent}%")
  fun objectScale(lang: String, delta: Int): String {
    val sign = if (delta >= 0) "+" else ""
    return pick(
      lang,
      "객체 크기(최대 +100%): ${sign}${delta}%",
      "Object size (max +100%): ${sign}${delta}%",
      "オブジェクトサイズ(最大 +100%): ${sign}${delta}%",
      "对象大小(最大 +100%): ${sign}${delta}%",
      "حجم العنصر (حتى +100%): ${sign}${delta}%"
    )
  }
  fun clickPressMs(lang: String, ms: Int) =
    pick(lang, "마커 클릭 pressMs(다운->업 유지): ${ms}ms", "Marker click pressMs (down->up): ${ms}ms", "マーカー押下(Down->Up): ${ms}ms", "点击按压(Down->Up): ${ms}ms", "زمن الضغط (Down->Up): ${ms}ms")
  fun imageVerifyInterval(lang: String, ms: Int) =
    pick(
      lang,
      "이미지 10차 검증 인터벌: ${ms}ms",
      "Image 10x verify interval: ${ms}ms",
      "画像 10回検証インターバル: ${ms}ms",
      "图片10次验证间隔: ${ms}ms",
      "فاصل التحقق 10 مرات للصورة: ${ms}ms"
    )
  fun execProbability(lang: String, percent: Int) =
    pick(lang, "실행확률: ${percent}%", "Run probability: ${percent}%", "実行確率: ${percent}%", "执行概率: ${percent}%", "احتمال التنفيذ: ${percent}%")

  fun randomDelayPct(lang: String, percent: Int) =
    pick(
      lang,
      "랜덤지연: ${percent}%",
      "Random delay: ${percent}%",
      "ランダム遅延: ${percent}%",
      "随机延迟: ${percent}%",
      "تأخير عشوائي: ${percent}%"
    )
  fun touchViz(lang: String) = pick(lang, "파란 점(터치 표시) 사용", "Show blue dot (touch)", "青い点(タッチ表示)を使用", "显示蓝点(触摸)", "إظهار النقطة الزرقاء (لمس)")
  fun touchVizHint(lang: String) = pick(lang, "※ 실행 중 클릭 좌표에만 잠깐 표시됩니다.", "※ Shown briefly only at click points while running.", "※ 実行中のクリック座標にのみ一瞬表示されます。", "※ 仅在运行时的点击坐标短暂显示。", "※ تظهر لفترة قصيرة فقط عند نقاط النقر أثناء التشغيل.")

  fun macroSaveHint(lang: String) = pick(lang, "기본폴더(atxfile)에서만 저장됩니다.", "Saved only in default folder (atxfile).", "既定フォルダ(atxfile)のみに保存されます。", "仅保存在默认文件夹(atxfile)。", "يتم الحفظ فقط في المجلد الافتراضي (atxfile).")
  fun macroOpenHint(lang: String) = pick(lang, "기본폴더(atxfile)에서만 불러옵니다.", "Loaded only from default folder (atxfile).", "既定フォルダ(atxfile)からのみ読み込みます。", "仅从默认文件夹(atxfile)读取。", "يتم التحميل فقط من المجلد الافتراضي (atxfile).")
  fun macroFavHint(lang: String) =
    pick(
      lang,
      "목록에서 파일을 길게 눌러 즐겨1~3에 등록할 수 있습니다.",
      "Long-press a file in the list to set Fav 1~3.",
      "一覧のファイルを長押しでお気に入り1~3に登録できます。",
      "长按列表中的文件可设置收藏1~3。",
      "اضغط مطولاً على ملف في القائمة لتعيينه كمفضل 1~3."
    )
  fun stopTimeTitle(lang: String) = pick(lang, "시간 제한 설정", "Set time limit", "時間制限設定", "设置时间限制", "ضبط الحد الزمني")
  fun stopCyclesTitle(lang: String) = pick(lang, "사이클 수 설정", "Set cycle count", "サイクル数設定", "设置循环次数", "ضبط عدد الدورات")
  fun cyclesHint(lang: String) = pick(lang, "횟수(예: 1)", "Count (e.g. 1)", "回数(例: 1)", "次数(例如 1)", "العدد (مثال: 1)")
  fun filenameLabel(lang: String) = pick(lang, "파일명:", "Filename:", "ファイル名:", "文件名:", "اسم الملف:")
  fun save(lang: String) = pick(lang, "저장", "Save", "保存", "保存", "حفظ")
  fun delete(lang: String) = pick(lang, "삭제", "Delete", "削除", "删除", "حذف")
  fun cancel(lang: String) = pick(lang, "취소", "Cancel", "キャンセル", "取消", "إلغاء")
  fun pickerCancel(lang: String) = cancel(lang)
  fun apply(lang: String) = pick(lang, "적용", "Apply", "適用", "应用", "تطبيق")

  fun passThroughCaption(lang: String, enabled: Boolean): String {
    val onOff = if (enabled) pick(lang, "ON", "ON", "ON", "ON", "تشغيل") else pick(lang, "OFF", "OFF", "OFF", "OFF", "إيقاف")
    val pass = pick(lang, "통과", "Pass", "通過", "穿透", "تمرير")
    return "$pass\n$onOff"
  }

  fun aiDefense(lang: String) = pick(lang, "AI탐지방어", "Anti-detection", "AI検知対策", "AI检测防护", "مقاومة الكشف")

  fun imageModuleLabel(lang: String) = pick(lang, "검색이미지", "Search image", "検索画像", "搜索图片", "صورة البحث")
  fun imagePick(lang: String) = pick(lang, "이미지가져오기", "Get image", "画像取得", "获取图片", "جلب الصورة")
  fun imageEdit(lang: String) = pick(lang, "이미지 수정", "Edit image", "画像を修正", "修改图片", "تعديل الصورة")
  fun imageAccuracyLabel(lang: String) = pick(lang, "이미지정확도(50~100%)", "Image accuracy (50~100%)", "画像精度(50~100%)", "图片精度(50~100%)", "دقة الصورة (50~100%)")
  fun imageStartCoordLabel(lang: String) = pick(lang, "시작체크좌표(픽셀)", "Start check XY (px)", "開始チェック座標(px)", "起始检查坐标(px)", "إحداثيات البداية (px)")
  fun imageEndCoordLabel(lang: String) = pick(lang, "종료체크좌표(픽셀)", "End check XY (px)", "終了チェック座標(px)", "结束检查坐标(px)", "إحداثيات النهاية (px)")
  fun imageFoundCenter(lang: String, x: Int, y: Int) =
    pick(
      lang,
      "검색된이미지중앙좌표(픽셀): x=${x}px, y=${y}px",
      "Found image center (px): x=${x}px, y=${y}px",
      "検出画像中心(px): x=${x}px, y=${y}px",
      "检测到的图片中心(px): x=${x}px, y=${y}px",
      "مركز الصورة المكتشفة (px): x=${x}px, y=${y}px"
    )

  fun imageFoundCenterEmpty(lang: String) =
    pick(
      lang,
      "검색된이미지중앙좌표(픽셀): x=?, y=?",
      "Found image center (px): x=?, y=?",
      "検出画像中心(px): x=?, y=?",
      "检测到的图片中心(px): x=?, y=?",
      "مركز الصورة المكتشفة (px): x=?, y=?"
    )

  fun imageClickModeLabel(lang: String) = pick(lang, "클릭 방식", "Click mode", "クリック方式", "点击方式", "وضع النقر")
  fun imageClickFoundCenter(lang: String) = pick(lang, "찿은이미지 중앙좌표 클릭", "Click found image center", "検出中心をクリック", "点击找到的中心", "انقر مركز الصورة")
  fun imageClickMarker(lang: String) = pick(lang, "마커위치클릭", "Click marker position", "マーカー位置をクリック", "点击标记位置", "انقر موقع العلامة")
  fun imageClickSound(lang: String) = pick(lang, "소리내기", "Play ringtone", "着信音を鳴らす", "播放铃声", "تشغيل نغمة الرنين")
  fun imageClickVibrate(lang: String) = pick(lang, "진동하기", "Vibrate", "バイブ", "震动", "اهتزاز")

  fun imageLastMatch(lang: String, scorePct: Int, minPct: Int, ok: Boolean): String {
    val status = if (ok) pick(lang, "성공", "OK", "成功", "成功", "نجاح") else pick(lang, "실패", "FAIL", "失敗", "失败", "فشل")
    return pick(
      lang,
      "최근매칭: score=${scorePct}%, min=${minPct}% ($status)",
      "Last match: score=${scorePct}%, min=${minPct}% ($status)",
      "直近マッチ: score=${scorePct}%, min=${minPct}% ($status)",
      "最近匹配: score=${scorePct}%, min=${minPct}% ($status)",
      "آخر مطابقة: score=${scorePct}%, min=${minPct}% ($status)"
    )
  }

  fun imageLastMatchEmpty(lang: String) =
    pick(
      lang,
      "최근매칭: (기록 없음)",
      "Last match: (no data)",
      "直近マッチ: (データなし)",
      "最近匹配: (无数据)",
      "آخر مطابقة: (لا بيانات)"
    )

  fun markerKindLabel(lang: String) = pick(lang, "마커 종류", "Marker type", "マーカー種類", "标记类型", "نوع العلامة")
  fun markerSettingsTitle(lang: String) = pick(lang, "마커 설정", "Marker settings", "マーカー設定", "标记设置", "إعدادات العلامة")
  fun delayLabel(lang: String) = pick(lang, "지연 시간(ms)", "Delay (ms)", "遅延(ms)", "延迟(ms)", "التأخير (ms)")
  fun jitterLabel(lang: String) = pick(lang, "랜덤 지연(%)", "Random delay (%)", "ランダム遅延(%)", "随机延迟(%)", "تأخير عشوائي (%)")
  fun pressLabel(lang: String) = pick(lang, "누름(ms)", "Press (ms)", "押下(ms)", "按压(ms)", "مدة الضغط (ms)")
  fun moveUpLabel(lang: String) =
    pick(
      lang,
      "move up(ms)  (마지막sub도착 후 → 손떼기(UP)까지 시간)",
      "Move up (ms) (after last sub → up)",
      "Move up(ms) (最後のsub→UPまで)",
      "Move up(ms)（最后sub→抬起）",
      "Move up(ms) (بعد آخر sub → رفع)"
    )
  fun swipeModeLabel(lang: String) = pick(lang, "스와이프 실행방식", "Swipe mode", "スワイプ方式", "滑动方式", "وضع السحب")
  fun modulePatternLabel(lang: String) = pick(lang, "스와이프 방향", "Swipe direction", "スワイプ方向", "滑动方向", "اتجاه السحب")
  fun moduleLenLabel(lang: String) = pick(lang, "스와이프 길이(px)", "Swipe length (px)", "スワイプ距離(px)", "滑动长度(px)", "طول السحب (px)")
  fun moduleDirModeLabel(lang: String) = pick(lang, "모듈 방향", "Module direction mode", "モジュール方向", "模块方向", "وضع اتجاه الوحدة")
  fun moduleExecModeLabel(lang: String) = pick(lang, "모듈 실행 방식", "Module run mode", "モジュール実行方式", "模块执行方式", "وضع تشغيل الوحدة")
  fun moduleMoveUpLabel(lang: String) =
    pick(
      lang,
      "Move up(ms)  (마지막 도착→손 떼기(UP)까지)",
      "Move up (ms) (after last → up)",
      "Move up(ms) (最後→UPまで)",
      "Move up(ms)（最后→抬起）",
      "Move up(ms) (بعد آخر → رفع)"
    )
  fun soloStartDelayLabel(lang: String) = pick(lang, "실행전 지연", "Pre-run delay", "実行前遅延", "执行前延迟", "تأخير قبل التشغيل")
  fun soloComboLabel(lang: String) = pick(lang, "콤보클릭 개수(1~10)", "Combo count (1~10)", "コンボ回数(1~10)", "连击次数(1~10)", "عدد الكومبو (1~10)")
  fun soloCreateCombo(lang: String) = pick(lang, "콤보생성", "Create combo", "コンボ作成", "生成连击", "إنشاء كومبو")
  fun test(lang: String) = pick(lang, "TEST", "TEST", "TEST", "TEST", "اختبار")

  fun alignH(lang: String) = pick(lang, "가로정렬", "Align horizontal", "横整列", "水平对齐", "محاذاة أفقية")
  fun alignV(lang: String) = pick(lang, "세로정렬", "Align vertical", "縦整列", "垂直对齐", "محاذاة عمودية")

  fun dirName(lang: String, code: String): String {
    return when (code) {
      "TAP" -> pick(lang, "클릭", "Tap", "タップ", "点击", "نقر")
      "U" -> pick(lang, "상", "Up", "上", "上", "أعلى")
      "D" -> pick(lang, "하", "Down", "下", "下", "أسفل")
      "L" -> pick(lang, "좌", "Left", "左", "左", "يسار")
      "R" -> pick(lang, "우", "Right", "右", "右", "يمين")
      "UL" -> pick(lang, "좌상", "Up-left", "左上", "左上", "أعلى-يسار")
      "UR" -> pick(lang, "우상", "Up-right", "右上", "右上", "أعلى-يمين")
      "DL" -> pick(lang, "좌하", "Down-left", "左下", "左下", "أسفل-يسار")
      "DR" -> pick(lang, "우하", "Down-right", "右下", "右下", "أسفل-يمين")
      else -> code
    }
  }

  fun swipeModeName(lang: String, mode: Int): String {
    return when (mode) {
      1 -> pick(lang, "2.독립실행", "2.Independent", "2.独立", "2.独立", "2.مستقل")
      else -> pick(lang, "1.순번실행", "1.Ordered", "1.順番", "1.顺序", "1.بالترتيب")
    }
  }

  fun moduleExecModeName(lang: String, mode: Int): String {
    return when (mode) {
      1 -> pick(lang, "2.모듈단독", "2.Module solo", "2.モジュール単独", "2.模块单独", "2.وحدة منفردة")
      else -> pick(lang, "1.모듈순번", "1.Module ordered", "1.モジュール順番", "1.模块顺序", "1.وحدة بالترتيب")
    }
  }

  fun moduleDirModeName(lang: String, mode: Int): String {
    return when (mode) {
      1 -> pick(lang, "2.전방향", "2.All directions", "2.全方向", "2.全方向", "2.كل الاتجاهات")
      else -> pick(lang, "1.한방향씩", "1.One by one", "1.一方向ずつ", "1.逐个方向", "1.اتجاه واحد كل مرة")
    }
  }

  fun modulePatternName(lang: String, pattern: Int): String {
    return when (pattern) {
      0 -> pick(lang, "1.시계", "1.Clockwise", "1.時計回り", "1.顺时针", "1.مع عقارب الساعة")
      1 -> pick(lang, "2.반시계", "2.Counter", "2.反時計回り", "2.逆时针", "2.عكس عقارب الساعة")
      2 -> pick(lang, "3.상하좌우", "3.Up/Down/Left/Right", "3.上下左右", "3.上下左右", "3.أعلى/أسفل/يسار/يمين")
      3 -> pick(lang, "4.좌우상하", "4.Left/Right/Up/Down", "4.左右上下", "4.左右上下", "4.يسار/يمين/أعلى/أسفل")
      4 -> pick(lang, "5.좌우", "5.Left/Right", "5.左右", "5.左右", "5.يسار/يمين")
      5 -> pick(lang, "6.상하", "6.Up/Down", "6.上下", "6.上下", "6.أعلى/أسفل")
      6 -> pick(lang, "7.상", "7.Up", "7.上", "7.上", "7.أعلى")
      7 -> pick(lang, "8.하", "8.Down", "8.下", "8.下", "8.أسفل")
      8 -> pick(lang, "9.좌", "9.Left", "9.左", "9.左", "9.يسار")
      9 -> pick(lang, "10.우", "10.Right", "10.右", "10.右", "10.يمين")
      else -> pick(lang, "11.랜덤", "11.Random", "11.ランダム", "11.随机", "11.عشوائي")
    }
  }

  fun unitName(lang: String, mul: Int): String {
    return when (mul) {
      60000 -> pick(lang, "분", "min", "分", "分", "دقيقة")
      1000 -> pick(lang, "초", "sec", "秒", "秒", "ثانية")
      else -> pick(lang, "밀리초", "ms", "ミリ秒", "毫秒", "ميلي ثانية")
    }
  }

  fun markerKindName(lang: String, kind: String): String {
    return when (kind) {
      "click" -> pick(lang, "1.순번실행", "1.Ordered", "1.順番", "1.顺序", "1.بالترتيب")
      "independent" -> pick(lang, "2.독립실행", "2.Independent", "2.独立", "2.独立", "2.مستقل")
      "swipe" -> pick(lang, "3.스와이프", "3.Swipe", "3.スワイプ", "3.滑动", "3.سحب")
      "solo_main" -> pick(lang, "4.단독실행", "4.Solo", "4.単独", "4.单独", "4.منفرد")
      "module" -> pick(lang, "5.방향모듈", "5.Direction module", "5.方向モジュール", "5.方向模块", "5.وحدة الاتجاه")
      "color_module" -> pick(lang, "6.색상모듈", "6.Color module", "6.カラーモジュール", "6.颜色模块", "6.وحدة الألوان")
      "image_module" -> pick(lang, "7.이미지모듈", "7.Image module", "7.画像モジュール", "7.图片模块", "7.وحدة الصورة")
      else -> kind
    }
  }

  fun screenShareRequired(lang: String) =
    pick(lang, "화면공유 필요", "Screen share required", "画面共有が必要", "需要屏幕共享", "مشاركة الشاشة مطلوبة")

  fun colorModuleNeedsShare(lang: String) =
    pick(
      lang,
      "6.색상모듈 (화면공유 권한 필요: 색상 시작으로 캡처 허용 후 사용)",
      "6.Color module (needs screen share permission: allow capture via Color Start)",
      "6.カラーモジュール(画面共有が必要: 色開始でキャプチャ許可)",
      "6.颜色模块（需要屏幕共享权限：用“颜色开始”允许捕获）",
      "6.وحدة الألوان (تتطلب مشاركة الشاشة: اسمح بالالتقاط عبر بدء الألوان)"
    )

  // ---------------- 도움말(설정 메뉴) ----------------
  fun helpItemTitle(lang: String, idx: Int): String {
    return when (idx) {
      1 -> pick(lang, "1) 시작 화면", "1) Start screen", "1) 開始画面", "1) 启动界面", "1) شاشة البدء")
      2 -> pick(lang, "2) 화면공유 권한 요청", "2) Screen share permission", "2) 画面共有権限", "2) 屏幕共享权限", "2) إذن مشاركة الشاشة")
      3 -> pick(lang, "3) 메뉴 툴바(기본)", "3) Menu toolbar (basic)", "3) メニューツールバー(基本)", "3) 菜单工具栏(基础)", "3) شريط الأدوات (أساسي)")
      4 -> pick(lang, "4) 설정창(패널)", "4) Settings panel", "4) 設定(パネル)", "4) 设置面板", "4) لوحة الإعدادات")
      5 -> pick(lang, "5) 기본설정(화면설정)", "5) Basic (screen settings)", "5) 基本設定(画面)", "5) 基本设置(屏幕)", "5) الإعدادات الأساسية (الشاشة)")
      6 -> pick(lang, "6) 설정 메뉴", "6) Settings menu", "6) 設定メニュー", "6) 设置菜单", "6) قائمة الإعدادات")
      7 -> pick(lang, "7) 메뉴 툴바(추가 버튼)", "7) Menu toolbar (extra button)", "7) ツールバー(追加ボタン)", "7) 工具栏(附加按钮)", "7) شريط الأدوات (زر إضافي)")
      else -> idx.toString()
    }
  }

  fun helpItemBody(lang: String, idx: Int): String {
    return when (idx) {
      1 ->
        pick(
          lang,
          "- 오토클릭짱(색상) 시작: 화면공유(캡처) 권한이 필요합니다.\n- 오토클릭짱(기본) 시작: 캡처 없이 실행합니다.\n- 접근성 설정 확인: 접근성 권한이 필요합니다.",
          "- Color Start: screen share (capture) permission is required.\n- Basic Start: runs without capture.\n- Check accessibility: accessibility permission is required.",
          "- 色開始: 画面共有(キャプチャ)権限が必要です。\n- 基本開始: キャプチャなしで実行します。\n- アクセシビリティ確認: 権限が必要です。",
          "- 颜色开始：需要屏幕共享（捕获）权限。\n- 基本开始：不使用捕获运行。\n- 检查无障碍：需要无障碍权限。",
          "- بدء الألوان: يتطلب إذن مشاركة الشاشة (التقاط).\n- البدء الأساسي: يعمل بدون التقاط.\n- التحقق من تسهيلات الاستخدام: يتطلب إذن الوصول."
        )
      2 ->
        pick(
          lang,
          "- '전체 화면 공유' 선택 후 '화면 공유'를 누르면 캡처가 시작됩니다.\n- 캡처가 중지되면 다시 시작이 필요합니다.",
          "- Select 'Entire screen' then tap 'Start now' to begin capture.\n- If capture stops, you need to start it again.",
          "- 「全画面共有」を選択し「開始」を押すとキャプチャ가 시작됩니다。\n- 中断되면 다시 시작이 필요합니다。",
          "- 选择“共享整个屏幕”后点击“开始”，即可开始捕获。\n- 捕获中断后需要重新开始。",
          "- اختر \"مشاركة الشاشة بالكامل\" ثم اضغط \"بدء\" لبدء الالتقاط.\n- إذا توقف الالتقاط، يلزم البدء مرة أخرى."
        )
      3 ->
        pick(
          lang,
          "- ▶: 실행/정지\n- +: 마커 추가\n- ✏: 편집 모드(마커 이동)\n- 🗑: 전체 삭제\n- 👁: 객체 보기 토글\n- ⚙: 설정\n- ✖: 종료\n- ≡: 드래그 이동",
          "- ▶: start/stop\n- +: add marker\n- ✏: edit mode (move markers)\n- 🗑: delete all\n- 👁: toggle objects\n- ⚙: settings\n- ✖: exit\n- ≡: drag to move",
          "- ▶: 開始/停止\n- +: マーカー追加\n- ✏: 編集モード(移動)\n- 🗑: 全削除\n- 👁: 表示切替\n- ⚙: 設定\n- ✖: 終了\n- ≡: ドラッグ移動",
          "- ▶：开始/停止\n- +：添加标记\n- ✏：编辑模式（移动）\n- 🗑：全部删除\n- 👁：显示切换\n- ⚙：设置\n- ✖：退出\n- ≡：拖动移动",
          "- ▶: بدء/إيقاف\n- +: إضافة علامة\n- ✏: وضع التعديل (تحريك العلامات)\n- 🗑: حذف الكل\n- 👁: تبديل إظهار العناصر\n- ⚙: الإعدادات\n- ✖: خروج\n- ≡: سحب للتحريك"
        )
      4 ->
        pick(
          lang,
          "- 언어, 화면설정/색상패널\n- 중지조건(무한대/시간/사이클)\n- 메크로 저장/열기\n- 접근성 설정, 닫기",
          "- Language, screen settings / color panel\n- Stop condition (infinite / time / cycles)\n- Save/open macro\n- Accessibility settings, close",
          "- 言語, 画面設定/カラーパネル\n- 停止条件(無限/時間/サイクル)\n- マクロ保存/開く\n- アクセシビリティ設定, 閉じる",
          "- 语言、屏幕设置/颜色面板\n- 停止条件（无限/时间/循环）\n- 保存/打开宏\n- 无障碍设置、关闭",
          "- اللغة، إعدادات الشاشة/لوحة الألوان\n- شرط الإيقاف (غير محدود/وقت/دورات)\n- حفظ/فتح الماكرو\n- إعدادات تسهيلات الاستخدام، إغلاق"
        )
      5 ->
        pick(
          lang,
          "- 툴바 투명도/크기, 객체 크기\n- 마커 클릭 pressMs\n- 이미지 10차 검증 인터벌\n- 실행확률\n- 터치표시/로그보기",
          "- Toolbar opacity/size, object size\n- Marker click pressMs\n- Image 10x verify interval\n- Run probability\n- Touch indicator / View logs",
          "- ツールバー透明度/サイズ, オブジェクトサイズ\n- クリック pressMs\n- 画像10回検証インターバル\n- 実行確率\n- タッチ表示/ログ表示",
          "- 工具栏透明度/大小、对象大小\n- 点击 pressMs\n- 图片10次验证间隔\n- 执行概率\n- 触摸显示/日志",
          "- شفافية/حجم الشريط، حجم العناصر\n- مدة الضغط pressMs\n- فاصل التحقق 10 مرات للصورة\n- احتمال التنفيذ\n- مؤشر اللمس/عرض السجل"
        )
      6 ->
        pick(
          lang,
          "- 도움말 / 스크립터 / 앱 평가 / 앱 공유 / 피드백 / 크레딧 / 개인정보보호정책",
          "- Help / Scripter / Rate app / Share app / Feedback / Credits / Privacy policy",
          "- ヘルプ / スクリプター / 評価 / 共有 / フィードバック / クレジット / プライバシー",
          "- 帮助 / 脚本 / 评分 / 分享 / 反馈 / 致谢 / 隐私政策",
          "- مساعدة / سكربتر / تقييم / مشاركة / ملاحظات / الشكر / سياسة الخصوصية"
        )
      7 ->
        pick(
          lang,
          "- 통과(밑에앱 터치) 같은 추가 토글 버튼이 포함된 예시입니다.\n- 아이콘 구성은 버전에 따라 달라질 수 있습니다.",
          "- Example with extra toggle buttons such as Pass-through (touch underlying app).\n- Icons may differ by version/device.",
          "- 「通過(下の앱にタッチ)」など追加トグルの例です。\n- アイコンは 버전/기기에 따라 다를 수 있습니다。",
          "- 示例：包含“穿透（触摸底层应用）”等附加开关按钮。\n- 图标可能因版本/设备而不同。",
          "- مثال يتضمن أزرار تبديل إضافية مثل التمرير (لمس التطبيق أسفل).\n- قد تختلف الأيقونات حسب الإصدار/الجهاز."
        )
      else -> ""
    }
  }
}

