package top.writerpass.multiusers.system

/**
 * Root 写操作的全局互斥前缀：管理写操作与迁移引擎共用同一个锁文件。
 *
 * Android 的 /system/bin/sh（mksh）会为重定向打开的 fd 加 O_CLOEXEC，fd>=3 都不会被子进程继承；
 * 而 Android 的 toybox flock 只接受 fd 参数（不支持 FILE/COMMAND 形式）。
 * 因此锁必须开在 fd 0 上：`exec 0<>lock` 打开文件并保留继承。
 * 写成 `exec 9>lock; flock -n 9` 在真机必然失败，报 "flock: flock: Bad file descriptor"
 * （Android 16 / toybox 0.8.12 实测；fdinfo flags 显示 fd>=3 带 O_CLOEXEC，fd 0 不带）。
 *
 * 设备端回归：RootLockDeviceTest（./gradlew :system:connectedDebugAndroidTest）。
 * migration/src/main/assets/engine.sh 里的锁必须保持同一写法。
 */
object RootLock {
    fun preamble(root: String) =
        "set -e; umask 077; test ! -L $root; mkdir -p $root; chmod 700 $root; test ! -L $root/lock; exec 0<>$root/lock; flock -n 0 || { echo '迁移仍在运行'; exit 1; }; "
}
