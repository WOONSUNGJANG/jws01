// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:color_picker_tool/main.dart';

void main() {
  testWidgets('앱이 렌더링된다', (WidgetTester tester) async {
    await tester.pumpWidget(const ColorPickerApp());

    // 기본 UI가 뜨는지만 확인(픽셀 캡처는 환경/프레임 타이밍에 따라 달라질 수 있음)
    expect(find.text('오토클릭짱'), findsOneWidget);
    expect(find.text('오토클릭짱 시작'), findsOneWidget);
  });
}
