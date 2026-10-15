# 测试指南

```sh
./gradlew :domain:test :root:test :system:testDebugUnitTest
python3 tests/test_engine.py
./gradlew :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

主机测试使用真实engine.sh但替换Android命令和数据路径，只验证编排/异常路径，不能证明SELinux、FBE、UID/su或OEM行为。替身已包含 `id`，因此**不要求以 root 运行**；但需要 Linux/GNU coreutils（`flock`、`sha256sum`、GNU `stat -c`），缺失时测试会明确失败并打印真实原因，不静默跳过。失败用例断言具体拒绝原因（如 `shared UID not supported`），环境问题不会被误判为“正确拒绝”。

默认Android测试覆盖API与任务持久化。只读Root smoke须显式启用：

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.rootTests=true
```

迁移测试只在可重置的专用Android16 Root设备执行：安装fixture-debug.apk；创建一个可删除的完整用户（下例10仅示意）；启动并解锁两侧；通过pm install-existing把fixture注册到目标；分别切换并启动fixture一次创建不同UID的CE/DE seed，再切回管理器用户。

```sh
adb shell su -c 'pm install-existing --user 10 top.writerpass.multiusers.fixture'
./gradlew :app:connectedDebugAndroidTest \
 -Pandroid.testInstrumentationRunnerArguments.migrationTests=true \
 -Pandroid.testInstrumentationRunnerArguments.targetUser=10
```

测试仅操作top.writerpass.multiusers.fixture：复制、比较CE/DE、验证归档、恢复原目标。快照保留以便排查，不自动删除。

必须真机覆盖：Magisk/KernelSU/APatch授权拒绝/恢复；AOSP16及OxygenOS；完整用户/工作资料创建删除启动停止切换；保护user0/当前/管理器用户；APK签名冲突、split既有安装；CE锁定、外置卷/共享UID/链接拒绝；真实UID/GID/context；空间耗尽、强停/断电、归档损坏、APK升级、用户ID复用；Keystore应用重新登录/业务验证。未执行项不得标“兼容”。
