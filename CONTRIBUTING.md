# 开发与验证

本分支以 V3.2.0 的任务切换流程为基础，优先保持已验证的行为。授权统一通过 `PrivilegedAccess`，各功能绑定自己的 `ServiceConnection`；解绑单个功能不能终止其他功能共享的服务。AxManager 优先，已授权的 Shizuku 作为后备。

Android 最低 API 为 24。新增 Java API 必须兼容该版本，或提供版本检查和兼容路径。服务之间的 AIDL 编号保持稳定；接口实现变化时统一更新 `PrivilegedAccess` 中的 Shizuku 服务版本。

应用内的动态广播通过 `ContextCompat.registerReceiver` 注册为不对外导出，发送时明确指定本应用包名。通知应用选择器需要列出任意已安装应用，因此保留 `QUERY_ALL_PACKAGES` 并在权限声明处说明用途；不要用全局 lint 忽略规则掩盖实际错误。

特权命令由 `TaskService.shell()` 创建。应用代码不得另行执行无授权的 shell，也不要依赖 AxManager 的 Shizuku 拦截模式。任务迁移完成后检查实际显示屏，不能仅根据 shell 命令的退出码判定成功。

强制显示区域尚未支持。未验证的厂商接口不能作为切换的前置条件，也不能修改或结束正在迁移的应用。真机记录应区分用户手动操作、ADB 状态读取和自动测试结果。

## 代码检查

Dart 使用两空格缩进并由 `dart format` 格式化。Java 与 PowerShell 使用四空格缩进。文本编码、换行和尾部空白规则见 `.editorconfig` 与 `.gitattributes`。保留原作者与许可证信息。

提交前运行：

```text
flutter pub get
dart format --output=none --set-exit-if-changed lib test
flutter analyze --no-pub --fatal-infos
flutter test --no-pub --reporter expanded
flutter build apk --release --target-platform android-arm64 --no-pub
```

构建后运行 Android 检查，Windows 使用 `gradlew.bat`：

```text
android/gradlew -p android :app:testReleaseUnitTest :app:lintRelease -Ptarget-platform=android-arm64 --console=plain
```

Windows 可直接运行 `scripts/build-android.ps1 -JdkPath 'JDK 21 目录'`，执行上述完整流程。脚本默认从 PATH 查找 Flutter，也可通过 `-FlutterPath` 指定其路径；Java、代理和跨盘 Kotlin 缓存调整仅在本次构建中生效。

`build/` 中的 APK、设备截图、日志和工具链不入库。不要提交 `android/local.properties`、`android/key.properties`、签名密钥或个人环境配置。自动检查由 `.github/workflows/checks.yml` 对所有分支及拉取请求执行。
