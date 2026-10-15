package top.writerpass.multiusers.system

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 设备端回归：RootLock 的前缀必须在真实 Android sh（mksh）下能拿到 flock。
 *
 * 背景：mksh 给重定向打开的 fd 加 O_CLOEXEC（fd>=3 都不会被子进程继承），而 Android 的
 * toybox flock 只接受 fd 参数。所以 `exec 9>lock; flock -n 9` 在真机必然报
 * "flock: flock: Bad file descriptor"，只有 `exec 0<>lock; flock -n 0` 可用。
 * JVM 主机单测走的是 bash/JDK 语义，发现不了这个差异，必须在这个测试里跑。
 */
@RunWith(AndroidJUnit4::class)
class RootLockDeviceTest {
    private val root =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lock-device-test")
            .apply {
                deleteRecursively()
                mkdirs()
            }.absolutePath

    private fun run(script: String): Pair<Int, String> {
        val p = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun lockCanBeAcquiredRepeatedly() {
        for (i in 1..2) {
            val (code, out) = run(RootLock.preamble(root) + "echo ACQUIRED$i")
            assertEquals("第 $i 次取锁失败: $out", 0, code)
            assertTrue(out.contains("ACQUIRED$i"))
        }
    }

    @Test fun runningHolderBlocksSecondWriter() {
        val holder =
            ProcessBuilder(
                "/system/bin/sh",
                "-c",
                RootLock.preamble(root) + "echo HELD; sleep 5",
            ).redirectErrorStream(true).start()
        try {
            Thread.sleep(1500)
            val (code, out) = run(RootLock.preamble(root) + "echo SHOULD_NOT_RUN")
            assertNotEquals("并发写操作没有被拒绝: $out", 0, code)
            assertTrue("拒绝原因应为'迁移仍在运行': $out", out.contains("迁移仍在运行"))
        } finally {
            holder.destroyForcibly()
            holder.waitFor()
        }
        val (code, out) = run(RootLock.preamble(root) + "echo AFTER")
        assertEquals("持有者退出后应能重新取锁（flock 随进程释放）: $out", 0, code)
    }
}
