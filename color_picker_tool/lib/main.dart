import 'dart:ui' as ui;
import 'dart:io';

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

void main() => runApp(const ColorPickerApp());

const MethodChannel _systemChannel = MethodChannel('system_color_picker');

class ColorPickerApp extends StatefulWidget {
  const ColorPickerApp({super.key});

  @override
  State<ColorPickerApp> createState() => _ColorPickerAppState();
}

class _ColorPickerAppState extends State<ColorPickerApp> {
  String _lang = 'ko';

  @override
  void initState() {
    super.initState();
    _loadLanguage();
  }

  Future<void> _loadLanguage() async {
    try {
      final code = await _systemChannel.invokeMethod<String>('getLanguage');
      if (!mounted) return;
      setState(() => _lang = (code ?? 'ko').trim().isEmpty ? 'ko' : code!.trim());
    } catch (_) {
      // ignore
    }
  }

  Locale _localeForLang(String code) {
    switch (code) {
      case 'en':
        return const Locale('en');
      case 'ja':
        return const Locale('ja');
      case 'zh':
        return const Locale('zh');
      case 'ar':
        return const Locale('ar');
      default:
        return const Locale('ko');
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = ColorScheme.fromSeed(seedColor: Colors.indigo);
    final locale = _localeForLang(_lang);
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      locale: locale,
      supportedLocales: const [
        Locale('ko'),
        Locale('en'),
        Locale('ja'),
        Locale('zh'),
        Locale('ar'),
      ],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      title: AppStrings.t(_lang, 'appTitle'),
      theme: ThemeData(colorScheme: colorScheme, useMaterial3: true),
      builder: (context, child) {
        if (child == null) return const SizedBox.shrink();
        return AppStringsScope(lang: _lang, child: child);
      },
      home: const SystemOverlayPage(),
    );
  }
}

class AppStringsScope extends InheritedWidget {
  const AppStringsScope({required this.lang, required super.child, super.key});
  final String lang;

  static AppStringsScope of(BuildContext context) {
    final w = context.dependOnInheritedWidgetOfExactType<AppStringsScope>();
    return w ?? const AppStringsScope(lang: 'ko', child: SizedBox.shrink());
  }

  @override
  bool updateShouldNotify(covariant AppStringsScope oldWidget) => oldWidget.lang != lang;
}

class AppStrings {
  static String lang(BuildContext context) => AppStringsScope.of(context).lang;

  static String t(String lang, String key) {
    // ko/en/ja/zh/ar
    const ko = <String, String>{
      'appTitle': '오토클릭짱',
      'startColor': '오토클릭짱(이미지) 시작',
      'startBasic': '오토클릭짱(기본) 시작',
      'screenSharePermissionBtn': '화면공유 권한설정',
      'exitApp': '앱 종료',
      'lastCrashTitle': '마지막 크래시(원인 스택)',
      'lastCrashCopy': '복사',
      'lastCrashClear': '지우기',
      'lastCrashCopied': '크래시 스택이 복사되었습니다.',
      'riskCapture':
          '화면 캡처(화면 공유) 권한 위험성:\n'
          '- 화면에 표시되는 내용(알림/메신저/비밀번호/개인정보 등)이 캡처 대상이 될 수 있습니다.\n'
          '- 캡처된 화면은 앱 기능(색상 인식/조건 판단 등)에 사용될 수 있으므로, 신뢰할 때만 허용하세요.\n'
          '- 필요 없으면 즉시 권한을 해제/중지하세요.',
      'riskAccessibility':
          '접근성 권한 위험성:\n'
          '- 다른 앱을 대신 터치/스와이프/입력할 수 있는 민감 권한입니다.\n'
          '- 악용될 경우 원치 않는 클릭/설정 변경/결제 등으로 이어질 수 있습니다.\n'
          '- 반드시 신뢰할 수 있을 때만 허용하고, 사용 후 권한을 해제하세요.',
      'accessibilityCheckBtn': '접근성 설정 확인',
      'needAccessibility': '접근성 권한이 필요합니다. 접근성 설정을 켠 후 다시 시작해주세요.',
      'needOverlay': '오버레이 권한이 필요합니다. 설정 화면에서 허용 후 다시 시작해주세요.',
      'needNotif': '알림 권한이 필요합니다(Android 13+). 허용 후 다시 시작해주세요.',
      'mpDenied': '화면 캡처 권한이 거부되었습니다. 다시 시도해주세요.',
      'startFail': '시작에 실패했습니다.',
      'hexCopy': 'HEX 복사',
      'hexCopied': 'HEX 값이 복사되었습니다.',
      'recapture': '화면 다시 캡처',
      'tapDragHint': '툴바 아래 화면을 탭/드래그해서 색상을 읽습니다',
      'coordNone': '(좌표 미선택)',
    };
    const en = <String, String>{
      'appTitle': 'AutoClickzzang',
      'startColor': 'Start (Color)',
      'startBasic': 'Start (Basic)',
      'screenSharePermissionBtn': 'Set screen-share permission',
      'exitApp': 'Exit app',
      'lastCrashTitle': 'Last crash (stack trace)',
      'lastCrashCopy': 'Copy',
      'lastCrashClear': 'Clear',
      'lastCrashCopied': 'Crash stack copied.',
      'riskCapture':
          'Screen capture (screen sharing) risks:\n'
          '- Sensitive content (notifications, messages, passwords, personal data) may be captured.\n'
          '- Captured frames can be used for app features (color detection/conditions). Allow only if you trust it.\n'
          '- Disable/stop immediately when not needed.',
      'riskAccessibility':
          'Accessibility permission risks:\n'
          '- This is a sensitive permission that can tap/swipe/input in other apps.\n'
          '- If abused, it may cause unwanted clicks/settings changes/payments.\n'
          '- Allow only when you trust it, and revoke after use.',
      'accessibilityCheckBtn': 'Check accessibility settings',
      'needAccessibility': 'Accessibility permission is required. Enable it and try again.',
      'needOverlay': 'Overlay permission is required. Allow it in Settings and try again.',
      'needNotif': 'Notification permission is required (Android 13+). Allow it and try again.',
      'mpDenied': 'Screen capture permission was denied. Please try again.',
      'startFail': 'Failed to start.',
      'hexCopy': 'Copy HEX',
      'hexCopied': 'HEX copied.',
      'recapture': 'Recapture',
      'tapDragHint': 'Tap/drag below the toolbar to read the color',
      'coordNone': '(no coordinate)',
    };
    const ja = <String, String>{
      'appTitle': 'オートクリックちゃん',
      'startColor': '開始(色)',
      'startBasic': '開始(基本)',
      'screenSharePermissionBtn': '画面共有権限の設定',
      'exitApp': 'アプリ終了',
      'lastCrashTitle': '直近クラッシュ(スタック)',
      'lastCrashCopy': 'コピー',
      'lastCrashClear': 'クリア',
      'lastCrashCopied': 'クラッシュ内容をコピーしました。',
      'riskCapture':
          '画面キャプチャ(共有)のリスク:\n'
          '- 画面上の内容(通知/メッセージ/パスワード/個人情報など)がキャプチャされる可能性があります。\n'
          '- キャプチャ画像は機能(色認識/条件判定など)に使用されます。信頼できる場合のみ許可してください。\n'
          '- 不要になったらすぐに停止/許可解除してください。',
      'riskAccessibility':
          'ユーザー補助(アクセシビリティ)権限のリスク:\n'
          '- 他のアプリをタップ/スワイプ/入力できる حساس な権限です。\n'
          '- 悪用されると意図しない操作/設定変更/決済につながる可能性があります。\n'
          '- 信頼できる場合のみ許可し、使用後は解除してください。',
      'accessibilityCheckBtn': 'アクセシビリティ確認',
      'needAccessibility': 'アクセシビリティ権限が必要です。設定で有効にしてから再試行してください。',
      'needOverlay': 'オーバーレイ権限が必要です。設定で許可してから再試行してください。',
      'needNotif': '通知権限が必要です(Android 13+)。許可してから再試行してください。',
      'mpDenied': '画面キャプチャ権限が拒否されました。もう一度お試しください。',
      'startFail': '開始に失敗しました。',
      'hexCopy': 'HEXコピー',
      'hexCopied': 'HEXをコピーしました。',
      'recapture': '再キャプチャ',
      'tapDragHint': 'ツールバー下をタップ/ドラッグして色を取得します',
      'coordNone': '(未選択)',
    };
    const zh = <String, String>{
      'appTitle': '自动点击王',
      'startColor': '开始(颜色)',
      'startBasic': '开始(基础)',
      'screenSharePermissionBtn': '设置屏幕共享权限',
      'exitApp': '退出应用',
      'lastCrashTitle': '最近崩溃(堆栈)',
      'lastCrashCopy': '复制',
      'lastCrashClear': '清除',
      'lastCrashCopied': '已复制崩溃堆栈。',
      'riskCapture':
          '屏幕捕获(屏幕共享)风险:\n'
          '- 屏幕内容(通知/消息/密码/隐私等)可能被捕获。\n'
          '- 捕获画面可能用于功能(颜色识别/条件判断等)，仅在信任时允许。\n'
          '- 不需要时请立即停止/撤销权限。',
      'riskAccessibility':
          '无障碍权限风险:\n'
          '- 这是敏感权限，可在其他应用中点击/滑动/输入。\n'
          '- 若被滥用，可能导致非预期点击/设置更改/支付等。\n'
          '- 仅在信任时允许，使用后请撤销。',
      'accessibilityCheckBtn': '检查无障碍设置',
      'needAccessibility': '需要无障碍权限。请在设置中开启后重试。',
      'needOverlay': '需要悬浮窗权限。请在设置中允许后重试。',
      'needNotif': '需要通知权限(Android 13+)。允许后重试。',
      'mpDenied': '屏幕捕获权限被拒绝。请重试。',
      'startFail': '启动失败。',
      'hexCopy': '复制HEX',
      'hexCopied': 'HEX已复制。',
      'recapture': '重新捕获',
      'tapDragHint': '点击/拖动工具栏下方以读取颜色',
      'coordNone': '(未选择坐标)',
    };
    const ar = <String, String>{
      'appTitle': 'النقر التلقائي',
      'startColor': 'بدء (ألوان)',
      'startBasic': 'بدء (أساسي)',
      'screenSharePermissionBtn': 'إعداد إذن مشاركة الشاشة',
      'exitApp': 'إغلاق التطبيق',
      'lastCrashTitle': 'آخر تعطل (stack)',
      'lastCrashCopy': 'نسخ',
      'lastCrashClear': 'مسح',
      'lastCrashCopied': 'تم نسخ تفاصيل التعطل.',
      'riskCapture':
          'مخاطر مشاركة/التقاط الشاشة:\n'
          '- قد يتم التقاط محتوى حساس (إشعارات/رسائل/كلمات مرور/بيانات شخصية).\n'
          '- قد تُستخدم اللقطات لميزات التطبيق (كشف الألوان/الشروط). اسمح فقط إذا كنت تثق.\n'
          '- أوقف/ألغِ الإذن فوراً عند عدم الحاجة.',
      'riskAccessibility':
          'مخاطر إذن تسهيلات الاستخدام:\n'
          '- إذن حساس يمكنه النقر/السحب/الإدخال داخل تطبيقات أخرى.\n'
          '- قد يؤدي سوء الاستخدام إلى نقرات/تغييرات إعدادات/مدفوعات غير مرغوبة.\n'
          '- اسمح فقط عند الثقة، وألغِ الإذن بعد الاستخدام.',
      'accessibilityCheckBtn': 'التحقق من إعدادات تسهيلات الاستخدام',
      'needAccessibility': 'يلزم إذن تسهيلات الاستخدام. فعّله ثم أعد المحاولة.',
      'needOverlay': 'يلزم إذن الظهور فوق التطبيقات. اسمح به من الإعدادات ثم أعد المحاولة.',
      'needNotif': 'يلزم إذن الإشعارات (Android 13+). اسمح به ثم أعد المحاولة.',
      'mpDenied': 'تم رفض إذن التقاط الشاشة. حاول مرة أخرى.',
      'startFail': 'فشل البدء.',
      'hexCopy': 'نسخ HEX',
      'hexCopied': 'تم نسخ HEX.',
      'recapture': 'إعادة الالتقاط',
      'tapDragHint': 'انقر/اسحب أسفل الشريط لقراءة اللون',
      'coordNone': '(لا يوجد تحديد)',
    };

    final dict = switch (lang) {
      'en' => en,
      'ja' => ja,
      'zh' => zh,
      'ar' => ar,
      _ => ko,
    };
    return dict[key] ?? ko[key] ?? key;
  }
}

class SystemOverlayPage extends StatefulWidget {
  const SystemOverlayPage({super.key});

  @override
  State<SystemOverlayPage> createState() => _SystemOverlayPageState();
}

class _SystemOverlayPageState extends State<SystemOverlayPage> {
  bool _starting = false;
  late final Future<String?> _winVersionFuture;
  Future<String?>? _lastCrashFuture;

  Future<String?> _getWindowsVersionInfo() async {
    if (kIsWeb) return null;
    if (!Platform.isWindows) return null;
    final os = Platform.operatingSystemVersion.trim();
    String buildDate = '';
    try {
      final exe = Platform.resolvedExecutable;
      final st = await File(exe).stat();
      final dt = st.modified.toLocal();
      String two(int n) => n.toString().padLeft(2, '0');
      buildDate =
          '${dt.year}-${two(dt.month)}-${two(dt.day)} ${two(dt.hour)}:${two(dt.minute)}:${two(dt.second)}';
    } catch (_) {}
    if (os.isEmpty && buildDate.isEmpty) return null;
    final b = buildDate.isNotEmpty ? ' / 컴파일: $buildDate' : '';
    return 'Windows: $os$b';
  }

  Future<void> _openAccessibilitySettings() async {
    try {
      await _systemChannel.invokeMethod('openAccessibilitySettings');
    } catch (_) {}
  }

  Future<void> _exitApp() async {
    // (요청) 3번째 버튼 클릭 시 앱 종료
    try {
      // 가능하면 오버레이도 같이 정리(실패해도 종료는 수행)
      await _systemChannel.invokeMethod('stopSystemPicker');
    } catch (_) {}
    SystemNavigator.pop();
  }

  Future<String?> _getLastNativeCrash() async {
    if (kIsWeb) return null;
    if (!Platform.isAndroid) return null;
    try {
      final s = await _systemChannel.invokeMethod<String>('getLastNativeCrash');
      final v = (s ?? '').trim();
      return v.isEmpty ? null : v;
    } catch (_) {
      return null;
    }
  }

  Future<void> _clearLastNativeCrash() async {
    if (kIsWeb) return;
    if (!Platform.isAndroid) return;
    try {
      await _systemChannel.invokeMethod<bool>('clearLastNativeCrash');
    } catch (_) {}
    if (!mounted) return;
    setState(() => _lastCrashFuture = _getLastNativeCrash());
  }

  Future<void> _startSystemPicker({required bool exitOnSuccess}) async {
    if (_starting) return;
    setState(() => _starting = true);
    var started = false;
    try {
      final lang = AppStrings.lang(context);
      // (요청) 시작 버튼을 누른 시점에만 접근성 체크/툴바 표시
      try {
        final ok =
            await _systemChannel.invokeMethod<bool>('ensurePermissionsAndShowToolbar');
        if (ok != true) {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(AppStrings.t(lang, 'needAccessibility'))),
          );
          return;
        }
      } catch (_) {}
      final res = await _systemChannel.invokeMethod<Map<Object?, Object?>>(
        'startSystemPicker',
      );
      if (!mounted) return;

      started = res?['started'] == true;
      final reason = res?['reason']?.toString();

      if (!started) {
        final msg = switch (reason) {
          'needs_overlay_permission' =>
            AppStrings.t(lang, 'needOverlay'),
          'needs_notification_permission' =>
            AppStrings.t(lang, 'needNotif'),
          'media_projection_denied' => AppStrings.t(lang, 'mpDenied'),
          _ => '${AppStrings.t(lang, 'startFail')} ($reason)',
        };
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(msg)));
        return;
      }
    } finally {
      if (mounted) setState(() => _starting = false);
    }
    if (!started) return;
    try {
      await _systemChannel.invokeMethod<bool>('showAccessibilityToolbar');
    } catch (_) {}
    if (exitOnSuccess) {
      // (요청) 시작 성공한 경우에만 앱 화면 종료(실패 시에는 "크래시"로 보일 수 있어 유지)
      if (mounted) {
        await Future<void>.delayed(const Duration(milliseconds: 120));
      }
      SystemNavigator.pop();
    }
  }

  Future<void> _startBasic() async {
    if (_starting) return;
    setState(() => _starting = true);
    var started = false;
    try {
      final lang = AppStrings.lang(context);
      // (요청) 시작 버튼을 누른 시점에만 접근성 체크/툴바 표시
      try {
        final ok =
            await _systemChannel.invokeMethod<bool>('ensurePermissionsAndShowToolbar');
        if (ok != true) {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(AppStrings.t(lang, 'needAccessibility'))),
          );
          return;
        }
      } catch (_) {}
      final res = await _systemChannel.invokeMethod<Map<Object?, Object?>>(
        'startBasicPicker',
      );
      if (!mounted) return;

      started = res?['started'] == true;
      final reason = res?['reason']?.toString();

      if (!started) {
        final msg = switch (reason) {
          'needs_overlay_permission' =>
            AppStrings.t(lang, 'needOverlay'),
          _ => '${AppStrings.t(lang, 'startFail')} ($reason)',
        };
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(msg)));
        return;
      }
    } finally {
      if (mounted) setState(() => _starting = false);
    }
    if (!started) return;
    try {
      await _systemChannel.invokeMethod<bool>('showAccessibilityToolbar');
    } catch (_) {}
    // (요청) 시작 성공한 경우에만 앱 화면 종료(실패 시에는 "크래시"로 보일 수 있어 유지)
    if (mounted) {
      await Future<void>.delayed(const Duration(milliseconds: 120));
    }
    SystemNavigator.pop();
  }

  @override
  void initState() {
    super.initState();
    // (요청) 앱 시작 시 툴바는 숨김 상태로 유지.
    try {
      _systemChannel.invokeMethod<bool>('hideAccessibilityToolbar');
    } catch (_) {}
    _winVersionFuture = _getWindowsVersionInfo();
    _lastCrashFuture = _getLastNativeCrash();
  }

  @override
  Widget build(BuildContext context) {
    final lang = AppStrings.lang(context);
    final sc = ScrollController();
    return Scaffold(
      appBar: AppBar(title: Text(AppStrings.t(lang, 'appTitle'))),
      body: SafeArea(
        child: Scrollbar(
          controller: sc,
          thumbVisibility: true,
          child: SingleChildScrollView(
            controller: sc,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  FutureBuilder<String?>(
                    future: _lastCrashFuture,
                    builder: (context, snap) {
                      final v = (snap.data ?? '').trim();
                      if (v.isEmpty) return const SizedBox.shrink();
                      return Card(
                        elevation: 0,
                        color: Theme.of(context).colorScheme.errorContainer,
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      AppStrings.t(lang, 'lastCrashTitle'),
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleSmall
                                          ?.copyWith(
                                            fontWeight: FontWeight.w700,
                                          ),
                                    ),
                                  ),
                                  TextButton(
                                    onPressed: () async {
                                      await Clipboard.setData(
                                        ClipboardData(text: v),
                                      );
                                      if (!context.mounted) return;
                                      ScaffoldMessenger.of(context).showSnackBar(
                                        SnackBar(
                                          content: Text(
                                            AppStrings.t(lang, 'lastCrashCopied'),
                                          ),
                                        ),
                                      );
                                    },
                                    child: Text(AppStrings.t(lang, 'lastCrashCopy')),
                                  ),
                                  TextButton(
                                    onPressed: _clearLastNativeCrash,
                                    child: Text(
                                      AppStrings.t(lang, 'lastCrashClear'),
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 8),
                              Container(
                                constraints: const BoxConstraints(maxHeight: 220),
                                decoration: BoxDecoration(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .surface
                                      .withValues(alpha: 0.7),
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(
                                    color: Theme.of(context).colorScheme.outlineVariant,
                                  ),
                                ),
                                padding: const EdgeInsets.all(10),
                                child: SingleChildScrollView(
                                  child: SelectableText(
                                    v,
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodySmall
                                        ?.copyWith(fontFamily: 'monospace'),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                  if (_lastCrashFuture != null) const SizedBox(height: 12),
                  // 1) 기본 시작(접근성 기반)
                  FilledButton.tonalIcon(
                    onPressed: _starting ? null : _startBasic,
                    icon: const Icon(Icons.play_arrow),
                    label: Text(AppStrings.t(lang, 'startBasic')),
                  ),
                  // (요청) 기본 시작 버튼 아래 한 줄 공백
                  const SizedBox(height: 14),
                  // 2) 화면캡처(화면공유) 시작
                  FilledButton.icon(
                    onPressed: _starting ? null : () => _startSystemPicker(exitOnSuccess: true),
                    icon: _starting
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.play_arrow),
                    label: Text(AppStrings.t(lang, 'startColor')),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    AppStrings.t(lang, 'riskCapture'),
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: Colors.black87,
                          height: 1.38,
                        ),
                  ),
                  const SizedBox(height: 14),

                  // 접근성 설정 확인(안내)
                  OutlinedButton.icon(
                    onPressed: _openAccessibilitySettings,
                    icon: const Icon(Icons.accessibility_new),
                    label: Text(AppStrings.t(lang, 'accessibilityCheckBtn')),
                  ),
                  const SizedBox(height: 8),
                  // (요청) 이전에 있던 접근성 위험성 텍스트 복구(버튼 바로 아래)
                  Text(
                    AppStrings.t(lang, 'riskAccessibility'),
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: Colors.black87,
                          height: 1.38,
                        ),
                  ),
                  const SizedBox(height: 14),

                  // 3) 앱 종료
                  OutlinedButton.icon(
                    onPressed: _exitApp,
                    icon: const Icon(Icons.close),
                    label: Text(AppStrings.t(lang, 'exitApp')),
                  ),
                  const SizedBox(height: 10),
                  FutureBuilder<String?>(
                    future: _winVersionFuture,
                    builder: (context, snap) {
                      final v = snap.data?.trim() ?? '';
                      if (v.isEmpty) return const SizedBox.shrink();
                      return Text(
                        v,
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              fontSize: 11,
                              color: Colors.black54,
                            ),
                      );
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class ColorPickerPage extends StatefulWidget {
  const ColorPickerPage({super.key});

  @override
  State<ColorPickerPage> createState() => _ColorPickerPageState();
}

class _ColorPickerPageState extends State<ColorPickerPage> {
  static const double _toolbarHeight = 52;

  final GlobalKey _inspectBoundaryKey = GlobalKey();

  ui.Image? _inspectImage;
  ByteData? _inspectRgba;
  int _inspectImageWidth = 0;
  int _inspectImageHeight = 0;
  double _capturedPixelRatio = 1.0;

  Offset? _lastTapLocal;
  Offset? _lastTapPixel;
  Color _selected = const Color(0xFF000000);

  Size? _lastInspectSize;
  bool _isCapturing = false;

  @override
  void dispose() {
    _inspectImage?.dispose();
    super.dispose();
  }

  Future<void> _captureInspectIfNeeded({bool force = false}) async {
    if (!mounted) return;
    if (_isCapturing) return;

    final boundaryContext = _inspectBoundaryKey.currentContext;
    if (boundaryContext == null) return;

    final renderObject = boundaryContext.findRenderObject();
    if (renderObject is! RenderRepaintBoundary) return;

    final size = renderObject.size;
    if (!force && _lastInspectSize == size && _inspectRgba != null) return;

    setState(() {
      _isCapturing = true;
      _lastInspectSize = size;
    });

    try {
      final pixelRatio = MediaQuery.of(context).devicePixelRatio;
      final image = await renderObject.toImage(pixelRatio: pixelRatio);
      final rgba = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (!mounted) {
        image.dispose();
        return;
      }

      _inspectImage?.dispose();
      setState(() {
        _inspectImage = image;
        _inspectRgba = rgba;
        _inspectImageWidth = image.width;
        _inspectImageHeight = image.height;
        _capturedPixelRatio = pixelRatio;
      });
    } finally {
      if (mounted) setState(() => _isCapturing = false);
    }
  }

  Color? _colorAtLocal(Offset local) {
    final rgba = _inspectRgba;
    if (_inspectImage == null || rgba == null) return null;

    final px = (local.dx * _capturedPixelRatio).round().clamp(
      0,
      _inspectImageWidth - 1,
    );
    final py = (local.dy * _capturedPixelRatio).round().clamp(
      0,
      _inspectImageHeight - 1,
    );

    final byteOffset = ((py * _inspectImageWidth) + px) * 4;
    if (byteOffset + 3 >= rgba.lengthInBytes) return null;

    final data = rgba.buffer.asUint8List();
    final r = data[byteOffset];
    final g = data[byteOffset + 1];
    final b = data[byteOffset + 2];
    final a = data[byteOffset + 3];

    _lastTapPixel = Offset(px.toDouble(), py.toDouble());
    return Color.fromARGB(a, r, g, b);
  }

  Future<void> _handleTap(Offset localPosition) async {
    if (_inspectRgba == null || _inspectImage == null) {
      await _captureInspectIfNeeded(force: true);
    }

    final color = _colorAtLocal(localPosition);
    if (color == null) return;

    setState(() {
      _lastTapLocal = localPosition;
      _selected = color;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            _MiniToolbar(
              height: _toolbarHeight,
              color: _selected,
              lastTapLocal: _lastTapLocal,
              lastTapPixel: _lastTapPixel,
              pixelRatio: _capturedPixelRatio,
              isCapturing: _isCapturing,
              onRefresh: () async => _captureInspectIfNeeded(force: true),
            ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final inspectSize = Size(
                    constraints.maxWidth,
                    constraints.maxHeight,
                  );

                  if (_lastInspectSize != inspectSize) {
                    WidgetsBinding.instance.addPostFrameCallback((_) {
                      _captureInspectIfNeeded();
                    });
                  }

                  return RepaintBoundary(
                    key: _inspectBoundaryKey,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        const CustomPaint(painter: _ScreenPainter()),
                        // "툴바 밑 화면"에서 좌표를 탭/드래그하면 해당 픽셀 색상을 샘플링
                        Material(
                          color: Colors.transparent,
                          child: GestureDetector(
                            behavior: HitTestBehavior.opaque,
                            onTapDown: (d) async => _handleTap(d.localPosition),
                            onPanDown: (d) async => _handleTap(d.localPosition),
                            onPanUpdate: (d) async =>
                                _handleTap(d.localPosition),
                          ),
                        ),
                        Align(
                          alignment: Alignment.bottomCenter,
                          child: Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                color: Colors.black.withValues(alpha: 0.55),
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 6,
                                ),
                                child: Text(
                                  AppStrings.t(AppStrings.lang(context), 'tapDragHint'),
                                  style: const TextStyle(color: Colors.white),
                                ),
                              ),
                            ),
                          ),
                        ),
                        if (_lastTapLocal != null)
                          Positioned(
                            left: (_lastTapLocal!.dx - 10).clamp(
                              0.0,
                              inspectSize.width - 20,
                            ),
                            top: (_lastTapLocal!.dy - 10).clamp(
                              0.0,
                              inspectSize.height - 20,
                            ),
                            child: IgnorePointer(
                              child: Container(
                                width: 20,
                                height: 20,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  border: Border.all(
                                    color: Colors.white,
                                    width: 2,
                                  ),
                                  boxShadow: const [
                                    BoxShadow(
                                      blurRadius: 6,
                                      color: Colors.black26,
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                      ],
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MiniToolbar extends StatelessWidget {
  const _MiniToolbar({
    required this.height,
    required this.color,
    required this.lastTapLocal,
    required this.lastTapPixel,
    required this.pixelRatio,
    required this.isCapturing,
    required this.onRefresh,
  });

  final double height;
  final Color color;
  final Offset? lastTapLocal;
  final Offset? lastTapPixel;
  final double pixelRatio;
  final bool isCapturing;
  final VoidCallback onRefresh;

  static String _hex2(int v) =>
      v.toRadixString(16).padLeft(2, '0').toUpperCase();

  int get _r255 => (color.r * 255.0).round().clamp(0, 255);
  int get _g255 => (color.g * 255.0).round().clamp(0, 255);
  int get _b255 => (color.b * 255.0).round().clamp(0, 255);
  int get _a255 => (color.a * 255.0).round().clamp(0, 255);

  String get hexRgb => '#${_hex2(_r255)}${_hex2(_g255)}${_hex2(_b255)}';
  String get hexArgb =>
      '#${_hex2(_a255)}${_hex2(_r255)}${_hex2(_g255)}${_hex2(_b255)}';

  @override
  Widget build(BuildContext context) {
    final lang = AppStrings.lang(context);
    final coordText = (lastTapLocal == null || lastTapPixel == null)
        ? AppStrings.t(lang, 'coordNone')
        : 'local(${lastTapLocal!.dx.toStringAsFixed(0)},${lastTapLocal!.dy.toStringAsFixed(0)}) '
              'px(${lastTapPixel!.dx.toStringAsFixed(0)},${lastTapPixel!.dy.toStringAsFixed(0)}) '
              'dpr ${pixelRatio.toStringAsFixed(2)}';

    return Material(
      elevation: 3,
      color: Theme.of(context).colorScheme.surface,
      child: SizedBox(
        height: height,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10),
          child: Row(
            children: [
              Text(AppStrings.t(lang, 'appTitle'), style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(width: 10),
              Container(
                width: 20,
                height: 20,
                decoration: BoxDecoration(
                  color: color,
                  borderRadius: BorderRadius.circular(4),
                  border: Border.all(
                    color: Theme.of(context).colorScheme.outlineVariant,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '$hexRgb  (RGB $_r255,$_g255,$_b255)',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    Text(
                      coordText,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              if (isCapturing)
                const Padding(
                  padding: EdgeInsets.only(right: 6),
                  child: SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              IconButton(
                tooltip: AppStrings.t(lang, 'hexCopy'),
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: hexRgb));
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(content: Text(AppStrings.t(lang, 'hexCopied'))),
                  );
                },
                icon: const Icon(Icons.copy, size: 20),
              ),
              IconButton(
                tooltip: AppStrings.t(lang, 'recapture'),
                onPressed: onRefresh,
                icon: const Icon(Icons.refresh, size: 20),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ScreenPainter extends CustomPainter {
  const _ScreenPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;

    // 데모용 "화면" 배경 (사용자는 여기 대신 실제 UI를 배치하면 됨)
    final bg = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Color(0xFF0EA5E9), // sky
          Color(0xFFA78BFA), // violet
          Color(0xFFF97316), // orange
        ],
      ).createShader(rect);
    canvas.drawRect(rect, bg);

    // 바둑판 패턴(명암/경계가 있어 색상 변화가 잘 보이게)
    const cell = 36.0;
    final dark = Paint()..color = Colors.black.withValues(alpha: 0.08);
    for (double y = 0; y < size.height; y += cell) {
      for (double x = 0; x < size.width; x += cell) {
        final isDark = ((x / cell).floor() + (y / cell).floor()) % 2 == 0;
        if (isDark) {
          canvas.drawRect(Rect.fromLTWH(x, y, cell, cell), dark);
        }
      }
    }

    // 원형 그라데이션(픽셀 샘플링 체감용)
    final circlePaint = Paint()
      ..shader = ui.Gradient.radial(rect.center, rect.shortestSide * 0.35, [
        const Color(0xFFFFFFFF).withValues(alpha: 0.95),
        const Color(0xFFFFFFFF).withValues(alpha: 0.0),
      ]);
    canvas.drawCircle(rect.center, rect.shortestSide * 0.35, circlePaint);
  }

  @override
  bool shouldRepaint(covariant _ScreenPainter oldDelegate) => false;
}
