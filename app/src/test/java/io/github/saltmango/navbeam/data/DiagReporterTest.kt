package io.github.saltmango.navbeam.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagReporterTest {

    @Test
    fun redact_stripsCoordinatesAndSsids() {
        val raw = "connect fail at 12.971604, 77.594566 and -33.8688,151.2093 with ssid='MY_PRIVATE_BIKE' or SSID=DASH_42"
        val clean = DiagReporter.redact(raw)

        assertFalse(clean.contains("12.971604"))
        assertFalse(clean.contains("77.594566"))
        assertFalse(clean.contains("-33.8688"))
        assertFalse(clean.contains("MY_PRIVATE_BIKE"))
        assertFalse(clean.contains("DASH_42"))
        assertTrue(clean.contains("[redacted-coord]"))
        assertTrue(clean.contains("ssid=[redacted]"))
    }

    @Test
    fun summarizeCrash_extractsExceptionLine() {
        val temp = File.createTempFile("crash-test", ".log")
        try {
            temp.writeText(
                """
                === NavBeam crash ===
                when=20261125-101500 (123456)
                thread=main
                exception=java.lang.IllegalStateException: Encoder stopped unexpectedly
                """.trimIndent()
            )
            assertEquals(
                "java.lang.IllegalStateException: Encoder stopped unexpectedly",
                DiagReporter.summarizeCrash(temp)
            )
        } finally {
            temp.delete()
        }
    }
}
