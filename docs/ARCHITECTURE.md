# 架构

| 模块 | 职责 | 依赖 |
|---|---|---|
|domain|纯Kotlin模型、输入验证、用户保护规则|Coroutines|
|root|唯一su进程入口、有界输出、超时与审计|domain|
|system|Android公共接口、受控pm/am服务调用|domain/root|
|migration|快照、归档、身份映射、校验、恢复状态机|domain/root/system|
|app|Compose UI、StateFlow、DI、AtomicFile、WorkManager前台任务|以上模块|
|fixture|专用设备集成测试App|Android SDK|

## 构建配置

依赖与插件版本统一在`gradle/libs.versions.toml`；各模块统一JDK21 toolchain与Java21目标，源码目录统一为`src/<sourceSet>/kotlin`。Android模块均`compileSdk=36`、`minSdk=36`、`targetSdk=36`；release构建启用R8与资源收缩（规则见`app/proguard-rules.pro`）。应用资源使用`@string/app_name`与`Theme.MultiUsers`，并提供`values-night`变体。

UI只调用ViewModel，不执行Shell。公共UserManager/Build/Process用于可访问信息，跨用户能力使用系统提供的pm/am命令。无hidden API反射、裸Binder transaction或PackageManager数据库编辑。无Shizuku。

应用列表的包名来自 root（`pm list packages`），本应用用户的 PackageManager 只负责补显示名与图标：逐用户查询接口（`createContextAsUser`/`getInstalledApplicationsAsUser`）是 `@hide/@SystemApi`，普通应用不可用；因此清单声明 `QUERY_ALL_PACKAGES`，并用 `/data/app` 下的 APK 归档信息兜底，解析不到就退化为包名+占位图标，绝不让整个列表失败（见 `app/AppInfo.kt`）。

## 流程

QUEUED → RUNNING → SUCCEEDED/FAILED/INTERRUPTED。
Root journal：PREPARING → SOURCE_READY → TARGET_SAVED → WRITING → VERIFIED → COMPLETE。

1. 校验参数和工具、获取flock，任务ID已存在则禁止重放。
2. 校验用户已解锁、serial、真实包UID、独立appId、数据路径及owner。
3. 持久化原enabled设置，禁用/force-stop源，分别创建CE/DE tar、内容hash及树/权限清单；前后对比与归档校验后恢复源enabled。
4. install-existing到目标，保存目标原enabled、serial，禁用/force-stop，完整保存目标快照。
5. 校验源归档及APK hash，保留目标根inode，替换内部文件；映射UID/GID，修复context并restorecon。
6. 校验全部内容、布局/模式、身份及context后，恢复目标原enabled。源数据及两个快照保留。

## 持久化与异常

应用Mutex串行管理变更；迁移及管理写操作共享root flock；读取日志不等待迁移锁。TaskStore同步更新最新任务，避免旧快照覆盖Worker新状态。记录先落盘再提交WorkManager，未开始/中断任务可读取Root日志判断。

Root目录保存package、source/target-user、serial、原enabled、state/result/log，以及source/target快照。快照含SHA256SUMS和COMPLETE。阶段用临时文件rename，关键元数据和快照sync。

CE/DE不是跨目录原子事务。断电/强停仍可能留下部分写入；因此恢复从完整目标快照重建，失败不自动启用。备份不提供硬件凭据或应用业务语义保证。

不支持其他Root管理员或系统安装器并发修改同一个包；校验不等同于锁定整个Android系统。未知目录/文件类型、共享UID、外置卷、未知标签均fail closed。Root超时结果未知，持锁子进程可能继续，不能盲目重试。

## 官方依据

- https://developer.android.com/build/releases/agp-8-11-0-release-notes
- https://developer.android.com/privacy-and-security/direct-boot
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/pm/UserManagerService.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActivityManagerShellCommand.java
