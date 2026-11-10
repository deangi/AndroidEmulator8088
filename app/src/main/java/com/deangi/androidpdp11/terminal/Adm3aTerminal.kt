package com.deangi.androidpdp11.terminal

/**
 * A screen-oriented ADM-3A terminal. It is independent of Compose and the
 * emulator so local and telnet console bytes can share the same parser.
 */
enum class TerminalPersonality { ADM3A, VT100 }

class Adm3aTerminal(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    personality: TerminalPersonality = TerminalPersonality.ADM3A,
) {
    private enum class ParserState { GROUND, ESCAPE, ADDRESS_ROW, ADDRESS_COLUMN, CSI, CHARSET }

    private val cells = Array(rows) { CharArray(columns) { ' ' } }
    private var state = ParserState.GROUND
    private val csi = StringBuilder()
    private var savedRow = 0
    private var savedColumn = 0
    var personality = personality
        set(value) { field = value; reset() }
    private var pendingRow = 0
    var cursorRow: Int = 0
        private set
    var cursorColumn: Int = 0
        private set

    init {
        require(columns > 0 && rows > 0) { "Terminal dimensions must be positive" }
    }

    fun reset() {
        clear()
        cursorRow = 0
        cursorColumn = 0
        state = ParserState.GROUND
        csi.clear()
    }

    /** Displays X at the four one-based 80×25 screen corners using ADM-3A addressing. */
    fun showCornerTestPattern() {
        reset()
        listOf(0 to 0, rows - 1 to 0, 0 to columns - 1, rows - 1 to columns - 1).forEach { (row, column) ->
            feed(byteArrayOf(0x1B, '='.code.toByte(), (row + 32).toByte(), (column + 32).toByte(), 'X'.code.toByte()))
        }
    }

    fun feed(bytes: ByteArray) = bytes.forEach { feed(it.toInt() and 0xFF) }

    fun feed(value: Int) {
        if (personality == TerminalPersonality.VT100) feedVt100(value) else when (state) {
            ParserState.GROUND -> feedGround(value)
            ParserState.ESCAPE -> feedEscape(value)
            ParserState.ADDRESS_ROW -> {
                pendingRow = value - 32
                state = ParserState.ADDRESS_COLUMN
            }
            ParserState.ADDRESS_COLUMN -> {
                moveTo(pendingRow, value - 32)
                state = ParserState.GROUND
            }
            ParserState.CSI, ParserState.CHARSET -> state = ParserState.GROUND
        }
    }

    private fun feedVt100(value: Int) {
        when (state) {
            ParserState.GROUND -> when (value) {
                0x07 -> Unit
                0x08 -> if (cursorColumn > 0) cursorColumn--
                0x09 -> cursorColumn = ((cursorColumn / 8) + 1).coerceAtMost((columns - 1) / 8) * 8
                0x0A, 0x0B, 0x0C -> lineFeed()
                0x0D -> cursorColumn = 0
                0x0E, 0x0F -> Unit
                0x1B -> state = ParserState.ESCAPE
                else -> if (value >= 0x20) put(value.toChar())
            }
            ParserState.ESCAPE -> when (value.toChar()) {
                '[' -> { csi.clear(); state = ParserState.CSI }
                '7' -> { savedRow = cursorRow; savedColumn = cursorColumn; state = ParserState.GROUND }
                '8' -> { moveTo(savedRow, savedColumn); state = ParserState.GROUND }
                'D' -> { lineFeed(); state = ParserState.GROUND }
                'E' -> { cursorColumn = 0; lineFeed(); state = ParserState.GROUND }
                'M' -> { reverseIndex(); state = ParserState.GROUND }
                'c' -> { reset(); state = ParserState.GROUND }
                '(', ')' -> state = ParserState.CHARSET
                else -> state = ParserState.GROUND
            }
            ParserState.CHARSET -> state = ParserState.GROUND // ASCII and DEC graphics share text cells for now.
            ParserState.CSI -> if (value in 0x40..0x7E) {
                executeCsi(value.toChar()); state = ParserState.GROUND
            } else if (value in 0x20..0x3F) csi.append(value.toChar()) else state = ParserState.GROUND
            else -> state = ParserState.GROUND
        }
    }

    private fun executeCsi(command: Char) {
        val parameters = csi.toString().removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun parameter(index: Int, default: Int = 1) = parameters.getOrElse(index) { default }.let { if (it == 0) default else it }
        when (command) {
            'A' -> cursorRow = (cursorRow - parameter(0)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + parameter(0)).coerceAtMost(rows - 1)
            'C' -> cursorColumn = (cursorColumn + parameter(0)).coerceAtMost(columns - 1)
            'D' -> cursorColumn = (cursorColumn - parameter(0)).coerceAtLeast(0)
            'H', 'f' -> moveTo(parameter(0) - 1, parameter(1) - 1)
            'J' -> when (parameters.firstOrNull() ?: 0) { 0 -> eraseToEndOfScreen(); 1 -> eraseToStartOfScreen(); else -> clear() }
            'K' -> when (parameters.firstOrNull() ?: 0) { 0 -> eraseToEndOfLine(); 1 -> for (column in 0..cursorColumn) cells[cursorRow][column] = ' '; else -> cells[cursorRow].fill(' ') }
            'm', 'h', 'l' -> Unit // Attributes and private modes do not change the text-cell model.
        }
    }

    fun line(row: Int, includeCursor: Boolean = false): String {
        require(row in 0 until rows)
        val result = cells[row].copyOf()
        // A character written in the final column advances the logical cursor
        // one position beyond the row.  The next printable byte wraps it; until
        // then there is no on-screen cell in which to draw the cursor.
        if (includeCursor && row == cursorRow && cursorColumn in 0 until columns) {
            result[cursorColumn] = '_'
        }
        return result.concatToString()
    }

    fun lines(includeCursor: Boolean = false): List<String> = List(rows) { line(it, includeCursor) }

    private fun feedGround(value: Int) {
        when (value) {
            0x07 -> Unit // BEL: no visual effect.
            0x08 -> if (cursorColumn > 0) cursorColumn--
            0x0A -> lineFeed()
            0x0B -> cursorRow = (cursorRow - 1).coerceAtLeast(0)
            0x0C -> cursorColumn = (cursorColumn + 1).coerceAtMost(columns - 1)
            0x0D -> cursorColumn = 0
            0x1A -> { clear(); cursorRow = 0; cursorColumn = 0 }
            0x1E -> { cursorRow = 0; cursorColumn = 0 }
            0x1B -> state = ParserState.ESCAPE
            else -> if (value >= 0x20) put(value.toChar())
        }
    }

    private fun feedEscape(value: Int) {
        when (value.toChar()) {
            '=' -> state = ParserState.ADDRESS_ROW
            '*', '+' -> { clear(); cursorRow = 0; cursorColumn = 0; state = ParserState.GROUND }
            'T', 't' -> { eraseToEndOfLine(); state = ParserState.GROUND }
            else -> state = ParserState.GROUND
        }
    }

    private fun put(character: Char) {
        if (cursorColumn >= columns) { cursorColumn = 0; lineFeed() }
        cells[cursorRow][cursorColumn++] = character
    }

    private fun lineFeed() {
        if (cursorRow < rows - 1) cursorRow++ else scrollUp()
    }

    private fun scrollUp() {
        for (row in 0 until rows - 1) cells[row] = cells[row + 1]
        cells[rows - 1] = CharArray(columns) { ' ' }
    }

    private fun reverseIndex() {
        for (row in rows - 1 downTo 1) cells[row] = cells[row - 1]
        cells[0] = CharArray(columns) { ' ' }
    }

    private fun clear() { for (row in cells.indices) cells[row].fill(' ') }
    private fun eraseToEndOfLine() { for (column in cursorColumn until columns) cells[cursorRow][column] = ' ' }
    private fun eraseToEndOfScreen() { eraseToEndOfLine(); for (row in cursorRow + 1 until rows) cells[row].fill(' ') }
    private fun eraseToStartOfScreen() { for (row in 0 until cursorRow) cells[row].fill(' '); for (column in 0..cursorColumn) cells[cursorRow][column] = ' ' }
    private fun moveTo(row: Int, column: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorColumn = column.coerceIn(0, columns - 1)
    }

    companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 25
    }
}
