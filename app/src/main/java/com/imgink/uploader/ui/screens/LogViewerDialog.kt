package com.imgink.uploader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.imgink.uploader.data.AppLog

@Composable
fun LogViewerDialog(vm: com.imgink.uploader.MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(AppLog.readAll()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地诊断日志") },
        text = {
            Column {
                if (text.isBlank()) {
                    Text("(空)", color = MaterialTheme.colorScheme.outline)
                } else {
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .height(360.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(ctx, "日志已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制全部") }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.clearLogs()
                text = AppLog.readAll()
            }) { Text("清空") }
        }
    )
}
