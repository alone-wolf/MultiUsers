# evidence/ 说明

本目录只保留**能证明当前源码状态**的验证产物；被取代的旧产物移入 archive/，不再作为结论依据。所有结论以 ../docs/VALIDATION.md 为准。

| 文件 | 内容 | 说明 |
|---|---|---|
| build.log | 从 clean 起一次跑完全部构建/测试/lint 的 Gradle 输出 | 当前唯一有效的构建记录（2026-10-14 rev5：ktlint 格式化后重建） |
| ktlint.txt | ktlint 校验记录 | 0 issues；格式化命令与版本见文件内 |
| domain-TEST-*.xml / root-TEST-*.xml / system-TEST-*.xml | JVM 单元测试结果 | 由 Gradle 直接生成，对应 :domain:test / :root:test / :system:testDebugUnitTest |
| lint-results-*.txt | 各模块 lint 报告 | debug 对应 :app:lintDebug，domain/root 对应 :domain:lint / :root:lint，system/migration 对应各自 lintDebug |
| apk-signature.txt / apk-alignment.txt | apksigner verify 与 zipalign -c 输出 | 针对 app-debug.apk |
| apk-badging.txt | aapt2 dump badging | 包名、版本、min/target SDK |
| SOURCE-SHA256.txt | 交付源码与配置的 SHA-256 清单 | 可用 \`shasum -a 256 -c evidence/SOURCE-SHA256.txt\` 自检；范围为除 evidence/、build/、local.properties 外的全部文件 |
| engine-host-tests.log | \`python3 tests/test_engine.py\` 的本机输出 | **本机 macOS 缺 flock，11/11 预期失败**；该文件记录的是失败原因，不是通过证据。需在 Linux 主机重跑 |
| archive/ | 被取代的历史产物 | recovered-build*.log（工作区丢失后的恢复构建）、jdk21-build.log（rev3 构建）、engine-host-tests.log（旧 9 用例通过记录） |

## 不含什么

- **不含构建产物**：app-debug.apk 等按"项目内不留构建产出"清除，哈希记录在 VALIDATION.md；历史交付 APK 放在项目外的 artifacts/。
- 不含真机 / Root / OEM 行为证据：没有连接设备，设备验收矩阵（../docs/TESTING.md）尚未执行。
- 不含 release APK 的签名证据：release 变体未签名。
- archive/ 中的记录仅作历史留存，**不得**用来说明当前源码已验证。
