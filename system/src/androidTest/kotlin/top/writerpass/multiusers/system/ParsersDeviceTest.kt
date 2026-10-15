package top.writerpass.multiusers.system
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端回归：必须在 Android 上运行（:system:connectedDebugAndroidTest）。
 * Android 的 java.util.regex 后端是 ICU4C（AOSP java/util/regex/Pattern.java），
 * 与桌面 JVM 正则存在语法差异：裸 `}` 会被 ICU 判为语法错误并抛
 * "Syntax error in regexp pattern"，而桌面 JVM 会接受，所以 src/test 下的单测发现不了。
 */
@RunWith(AndroidJUnit4::class)
class ParsersDeviceTest {
    @Test fun usersParsesRealDumpsysOutput() {
        val u = Parsers.users("Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work: A:1030} running", 0)
        assertEquals(2, u.size)
        assertEquals("Owner", u[0].name)
        assertEquals("Work: A", u[1].name)
        assertEquals(0x1030, u[1].flags)
        assertTrue(u[1].managed)
        assertTrue(u[0].current)
    }

    @Test fun packagesParseRealPmOutput() {
        val m = Parsers.packages("package:com.example.app uid:10123\npackage:android uid:1000")
        assertEquals(10123, m.getValue("com.example.app"))
        assertEquals(2, m.size)
    }

    @Test fun unparsableUserListFailsClosed() {
        var thrown = false
        try {
            Parsers.users("garbage", 0)
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test fun projectPatternsCompileUnderIcu() {
        Regex("(?im)^(error:|failure|exception|securityexception)") // AndroidSystem.cmd 的 su 错误检测
        Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") // Inputs.pkg
        Regex("[a-f0-9-]{36}") // Inputs.task
    }
}
