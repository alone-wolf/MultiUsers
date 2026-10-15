package top.writerpass.multiusers.system
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test fun namesCanContainColons() {
        val u = Parsers.users("Users:\n UserInfo{0:Owner:c13} running\n UserInfo{10:Work: A:1030} running", 0)
        assertEquals("Work: A", u[1].name)
        assertTrue(u[1].managed)
        assertTrue(u[0].current)
    }

    @Test fun malformedFailsClosed() {
        assertThrows(IllegalStateException::class.java) { Parsers.users("garbage", 0) }
        assertThrows(IllegalStateException::class.java) { Parsers.packages("package:a.b uid:bad") }
    }

    @Test fun uidWithoutGuessing() {
        assertEquals(1010123, Parsers.packages("package:a.b uid:1010123\n")["a.b"])
    }

    @Test fun precheckParsesBothSides() {
        val p = Parsers.precheck("SRC_STATE=RUNNING_UNLOCKED\nCE_SRC=YES\nDE_SRC=YES\nDST_STATE=STOPPED\nCE_DST=NO\nDE_DST=NO\n", 10, 11)
        assertEquals("RUNNING_UNLOCKED", p.srcState)
        assertTrue(p.srcDirs.ready)
        assertEquals(11, p.dst)
        assertEquals("STOPPED", p.dstState)
        assertFalse(p.dstDirs.ready)
        val q = Parsers.precheck("", 0, 1)
        assertEquals("", q.srcState)
        assertFalse(q.dstDirs.ce)
    }

    @Test fun apkPathListingIsParsed() {
        val t = "package:/data/app/~~a==/x==/base.apk=com.foo uid:10123\n"
        assertEquals(10123, Parsers.packages(t)["com.foo"])
        assertEquals("/data/app/~~a==/x==/base.apk", Parsers.apkPaths(t)["com.foo"])
    }
}
