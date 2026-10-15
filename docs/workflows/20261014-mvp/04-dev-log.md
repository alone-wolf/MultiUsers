# 开发日志
上一轮完成代码、debug构建、10项JVM测试及9项host流程测试，Android集成测试APK编译；最后Lint发现多余QUERY_ALL_PACKAGES权限。尚未持久保存即中断，临时工作区随后丢失。
本轮按可见聊天代码恢复同一增量；恢复后重新验证，旧结果仅是历史，不作为当前版本证据。
已修复：多余QUERY_ALL_PACKAGES移除；图标和dataExtractionRules补齐；任务索引损坏保留证据；读取日志不等执行锁；同步更新最新状态；用户serial保护；root输出有限与超时未知结果提示；源保留及目标快照恢复。
本轮命令、结果见evidence及VALIDATION.md。源快照已先保存，最终源码与APK分别交付。

恢复编译发现中文紧邻Kotlin插值导致变量名错误，已加花括号；再次完整构建通过。Root输出超限改为明确错误而不静默截断。最新全部验证见VALIDATION。

追加（同日，未重新构建）：修复 Models.kt 源码中的裸 NUL 字节（改为等价 '\u0000'）；tests/test_engine.py 替身补 id 并让失败用例断言具体拒绝原因，消除非 root 主机的假通过；docs/TESTING.md 补充 Linux/GNU coreutils 前提。受影响文件的哈希已在 SOURCE-SHA256.txt 更新，构建与设备验收结论仍以 VALIDATION 为准。

第三轮（同日，已重新构建）：JDK由17改为本机Corretto21（toolchain与Java目标均21）；补齐local.properties、gradle.properties、.editorconfig、.gitignore与gradle/libs.versions.toml版本目录；各模块补齐release/R8+资源收缩、lint、testOptions、packaging配置；应用资源改用@string/app_name与Theme.MultiUsers并新增values-night；源码目录由src/*/java统一为src/*/kotlin；为:domain与:root接入com.android.lint。从clean起的完整构建、10项JVM测试、5个模块lint、debug/release APK与instrumented test APK全部通过，证据见evidence/archive/jdk21-build.log与VALIDATION。真机验收仍为NOT RUN。

补记（rev3之后、整理轮之前，源码时间戳19:33–21:19）：新增 system/RootLock.kt（Android mksh 的 O_CLOEXEC 使 fd≥3 不可继承，flock 必须在 fd 0 上，迁移引擎同步同写法）；新增 app/AppInfo.kt（列表来自 root，用 PackageManager 补显示名与图标，解析不到退化为包名+占位图标）；新增 3 个设备测试（ParsersDeviceTest、RootLockDeviceTest、AppInfoDeviceTest）；扩展 ShellBoundaryTest 与 RootExecutor 的命名空间探测；修改 Models/Parsers/AndroidSystem/ParsersTest/engine.sh/MainViewModel/MainActivity。**这批改动当时没有重新取证**（VALIDATION 与 SOURCE-SHA256 停留在 rev3），整理轮连同上述内容一并从 clean 重建并回填证据。

**更正**：rev2 轮记录的"已修复：多余QUERY_ALL_PACKAGES移除"与当前实现不符。应用列表的包名来自 root（pm list packages），要解析任意包的显示名与图标就必须声明 QUERY_ALL_PACKAGES（AppInfo.kt 与 AndroidManifest.xml 的 tools:ignore 已写明理由），恢复轮于 20:16 重新加入并保留。该行只描述当时那一轮的中间状态，以本行为准。

第四轮（整理轮，同日，已从 clean 重新构建）：**未改动任何 Kotlin / engine.sh 行为代码**。按磁盘实况重算 evidence/SOURCE-SHA256.txt（原 11 项哈希过期、5 个源码文件漏登记、47 项更正为 63 项）；clean 重建并重录 VALIDATION（:root:test 2→3、:system:testDebugUnitTest 3→5、app lint 18→20 warnings）；被取代的构建日志与旧 host 测试日志移入 evidence/archive/，engine-host-tests.log 改记本机 macOS 的真实失败（缺 flock，11/11 失败，非通过证据）；交付 APK 从工作区根目录移入 dist/；清理 .gradle/、.kotlin/、tests/__pycache__/ 与各模块 build/ 缓存；workflows/INDEX、00-state、STARTUP、PROJECT 的基线版本由滞留的 rev2 订正为 rev4。
第五轮（整理轮续，同日）：用 ktlint 1.8.0（ktlint-cli-1.8.0-all.jar，JDK21）对全部 Kotlin 源码执行 --format，随后 ktlint check 0 issues。改动仅为格式与导入：4 空格缩进、声明/赋值/冒号空格、导入排序与去重、if-else 换行；展开 20 个通配导入（Compose layout/material3/runtime、work、coroutines、flow、domain、root、junit Assert）；拆分 3 处超长行（migrationWarning 文案、迁移预检命令、NAMESPACE_PROBE）；.editorconfig 增加 \`ktlint_function_naming_ignore_when_annotated_with = Composable\` 以保留 Compose 组合函数的 PascalCase。**未改动任何逻辑**。格式化后从 clean 重建：ktlint 0 issues、13 项 JVM 单测、5 个模块 lint、4 个 APK 全部通过。按用户要求清除项目内一切构建产出（各模块 build/、.gradle/、.kotlin/、dist/），交付 APK 移到项目外 artifacts/。
