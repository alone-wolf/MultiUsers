package top.writerpass.multiusers.domain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PolicyTest {
    @Test fun shellQuoteIsLiteral() {
        assertEquals("'a'\\''; reboot'", Inputs.quote("a'; reboot"))
    }

    @Test fun rejectsTraversalAndInjection() {
        listOf("a.b;id", "../a.b", "a/b", "a.b\n").forEach { assertThrows(IllegalArgumentException::class.java) { Inputs.pkg(it) } }
    }

    @Test fun protectsCurrentAndOwner() {
        for (id in listOf(0, 10, 11)) {
            assertThrows(IllegalArgumentException::class.java) {
                UserPolicy.check(
                    UserAction.DELETE,
                    DeviceUser(
                        id,
                        "u",
                        0,
                        false,
                        id == 11,
                    ),
                    10,
                )
            }
        }
    }

    @Test fun profileCannotSwitch() {
        assertThrows(IllegalArgumentException::class.java) { UserPolicy.check(UserAction.SWITCH, DeviceUser(12, "work", 0x20, true), 0) }
    }

    @Test fun copyCannotTargetSource() {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationRequest("12345678-1234-1234-1234-123456789abc", MigrationKind.COPY, "a.b", 0, 0).validate()
        }
    }
}
