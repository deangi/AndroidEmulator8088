package com.deangi.androidpdp11

/** Minimal paper-tape-style PDP-11 console test, loaded at octal 001000. */
object Pdp11ConsoleProgram {
    private const val loadAddress = 0x0200 // octal 001000

    fun load(core: Pdp11NativeCore) {
        // MOV #message,R1; MOVB (R1)+,R0; BEQ input; MOVB R0,@#TPB; BR loop
        // input: TSTB @#TKS; BPL input; MOVB @#TKB,R0; MOVB R0,@#TPB; BR input
        val words = mutableListOf(
            5569, 542, 37952, 771, 36895, 65398, 508,
            35807, 65392, 33021, 38848, 65394, 36895, 65398, 504,
        )
        val text = "Hello from PDP11\n\u0000".encodeToByteArray()
        for (index in text.indices step 2) {
            val low = text[index].toInt() and 0xff
            val high = text.getOrElse(index + 1) { 0 }.toInt() and 0xff
            words += low or (high shl 8)
        }
        val image = ByteArray(words.size * 2)
        words.forEachIndexed { index, word ->
            image[index * 2] = word.toByte()
            image[index * 2 + 1] = (word ushr 8).toByte()
        }
        core.reset(loadAddress)
        core.clearMemory()
        core.load(loadAddress, image)
        core.reset(loadAddress)
    }
}
