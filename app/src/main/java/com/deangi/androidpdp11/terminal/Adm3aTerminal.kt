package com.deangi.androidpdp11.terminal

/**
 * A screen-oriented ADM-3A terminal. It is independent of Compose and the
 * emulator so local and telnet console bytes can share the same parser.
 */
class Adm3aTerminal(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
) {
    private enum class ParserState { GROUND, ESCAPE, ADDRESS_ROW, ADDRESS_COLUMN }

    private val cells = Array(rows) { CharArray(columns) { ' ' } }
    private var state = ParserState.GROUND
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
        when (state) {
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

    private fun clear() { for (row in cells.indices) cells[row].fill(' ') }
    private fun eraseToEndOfLine() { for (column in cursorColumn until columns) cells[cursorRow][column] = ' ' }
    private fun moveTo(row: Int, column: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorColumn = column.coerceIn(0, columns - 1)
    }

    companion object {
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 25
    }
}
