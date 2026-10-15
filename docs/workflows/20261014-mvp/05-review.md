# 评审
依据用户全部MVP与实际代码，审查输入/命令边界、用户保护、归档校验、UID/GID/context、并发锁、源不删除、目标回滚、断点恢复与任务状态。
已修复的具体问题：覆盖前失败目标禁用恢复；用户ID复用误恢复；管道吞错（pipefail及find批量exec）；日志读锁阻塞/旧任务覆盖；索引损坏导致崩溃；多余包可见性权限。
主机检查不能证明真实Root/SELinux/FBE行为。Magisk/KernelSU/APatch及OxygenOS没有连接设备实测。最终状态据VALIDATION，不宣称全部设备验收通过。
