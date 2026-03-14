package com.gop.logging.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StepContextTest {

    @Test
    fun scopedRestoresPreviousStep() {
        StepContext.set("outer")

        StepContext.scoped("inner") {
            assertEquals("inner", StepContext.get())
        }

        assertEquals("outer", StepContext.get())
        StepContext.clear()
    }

    @Test
    fun scopedClearsWhenNoPreviousStep() {
        StepContext.clear()

        StepContext.scoped("inner") {
            assertEquals("inner", StepContext.get())
        }

        assertNull(StepContext.get())
    }
}
