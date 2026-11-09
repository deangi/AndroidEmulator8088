package com.deangi.androidpdp11.emulator

import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatorSliceCoordinatorTest {
    @Test fun `runs ordered host work around the configured cpu budget`() {
        val events = mutableListOf<String>()
        val coordinator = EmulatorSliceCoordinator(
            runInstructions = { count -> events += "cpu:$count"; 42 },
            services = listOf(EmulatorSliceService { phase -> events += phase.name }),
            initialInstructionsPerSlice = 1_000,
        )

        assertEquals(42, coordinator.runSlice())
        assertEquals(listOf("BEFORE_CPU", "cpu:1000", "AFTER_CPU"), events)

        coordinator.instructionsPerSlice = 2_000
        coordinator.runSlice()
        assertEquals("cpu:2000", events[4])
    }
}
