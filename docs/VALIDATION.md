# 验证记录 — 2026-10-14（rev5：ktlint 格式化后重建）

本表为**ktlint 1.8.0 格式化全部 Kotlin 源码后，从 clean 重建**的实际结果：全部命令一次跑完，完整日志见 evidence/build.log。真机/平台验收仍未执行，未执行项不得标"兼容"。

| 检查 | 当前结果 | 证据 |
|---|---|---|
| ktlint check（20 个 .kt + 5 个 .kts） | PASS，0 issues | evidence/ktlint.txt |
| :app:assembleDebug / :fixture:assembleDebug | PASS | evidence/build.log |
| :app:assembleRelease（R8 + 资源收缩） | PASS | evidence/build.log |
| :fixture:assembleRelease | PASS | evidence/build.log |
| :app:assembleDebugAndroidTest | PASS，仅编译 | evidence/build.log |
| :domain:test | 5/5 PASS | evidence/domain-TEST-*.xml |
| :root:test | 3/3 PASS | evidence/root-TEST-*.xml |
| :system:testDebugUnitTest | 5/5 PASS | evidence/system-TEST-*.xml |
| :app:lintDebug | PASS，0 errors / 20 warnings | evidence/lint-results-debug.txt |
| :domain:lint / :root:lint | No issues found | evidence/lint-results-domain.txt、lint-results-root.txt |
| :system:lintDebug / :migration:lintDebug | 各 1 warning / 0 errors | evidence/lint-results-system.txt、lint-results-migration.txt |
| apksigner verify（debug） | PASS，Android Debug签名/v2 | evidence/apk-signature.txt |
| zipalign -c -P 16 -v 4（debug） | PASS，Verification successful | evidence/apk-alignment.txt |
| APK包信息 | top.writerpass.multiusers；min/target SDK36 | evidence/apk-badging.txt |
| python3 tests/test_engine.py | FAIL 11/11，本机 macOS 缺 flock，属设计内的明确失败 | evidence/engine-host-tests.log |
| adb设备 / 真机安装 / Root操作 | NOT RUN，无连接设备 | adb devices为空 |

app 的 lint warning 构成：GradleDependency 8、NewerVersionAvailable 6、AndroidGradlePluginVersion 4、UseKtx 1（AppInfo.kt 可用 androidx.core.graphics.createBitmap）、SdCardPath 1。前 18 条均为"存在更新版本可用"，不是代码缺陷；按既定策略保留本次已验证的固定版本，不为消除提示临时升级。:system 与 :migration 的 1 条 warning 同源（checkDependencies 会把 :root 的 RootExecutor.kt 一起报出）：SdCardPath 对"/data/"字面量的通用提示，而该路径是 root 探测脚本在**设备上**执行的常量，此处属误报。

格式化规则未覆盖的项：ktlint 只重排空白、缩进与导入并拆分超长行，不改标识符与逻辑。Compose 组合函数的 PascalCase（MultiUsersScreen、UserPicker）按官方约定经 .editorconfig 的 `ktlint_function_naming_ignore_when_annotated_with = Composable` 放行。

## 制品

按"项目内不留构建产物"的要求，本轮产物已随 clean 清除，不随源码保存；下表哈希用于核对（`./gradlew :app:assembleDebug` 等命令可重建）：

| 产物 | 大小 | SHA-256 |
|---|---|---|
| app-debug.apk | 27547254 | `7c0f128cfcd66ee15f137316511b2f6ad32c6794ae07a1041ce82ad0a5685b36` |
| app-release-unsigned.apk | 2383246 | `6f56c58a53cf5a57f49be8414144cd58a0ef83c1368d70d5953fe95ebba8e882` |
| app-debug-androidTest.apk | 814745 | `9d698badf77bd193f88426f88714491ecf8557365e9f1f542070b26184ca3ad0` |

debug 产物不逐字节可复现：同一份源码两次 clean 构建的 debug APK 大小相同（27547254）而哈希不同（`bb865e2e…` 与 `7c0f128c…`）。整理轮之前交付的 debug APK（27778645 bytes / `d629763f…`）与本轮 release（`05a425da…`）一并存放在**项目外**的 artifacts/ 目录，避免丢失唯一副本。

## 构建环境

macOS arm64；Amazon Corretto 21.0.11（`jvmToolchain(21)`，Java source/target 21）；Gradle 8.13 Wrapper；AGP 8.11.1；Kotlin 2.1.20；ktlint 1.8.0 CLI；Android SDK Platform 36；Build Tools 37.0.0（apksigner/zipalign 校验取 build-tools 下最新版）。`sdk.dir` 写入 local.properties（机器相关，不纳入版本管理与源码清单）。源码清单：evidence/SOURCE-SHA256.txt（覆盖除 evidence/、build/、local.properties 外的全部交付文件，可用 `shasum -a 256 -c` 自检）。

沙箱内复现构建（不写 `~/.gradle`、`~/.android`）：

```sh
export GRADLE_USER_HOME=<工作区>/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches ANDROID_USER_HOME=<工作区>/.android-home
"$HOME/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle" --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process clean :app:assembleDebug ...
```

## 覆盖范围与未验证项

主机迁移测试使用Android命令替身，覆盖复制回滚、损坏归档、写入失败、锁定用户、共享UID、符号链接、APK变化、用户ID复用、覆盖前失败恢复及源/目标数据缺失的诊断命名。不能证明真实FBE/SELinux/UID映射、su管理器或OEM行为。

`tests/test_engine.py` 需要 Linux/GNU coreutils（`flock`、`sha256sum`、GNU `stat -c`）。本机为 macOS，11 个用例全部失败并在日志首行给出真实原因（`flock: command not found`），**环境错误不会被误判为"正确拒绝"的假通过**；需在 Linux 主机重跑并覆盖 evidence/engine-host-tests.log。

未实测：Magisk/KernelSU/APatch及OxygenOS/AOSP16兼容性，真实用户/工作资料生命周期，真实数据复制，断电恢复，App业务/硬件凭据可用性。完整设备验收仍被缺少测试设备阻塞，不标全部AC通过。

## 变更记录

- rev5（本轮）：用 ktlint 1.8.0 对全部 Kotlin 源码做官方风格格式化（4 空格缩进、声明/赋值空格、导入排序、if-else 换行），展开 20 个通配导入、拆分 3 处超长行、为 Compose 组合函数加命名例外，ktlint check 0 issues；**逻辑未改动**，格式化后从 clean 重建全部通过。按用户要求清除项目内一切构建产出（含 dist/），交付 APK 移到项目外 artifacts/。
- rev4（整理轮）：未改动行为代码。重算源码清单（原 11 项哈希过期、5 个源码文件漏登记、47 项更正为 63 项）；从 clean 重建并重录验证表；被取代的日志移入 evidence/archive/；建 dist/ 存放交付 APK；docs 基线版本号由滞留的 rev2 更正。
- rev3（更早）：JDK17 改本机21，补齐 local.properties、gradle.properties、.editorconfig、.gitignore、gradle/libs.versions.toml；模块补齐 release/R8、lint、testOptions；资源改用 `@string/app_name` 与 `Theme.MultiUsers`（含 values-night）；源码目录由 `src/*/java` 统一为 `src/*/kotlin`；为 :domain/:root 接入 `com.android.lint`。
- rev2（更早）：Linux/JDK17 构建与测试。
- 更早修正：删除 Models.kt 源码中的裸 NUL 字节（改为等价 `'\u0000'`）；tests/test_engine.py 替身补 `id` 并让失败用例断言具体拒绝原因。
