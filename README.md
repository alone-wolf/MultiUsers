# MultiUsers

Android 16+ Root-only 多用户管理 MVP。Application ID `top.writerpass.multiusers`；Kotlin、Compose Material3、Gradle Kotlin DSL。minSdk/targetSdk/compileSdk 均为36。

功能：Root与工具检测；真实用户/工作资料枚举、创建、删除、启动、停止、切换；按用户应用列表、单APK安装、install-existing、启用/禁用、卸载；同设备普通App的CE/DE复制、备份、SHA256验证、恢复；持久任务、日志及失败恢复。

**实际构建和测试结果见 [VALIDATION](docs/VALIDATION.md)。真机未执行的项目不等于已验证兼容。**

## 构建

JDK21（本机已安装的 Corretto 21）、Android SDK Platform36、Build Tools（本机装有 35.0.0/36.0.0/36.1.0/37.0.0，编译用 AGP 默认，apksigner/zipalign 校验取最新版），Gradle8.13 Wrapper已包含。AGP8.11.1，Kotlin2.1.20；所有依赖与插件版本集中在 `gradle/libs.versions.toml`。

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
# local.properties 设置 sdk.dir=/你的/Android/Sdk，或设置 ANDROID_HOME（本机已生成该文件，不纳入版本管理）
./gradlew :app:assembleDebug :fixture:assembleDebug
./gradlew :domain:test :root:test :system:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease          # R8 压缩与资源收缩
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Windows使用gradlew.bat，Android Studio直接打开根目录。Debug签名由构建机器生成，换机器构建升级时可能需统一debug keystore或卸载旧包。

### Android Studio 的 Gradle JDK

Android Studio 自带的 JBR 随版本升级（2026.1 内置 **JBR 25**），而 Gradle 8.13 只支持 Java 1.8–23：IDE 的 Gradle JDK 若落在内置 JBR 上，同步会直接失败（*Incompatible Gradle JVM version … supports Java versions between 1.8 and 23*）。

本仓库用 `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=21`）把 **Gradle 守护进程**钉在 Java 21：Gradle 会自行挑选本机已安装的 JDK 21，即使启动 Gradle 的 JVM 是 25 也能正常同步与构建（已在 JBR 25 启动、daemon 实际运行于 Corretto 21 的条件下验证全量构建通过）。

若个别 IDE 版本仍报错，改为手动指定：Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle JDK** → 选择本机 JDK 21（本机注册名为 `corretto-21`）。

不要用 `gradle.properties` 里的 `org.gradle.java.home` 写死 JDK 绝对路径——该文件纳入版本管理，写死会使其他机器无法构建。

## 代码风格

全部 Kotlin 源码为 ktlint 1.8.0 官方风格（4 空格缩进、禁用通配导入、行长 140），配置见 `.editorconfig`；Compose 组合函数按 Compose 官方约定保留 PascalCase（通过 `ktlint_function_naming_ignore_when_annotated_with` 放行）。当前状态 0 issues，记录见 evidence/ktlint.txt。

```sh
java -jar ktlint-cli-1.8.0-all.jar --format   # 格式化
java -jar ktlint-cli-1.8.0-all.jar            # 校验
```

## 项目结构

```
gradle/libs.versions.toml     依赖与插件版本目录
app/                          Compose UI、StateFlow、WorkManager
domain/                       纯 Kotlin 模型与输入校验（无 Android 依赖）
root/                         唯一 su 执行入口
system/                       UserManager/包管理受控调用
migration/                    快照/恢复引擎（含 assets/engine.sh）
fixture/                      真机测试用靶子应用
docs/                         设计、测试、验证文档与增量工作流记录
evidence/                     当前验证产物（说明见 evidence/README.md）
  archive/                    被取代的历史产物，不作为结论依据
tests/                        host 编排测试（tests/test_engine.py）
```
各模块源码目录统一为 `src/<sourceSet>/kotlin`；构建配置集中在根 `build.gradle.kts` 与各模块同名脚本中。构建缓存与 `*/build/` 均为可再生输出（`.gitignore` 已忽略），**项目内不保存任何构建产物**；源码完整性用 `shasum -a 256 -c evidence/SOURCE-SHA256.txt` 自检。

## 使用

1. 安装后点“检测Root”，在Magisk/KernelSU/APatch中授予su权限。使用通用su -c协议，具体ROM和管理器组合仍需真机验收。
2. 用户页创建普通用户或工作资料。工作资料父用户是当前选中的用户；不附带企业DPC，不绕过OEM用户数量限制。
3. 启动并解锁两侧用户，新用户先完成系统首次设置。应用页选择单APK安装，或“复制到…”→“仅安装”复用已有安装（支持系统已安装的split APK代码，不支持任意.apks/.xapk导入）。
4. 数据迁移前，**源用户和目标用户**下的该App都要至少启动一次以创建CE/DE数据目录（未启动过的一侧会在PREPARING阶段被拒绝，日志里会打印DIAG事实）。选择目标，阅读硬件凭据限制并确认。期间不要启动相关App、切换用户或使用其他Root工具修改数据。
5. 任务页可读取日志、验证备份、恢复备份及失败恢复。覆盖目标前引擎先保存目标快照。**迁移永不自动删除源App、源用户或源数据**；用户页的独立删除功能需另行确认。

## 数据契约与限制

- 仅同设备普通独立UID应用、内部CE/DE目录；要求两侧RUNNING_UNLOCKED。不尝试破解锁屏、FBE或关闭SELinux。
- 禁止迁移系统App、管理器自身、共享UID、外置adoptable卷、链接/特殊文件、未知UID/GID或非标准context。用户管理和应用管理仍使用真实系统能力，错误不会用模拟数据替代。
- 按实际UID重映射app/cache/shared app GID；使用目标已有context，再restorecon，并核对内容hash、模式、UID/GID及context。保留目标数据根inode及其加密策略。
- Keystore/StrongBox/TEE、通行密钥、DRM、系统账户及硬件绑定凭据不可通过文件复制保证迁移。某些App需重新登录或无法读取原加密数据。文件校验成功不代表业务可用。
- 不包含外部存储、Android/data、OBB、权限/AppOps/角色/通知设置。不会修改PackageManager内部数据库。
- 备份为root:root 0700目录下的未额外加密归档：`/data/local/multiusers/<task-id>/source`；目标原数据为`target`。不导入外部归档，不自动清理备份，不申请INTERNET权限、不上传备份。
- APK hash必须与备份一致，升级后恢复会拒绝。未知OEM行为返回错误，不能宣称对所有ROM兼容。

## 中断恢复

WorkManager与私有AtomicFile记录任务；Root端独立journal、快照、日志。任务一旦开始，重启不盲目重放写入。全局flock阻止仍在运行的Root任务与管理变更并发。

失败时先“读取日志”：Root进程可能在App退出后继续。覆盖尚未开始时，恢复仅还原enabled设置；已有目标快照时，恢复快照并验证后再启用目标。写入失败目标保持禁用。用户serial变化则拒绝对被复用ID恢复。

空间不足、APK更新或原用户删除可能需要先修复环境。未完成恢复前不要清除管理器数据；清除会丢失UI任务索引，root快照不会自动删除。任务索引损坏时保留原文件并阻止新任务。

模块与实现契约见 [架构](docs/ARCHITECTURE.md)，设备验收步骤见 [测试指南](docs/TESTING.md)。