package app.krafted.candytriangle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the installed application exposes the expected package context on-device. */
@RunWith(AndroidJUnit4::class)
class AppContextInstrumentedTest {
    @Test
    fun appContextHasExpectedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.krafted.candytriangle", context.packageName)
    }
}
