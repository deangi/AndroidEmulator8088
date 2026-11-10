package com.deangi.emulator.core

import java.io.Closeable

/**
 * Architecture-neutral contract between the Android host and a native guest.
 *
 * A core owns CPU execution, its primary console UART/TTY, and the machine's
 * media-controller state. Boot profiles remain host policy; drive geometry,
 * mounted-state notification, and media changes belong to the native machine
 * implementation and vary substantially between CP/M/Z80 and PDP-11 systems.
 */
interface EmulatorCore : Closeable {
    val architecture: EmulatorArchitecture
    val workspace: EmulatorWorkspace

    /** Human-readable CPU/model identity, e.g. "Z80", "8080", or "PDP-11/70". */
    val processorId: String

    /** Installed, addressable RAM.  Applying a new size resets the machine. */
    var memorySizeBytes: Long

    /** Restores the CPU and machine to its architecture-defined reset state. */
    fun reset()

    /** Fills the complete installed RAM range; defaults to a conventional cold-clear. */
    fun clearMemory(value: Byte = 0)

    /** Clears installed RAM to zero without changing CPU or device state. */
    fun zeroMemory() = clearMemory(0)

    /** Executes no more than [instructionBudget] guest instructions. */
    fun runInstructions(instructionBudget: Long): ExecutionResult

    /** The machine's interactive console. */
    val console: ConsolePort

    /** Named removable/fixed media slots, independent of their media geometry. */
    val drives: DrivePort

    /** Present only for machines configured with a line-printer device. */
    val linePrinter: LinePrinterPort?

    /** Present only when the emulated machine exposes an operator front panel. */
    val frontPanel: FrontPanelPort?

    /** Returns and clears asynchronous host-visible failures. */
    fun takeHostError(): String
}

enum class EmulatorArchitecture { Z80, PDP11 }

/**
 * Host-visible layout for one emulator family below the Android document tree
 * selected by the user.  Native code never receives paths; it receives open
 * descriptors through [MediaAttachment].
 */
data class EmulatorWorkspace(
    val directoryName: String,
    val configurationFileName: String,
    val printerDirectoryName: String = "printer",
)

val V_PDP11_WORKSPACE = EmulatorWorkspace(
    directoryName = "vPDP11",
    configurationFileName = "pdp11config.ini",
)

data class ExecutionResult(
    val instructions: Long,
    val cycles: Long,
    val state: ExecutionState,
)

enum class ExecutionState { RUNNING, WAITING, HALTED, FAULTED }

/**
 * Byte-oriented console port.  Android, Telnet, and the terminal renderer can
 * share this one port without knowing which CPU produced the bytes.
 */
interface ConsolePort {
    fun offerInput(bytes: ByteArray)
    fun hasPendingInput(): Boolean
    fun drainOutput(): ByteArray
}

/** Output-only line printer device, separate from terminal output. */
interface LinePrinterPort {
    fun drainOutput(): ByteArray
}

/**
 * Drive slots use their native names: Z80 implementations may expose A–D;
 * PDP-11 implementations may expose RK0, RL0, RP0, or DU0.  The media file
 * name is resolved relative to [EmulatorWorkspace.directoryName] by Android.
 */
interface DrivePort {
    /** All slots that this machine can expose, whether media is present or not. */
    fun availableDrives(): List<DriveSlot>

    /** Image types and valid geometry choices offered by this machine. */
    fun supportedImageTypes(): List<DiskImageType>

    fun mountedDrives(): List<MountedDrive>

    /**
     * Create an unmounted, blank image in the selected emulator workspace.
     * A [BlankDiskImageRequest.sizeBytes] value of zero selects the image
     * type's conventional geometry; implementations reject unsupported
     * positive sizes.
     */
    fun createBlankImage(request: BlankDiskImageRequest): DriveResult

    fun mount(drive: String, fileName: String, readOnly: Boolean = false): DriveResult
    fun dismount(drive: String): DriveResult
}

/** Native unit name and its media/controller family, for example RL0/RL11. */
data class DriveSlot(
    val drive: String,
    val controller: String,
    val removable: Boolean = true,
)

/** A machine-supported disk image family, such as RL01 or CP/M floppy. */
data class DiskImageType(
    val id: String,
    val displayName: String,
    val defaultSizeBytes: Long = 0,
    val allowedSizeBytes: List<Long> = emptyList(),
)

/** Host request to create a blank image; it is not mounted automatically. */
data class BlankDiskImageRequest(
    val fileName: String,
    val imageTypeId: String,
    /** Zero selects [DiskImageType.defaultSizeBytes]. */
    val sizeBytes: Long = 0,
)

data class MountedDrive(
    val drive: String,
    val fileName: String,
    val readOnly: Boolean,
)

data class DriveResult(val success: Boolean, val message: String = "")

/** Generic operator-console functionality; architecture-specific controls can extend it. */
interface FrontPanelPort {
    fun snapshot(): FrontPanelSnapshot
    fun setSwitchRegister(value: UShort)
    fun reset()
    fun halt()
    fun run()
}

data class FrontPanelSnapshot(
    val address: ULong,
    val data: UShort,
    val lights: ULong,
    val running: Boolean,
)

/** Optional removable-media capability; drive naming/geometry stay machine-specific. */
interface MediaController {
    fun replaceMedia(media: List<MediaAttachment>): Boolean
}

data class MediaAttachment(
    val slot: String,
    val fileDescriptor: Int?,
    val readOnly: Boolean = false,
)
