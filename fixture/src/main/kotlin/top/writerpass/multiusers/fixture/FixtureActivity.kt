package top.writerpass.multiusers.fixture
import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import java.io.File

class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seed = "Fixture UID ${Process.myUid()}\n"
        File(filesDir, "seed.txt").writeText("CE $seed")
        File(createDeviceProtectedStorageContext().filesDir, "seed.txt").writeText("DE $seed")
        setContentView(
            TextView(this).apply {
                text =
                    "Test data created for UID ${Process.myUid()}"
            },
        )
    }
}
