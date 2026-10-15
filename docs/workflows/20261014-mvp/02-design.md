# 详细设计 rev2
DES001 domain/root → AC001/008；DES002 system用户 → AC002；DES003 system应用 → AC003；DES004 migration → AC004-006；DES005 app持久任务 → AC007。
具体模块、接口、持久化、阶段、异常与恢复契约统一见../../ARCHITECTURE.md和代码。
关键守卫：RUNNING_UNLOCKED、appId10000..19999、独立UID、内部目录owner、无链接/特殊文件、标准context、同APK hash、原用户serial。覆盖前完整目标快照，失败目标禁用；显式恢复。
