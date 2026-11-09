package com.deangi.androidpdp11.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TerminalDisplay(
    terminal: Adm3aTerminal,
    renderVersion: Int,
    onConsoleInput: (Byte) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val imeFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var keyboardRequested by remember { mutableStateOf(false) }
    var imeText by remember { mutableStateOf("") }
    // The model intentionally has no Compose dependency.  This token makes a
    // received terminal byte batch observable to the display layer.
    val screenText = remember(renderVersion, terminal, interactive) { terminal.lines(includeCursor = interactive).joinToString("\n") }
    LaunchedEffect(focusRequester, interactive) { if (interactive) focusRequester.requestFocus() }
    // The bezel is a parent container, making its width external to the
    // 80-column by 25-line terminal viewport rather than consuming it.
    Box(
        modifier = modifier
            .width(608.dp)
            .height(373.dp)
            .border(8.dp, Color(0xFF15452A))
            .background(Color.Black)
            .padding(8.dp)
            .pointerInput(keyboardController, interactive) {
                if (!interactive) return@pointerInput
                detectTapGestures(
                    onDoubleTap = {
                        if (keyboardRequested) {
                            keyboardRequested = false
                            keyboardController?.hide()
                            focusRequester.requestFocus()
                        } else {
                            keyboardRequested = true
                            imeFocusRequester.requestFocus()
                            scope.launch {
                                delay(100)
                                keyboardController?.show()
                            }
                        }
                    },
                )
            },
    ) {
        Text(
            text = screenText,
            color = Color(0xFF55FF8A),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            softWrap = false,
            modifier = Modifier
                // Leave several spare character cells beyond 80 columns;
                // device font metrics are wider than the nominal estimate.
                .width(592.dp)
                .height(357.dp)
                .background(Color.Black)
            .padding(8.dp)
            .then(if (interactive) Modifier.focusRequester(focusRequester).focusable().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val value = com.deangi.androidpdp11.console.ConsoleKeyMapper.toConsoleByte(
                    event.key.keyCode.toInt(),
                    event.utf16CodePoint,
                ) ?: return@onPreviewKeyEvent false
                onConsoleInput(value)
                true
            } else Modifier),
        )
        // An invisible editable target supplies the Android IME connection.
        // It is focused only after a terminal double-tap.
        if (interactive) BasicTextField(
            value = imeText,
            onValueChange = { updated ->
                when {
                    updated.startsWith(imeText) -> {
                        updated.drop(imeText.length).forEach { character ->
                            val value = if (character == '\n') '\r'.code else character.code
                            if (value in 0x00..0xFF) onConsoleInput(value.toByte())
                        }
                    }
                    imeText.startsWith(updated) -> repeat(imeText.length - updated.length) {
                        onConsoleInput('\b'.code.toByte())
                    }
                }
                imeText = updated.takeLast(256)
            },
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(imeFocusRequester),
        )
    }
}
