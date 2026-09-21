package app.krafted.candytriangle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Placeholder smoke test proving the instrumented source set compiles and runs on-device.
 *
 * The real on-device QA pass is D6; the automated verification suite (§11) is JVM-only.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun appContextHasExpectedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.krafted.candytriangle", context.packageName)
    }
}
