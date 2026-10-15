package top.writerpass.multiusers.root
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.writerpass.multiusers.domain.Inputs

class ShellBoundaryTest {
    @Test fun untrustedNamesRemainSingleLiteralArgument() {
        for (v in listOf("Work profile", "O'Brien", "x; echo compromised", "$(id)", "`id`", "line\nnext", "")) {
            val p = ProcessBuilder("sh", "-c", "printf '%s' ${Inputs.quote(v)}").start()
            assertEquals(v, p.inputStream.bufferedReader().readText())
            assertEquals(0, p.waitFor())
        }
    }

    @Test fun namespaceNoteReportsEveryUserFact() {
        val n = namespaceNote("OK 0(RUNNING_UNLOCKED)\nNO 10(RUNNING_UNLOCKED)\n", "--mount-master（全局命名空间）")
        assertTrue(n.contains("--mount-master"))
        assertTrue(n.contains("OK 0(RUNNING_UNLOCKED)"))
        assertTrue(n.contains("NO 10(RUNNING_UNLOCKED)"))
        assertTrue(namespaceNote("", "默认命名空间").contains("未探测到用户目录"))
    }

    @Test fun rootErrorPreservesExitAndDiagnostic() {
        val e = assertThrows(IllegalStateException::class.java) { ShellResult(13, "permission denied").checked() }
        assertTrue(e.message!!.contains("13"))
        assertTrue(e.message!!.contains("permission denied"))
    }
}
