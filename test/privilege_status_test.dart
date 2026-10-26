import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mrss/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  const channel = MethodChannel('com.display.switcher/task');
  final binding = TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  var backend = 'AxManager';

  setUp(() {
    backend = 'AxManager';
    calls.clear();
    SharedPreferences.setMockInitialValues({
      'charging_animation_enabled': false,
    });
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      calls.add(call);
      switch (call.method) {
        case 'getPrivilegeStatus':
          return {'granted': true, 'backend': backend, 'checking': false};
        case 'getCurrentRearDpi':
          return 320;
        case 'getDisplayRotation':
          return 0;
        case 'checkNotificationListenerPermission':
          return true;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    binding.platformDispatcher.clearLocaleTestValue();
    binding.platformDispatcher.clearLocalesTestValue();
  });

  Future<void> openApp(WidgetTester tester) async {
    await tester.pumpWidget(const DisplaySwitcherApp());
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();
  }

  testWidgets('shows AxManager as the active authorization backend', (
    tester,
  ) async {
    await openApp(tester);
    expect(find.text('All Set · AxManager'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shows Shizuku when native access uses the fallback', (
    tester,
  ) async {
    backend = 'Shizuku';
    await openApp(tester);
    expect(find.text('All Set · Shizuku'), findsOneWidget);
  });

  testWidgets('explains unsupported display areas without offering a control', (
    tester,
  ) async {
    await openApp(tester);
    expect(find.byType(DropdownButton<bool>), findsNothing);
    expect(find.textContaining('Forcing full screen'), findsOneWidget);
    expect(
      calls.where((call) => call.method == 'setRearFullscreenEnabled'),
      isEmpty,
    );
  });

  testWidgets('leaves existing display preferences untouched', (tester) async {
    SharedPreferences.setMockInitialValues({
      'charging_animation_enabled': false,
      'rear_fullscreen_enabled': false,
    });
    await openApp(tester);
    final preferences = await SharedPreferences.getInstance();
    expect(preferences.getBool('rear_fullscreen_enabled'), isFalse);
    expect(
      calls.where((call) => call.method == 'setRearFullscreenEnabled'),
      isEmpty,
    );
  });

  testWidgets(
    'refreshes the backend on resume and requests notifications once',
    (tester) async {
      await openApp(tester);
      backend = 'Shizuku';
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('All Set · Shizuku'), findsOneWidget);
      expect(calls.any((call) => call.method == 'refreshPrivilege'), isTrue);
      expect(
        calls.where((call) => call.method == 'requestNotificationPermission'),
        hasLength(1),
      );
    },
  );

  testWidgets('shows the display limitation in Chinese', (tester) async {
    binding.platformDispatcher.localesTestValue = const [Locale('zh', 'CN')];
    await openApp(tester);
    expect(find.text('一切就绪 · AxManager'), findsOneWidget);
    expect(find.textContaining('强制全屏或仅摄像头右侧'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
