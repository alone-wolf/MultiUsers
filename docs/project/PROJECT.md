# MultiUsers 基线
revision: 5；来源：2026-10-14用户完整MVP要求。
CAP001 Root/用户、CAP002应用、CAP003迁移恢复：代码已恢复，当前验证以VALIDATION为准。
目标：Android16+、Kotlin/Compose Material3、Root-only，top.writerpass.multiusers。
长期不变量：模块分层、系统服务管理包、不写PM数据库、源不删除、未知身份拒绝、备份后覆盖。
工程：JDK21/Gradle8.13/AGP8.11.1/Kotlin2.1.20/SDK36。工具版本固定，Wrapper随源交付；依赖与插件版本集中在gradle/libs.versions.toml。
M001：全部用户MVP，真机验收待设备。模块和持久化契约见../ARCHITECTURE.md。
交付与证据：项目内只留源码与文档，不留构建产物；验证产物在evidence/（当前结论见docs/VALIDATION.md，历史产物在evidence/archive/），历史交付APK在项目外artifacts/。Kotlin统一ktlint 1.8.0官方风格，配置见.editorconfig。
变化记录：rev1初次设计实现；rev2中断临时工作区丢失后按聊天恢复，原证据不自动沿用；rev3 JDK由17改为本机21，补齐构建/资源/编辑器配置，源码目录统一为kotlin，改动后重新构建验证；rev4整理轮，无行为代码改动，重算源码清单、从clean重建取证、归档被取代的旧证据，并订正文档中滞留的rev2基线引用；rev5用ktlint 1.8.0格式化全部Kotlin源码（展开通配导入、拆分超长行），逻辑未改动，格式化后重新从clean构建验证，并按用户要求清除项目内一切构建产出。