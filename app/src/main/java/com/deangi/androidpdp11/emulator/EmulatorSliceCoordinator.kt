package com.deangi.androidpdp11.emulator

/** A host-side activity point immediately before or after a native CPU slice. */
fun interface EmulatorSliceService {
    fun run(phase: SlicePhase)
}

enum class SlicePhase { BEFORE_CPU, AFTER_CPU }

/**
 * Keeps host work out of the Z80 instruction hot loop. The instruction budget
 * is deliberately adjustable as performance and device timing are tuned.
 */
class EmulatorSliceCoordinator(
    private val runInstructions: (Long) -> Long,
    private val services: List<EmulatorSliceService> = emptyList(),
    initialInstructionsPerSlice: Long = DEFAULT_INSTRUCTIONS_PER_SLICE,
) {
    @Volatile
    var instructionsPerSlice: Long = initialInstructionsPerSlice
        set(value) {
            require(value > 0) { "Instructions per slice must be positive" }
            field = value
        }

    init {
        require(initialInstructionsPerSlice > 0) { "Instructions per slice must be positive" }
    }

    fun runSlice(): Long {
        services.forEach { it.run(SlicePhase.BEFORE_CPU) }
        val cycles = runInstructions(instructionsPerSlice)
        services.forEach { it.run(SlicePhase.AFTER_CPU) }
        return cycles
    }

    companion object { const val DEFAULT_INSTRUCTIONS_PER_SLICE = 1_000L }
}
