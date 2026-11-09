package com.deangi.androidpdp11.storage

/** CP/M 2.2 IBM-3740 raw floppy filesystem, independent of Android storage. */
internal class CpmFloppyImage(private val image: ByteArray) {
    data class FileInfo(val user: Int, val name: String, val records: Int, val blocks: Int, val readOnly: Boolean, val system: Boolean)
    data class Directory(val files: List<FileInfo>, val freeBytes: Int, val freeEntries: Int, val opaqueEntries: Int)
    private data class Extent(val slot: Int, val user: Int, val name: String, val index: Int, val records: Int, val blocks: List<Int>, val readOnly: Boolean, val system: Boolean)
    private data class Parsed(val extents: List<Extent>, val allocated: Set<Int>, val opaqueSlots: Set<Int>)

    init { require(image.size >= IMAGE_BYTES) { "Floppy image is too small" } }

    fun directory(): Directory {
        val parsed = parse()
        val files = parsed.extents.groupBy { it.user to it.name }.map { (key, extents) ->
            FileInfo(key.first, key.second, extents.sumOf { it.records }, extents.sumOf { it.blocks.size }, extents.any { it.readOnly }, extents.any { it.system })
        }.sortedWith(compareBy({ it.user }, { it.name }))
        return Directory(files, (BLOCKS - parsed.allocated.size) * BLOCK_SIZE, DIRECTORY_ENTRIES - parsed.extents.size - parsed.opaqueSlots.size, parsed.opaqueSlots.size)
    }

    /** Returns full CP/M records; original byte lengths are not stored by CP/M 2.2. */
    fun exportFile(selector: String, text: Boolean = false): ByteArray {
        val (user, name) = parseSelector(selector)
        val extents = parse().extents.filter { it.user == user && it.name == name }.sortedBy { it.index }
        require(extents.isNotEmpty()) { "CP/M file not found: $selector" }
        val result = ByteArray(extents.sumOf { it.records } * RECORD_SIZE)
        var written = 0
        extents.forEach { extent ->
            var remaining = extent.records * RECORD_SIZE
            extent.blocks.forEach { block ->
                val count = minOf(remaining, BLOCK_SIZE)
                readLogical(block * BLOCK_SIZE, result, written, count)
                written += count
                remaining -= count
            }
        }
        return if (text) result.copyOf(result.indexOfLast { it != 0x1A.toByte() } + 1) else result
    }

    /** Returns a modified image copy, preserving the original and any trailing bytes. */
    fun importFile(selector: String, data: ByteArray, text: Boolean = false): ByteArray {
        val (user, name) = parseSelector(selector)
        val parsed = parse()
        require(parsed.extents.none { it.user == user && it.name == name }) { "CP/M file already exists: $selector" }
        require(data.size <= (BLOCKS - 2) * BLOCK_SIZE) { "File is too large for a floppy" }
        val records = (data.size + RECORD_SIZE - 1) / RECORD_SIZE
        val extentCount = maxOf(1, (records + 127) / 128)
        val blockCount = (records + 7) / 8
        val usedSlots = parsed.extents.map { it.slot }.toSet() + parsed.opaqueSlots
        val freeSlots = (0 until DIRECTORY_ENTRIES).filter { it !in usedSlots }
        val freeBlocks = (2 until BLOCKS).filter { it !in parsed.allocated }
        require(freeSlots.size >= extentCount) { "Not enough free CP/M directory entries" }
        require(freeBlocks.size >= blockCount) { "Not enough free CP/M disk space" }

        val result = image.copyOf()
        val target = CpmFloppyImage(result)
        val components = name.split('.')
        var recordsLeft = records
        var blockIndex = 0
        var sourceOffset = 0
        for (extent in 0 until extentCount) {
            val recordsHere = minOf(recordsLeft, 128)
            val blocksHere = (recordsHere + 7) / 8
            val entry = ByteArray(32)
            entry[0] = user.toByte()
            components[0].padEnd(8, ' ').forEachIndexed { i, c -> entry[1 + i] = c.code.toByte() }
            components.getOrElse(1) { "" }.padEnd(3, ' ').forEachIndexed { i, c -> entry[9 + i] = c.code.toByte() }
            entry[12] = (extent and 0x1f).toByte()
            entry[14] = (extent shr 5).toByte()
            entry[15] = recordsHere.toByte()
            repeat(blocksHere) { index ->
                val block = freeBlocks[blockIndex++]
                entry[16 + index] = block.toByte()
                val buffer = ByteArray(BLOCK_SIZE) { if (text) 0x1A else 0 }
                val count = minOf(BLOCK_SIZE, data.size - sourceOffset)
                data.copyInto(buffer, 0, sourceOffset, sourceOffset + count)
                sourceOffset += count
                target.writeLogical(block * BLOCK_SIZE, buffer)
            }
            target.writeLogical(freeSlots[extent] * 32, entry)
            recordsLeft -= recordsHere
        }
        val restored = target.exportFile(selector)
        check(restored.size == records * RECORD_SIZE && restored.copyOf(data.size).contentEquals(data)) { "Imported file verification failed" }
        return result
    }

    /** Marks all extents deleted on a copy; allocated data and other entries remain untouched. */
    fun removeFile(selector: String): ByteArray {
        val (user, name) = parseSelector(selector)
        val extents = parse().extents.filter { it.user == user && it.name == name }
        require(extents.isNotEmpty()) { "CP/M file not found: $selector" }
        require(extents.none { it.readOnly }) { "CP/M file is read-only: $selector" }
        val result = image.copyOf()
        val target = CpmFloppyImage(result)
        extents.forEach { target.writeLogical(it.slot * 32, byteArrayOf(0xE5.toByte())) }
        target.parse()
        return result
    }

    private fun parse(): Parsed {
        val extents = mutableListOf<Extent>()
        val allocated = mutableSetOf(0, 1)
        val opaqueSlots = mutableSetOf<Int>()
        for (slot in 0 until DIRECTORY_ENTRIES) {
            val entry = ByteArray(32)
            readLogical(slot * 32, entry, 0, entry.size)
            val user = entry[0].toInt() and 0xff
            if (user == 0xE5) continue
            if (user !in 0..31) {
                opaqueSlots.add(slot)
                for (i in 16 until 32) {
                    val block = entry[i].toInt() and 0xff
                    if (block != 0) require(block in 2 until BLOCKS && allocated.add(block)) { "Invalid or shared allocation in opaque directory entry $slot" }
                }
                continue
            }
            val base = decodePart(entry, 1, 8)
            val extension = decodePart(entry, 9, 3)
            require(base.isNotEmpty()) { "Invalid CP/M filename in slot $slot" }
            val name = base + if (extension.isEmpty()) "" else ".$extension"
            val index = (entry[12].toInt() and 0x1f) or ((entry[14].toInt() and 0x3f) shl 5)
            val records = entry[15].toInt() and 0xff
            require(records <= 128) { "Invalid record count in $name" }
            val needed = (records + 7) / 8
            val pointers = (0 until 16).map { entry[16 + it].toInt() and 0xff }
            require(pointers.take(needed).all { it != 0 }) { "Missing allocation block in $name" }
            val blocks = pointers.filter { it != 0 }
            blocks.forEach { block ->
                require(block in 2 until BLOCKS && allocated.add(block)) { "Invalid or shared allocation block in $name" }
            }
            extents.add(Extent(slot, user, name, index, records, blocks, entry[9].toInt() and 0x80 != 0, entry[10].toInt() and 0x80 != 0))
        }
        extents.groupBy { it.user to it.name }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.index }
            require(sorted.indices.all { sorted[it].index == it }) { "Missing or duplicate CP/M extent in ${sorted.first().name}" }
            require(sorted.dropLast(1).all { it.records == 128 }) { "Incomplete CP/M extent in ${sorted.first().name}" }
        }
        return Parsed(extents, allocated, opaqueSlots)
    }

    private fun decodePart(entry: ByteArray, offset: Int, length: Int): String {
        val text = (offset until offset + length).map { (entry[it].toInt() and 0x7f).toChar() }.joinToString("")
        require(text.all { it == ' ' || it.code in 0x21..0x7e } && !text.trimEnd().contains(' ')) { "Invalid CP/M directory filename" }
        return text.trimEnd().uppercase()
    }

    private fun readLogical(offset: Int, target: ByteArray, targetOffset: Int, count: Int) {
        var pos = 0
        while (pos < count) {
            val logical = offset + pos
            val sectorIndex = logical / RECORD_SIZE
            val inSector = logical % RECORD_SIZE
            val countHere = minOf(count - pos, RECORD_SIZE - inSector)
            val track = 2 + sectorIndex / 26
            val physicalSector = SKEW[sectorIndex % 26] - 1
            val physicalOffset = (track * 26 + physicalSector) * RECORD_SIZE + inSector
            require(physicalOffset >= 0 && physicalOffset + countHere <= IMAGE_BYTES) { "CP/M block outside floppy image" }
            image.copyInto(target, targetOffset + pos, physicalOffset, physicalOffset + countHere)
            pos += countHere
        }
    }

    private fun writeLogical(offset: Int, source: ByteArray) {
        var pos = 0
        while (pos < source.size) {
            val logical = offset + pos
            val sectorIndex = logical / RECORD_SIZE
            val inSector = logical % RECORD_SIZE
            val count = minOf(source.size - pos, RECORD_SIZE - inSector)
            val physicalOffset = ((2 + sectorIndex / 26) * 26 + SKEW[sectorIndex % 26] - 1) * RECORD_SIZE + inSector
            require(physicalOffset >= 0 && physicalOffset + count <= IMAGE_BYTES) { "CP/M block outside floppy image" }
            source.copyInto(image, physicalOffset, pos, pos + count)
            pos += count
        }
    }

    companion object {
        const val IMAGE_BYTES = 77 * 26 * 128
        private const val RECORD_SIZE = 128
        private const val BLOCK_SIZE = 1024
        private const val BLOCKS = 243
        private const val DIRECTORY_ENTRIES = 64
        private val SKEW = intArrayOf(1,7,13,19,25,5,11,17,23,3,9,15,21,2,8,14,20,26,6,12,18,24,4,10,16,22)

        fun parseSelector(selector: String): Pair<Int, String> {
            val parts = selector.split(':', limit = 2)
            val user = if (parts.size == 2) parts[0].toIntOrNull() ?: -1 else 0
            require(user in 0..31) { "CP/M user number must be 0..31" }
            val name = parts.last().uppercase()
            val components = name.split('.')
            val validChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'()-@^_`{}~"
            require(components.size in 1..2 && components[0].length in 1..8 &&
                (components.size == 1 || components[1].length in 1..3) &&
                components.all { part -> part.all { it in validChars } }) { "Use a CP/M 8.3 filename, optionally prefixed by user number (e.g. 0:FILE.TXT)" }
            return user to name
        }
    }
}
