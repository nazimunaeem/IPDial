package com.ipdial.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun isNewerVersion_handlesPatchMinorMajorAndLengthDifferences() {
        assertTrue(UpdateChecker.isNewerVersion("1.2.1", "1.2.2"))
        assertTrue(UpdateChecker.isNewerVersion("1.2", "1.2.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.2.2", "2.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.2", "1.2.2"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.2", "1.2.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.2", "1.2"))
    }
}
