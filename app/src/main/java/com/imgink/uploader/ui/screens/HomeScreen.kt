package com.imgink.uploader.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.imgink.uploader.ItemState
import com.imgink.uploader.MainViewModel
import com.imgink.uploader.UploadState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onHistory: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by vm.uploadState.collectAsState()

    // 单张成功自动复制链接（一次性副作用：复制过即标记，切页返回不重放）；多张不自动复制，靠「复制全部」按钮
    LaunchedEffect(state) {
        val s = state as? UploadState.BatchFinished ?: return@LaunchedEffect
        val url = s.singleUrl ?: return@LaunchedEffect
        if (s.copied) return@LaunchedEffect
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(ctx, "链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
        vm.markCopied(s)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MainViewModel.MAX_BATCH)
    ) { uris ->
        if (uris.isNotEmpty()) vm.startBatch(uris)
    }
    var camFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile
        if (ok && f != null && f.exists()) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            vm.startBatch(listOf(uri))
        }
    }

    fun copy(text: String, label: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ImgInk 图床") },
                actions = {
                    TextButton(onClick = onHistory) { Text("历史") }
                    TextButton(onClick = onSettings) { Text("设置") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("相册多选")
                }
                FilledTonalButton(
                    onClick = {
                        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                        val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
                        camFile = f
                        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("拍照上传")
                }
            }

            Text(
                "💡 相册一次可选最多 ${MainViewModel.MAX_BATCH} 张批量上传；也可在任意应用（如相册）里「分享」多张图给本应用直接上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            when (val s = state) {
                is UploadState.Idle -> Unit
                is UploadState.Error -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("❌ 上传失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.dismissUpload() }) { Text("知道了") }
                    }
                }
                is UploadState.BatchRunning -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cur = s.current
                        val title = if (s.total == 1) {
                            "⏳ 上传中：${cur?.name.orEmpty()}"
                        } else {
                            "⏳ 批量上传 ${s.done + s.failed + (if (cur != null) 1 else 0)}/${s.total}：${cur?.name?.ifBlank { "…" } ?: ""}"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { s.overallProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${s.overallProgress}%", style = MaterialTheme.typography.bodySmall)
                        if (s.pending > 0 || s.total > 1) {
                            TextButton(onClick = { vm.cancelRemaining() }) { Text("取消剩余") }
                        }
                    }
                }
                is UploadState.BatchFinished -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val title = when {
                            s.done > 0 && s.failed == 0 && !s.cancelled -> "✅ 上传完成 · ${s.done} 张"
                            s.done > 0 -> "📤 上传结束：${s.done} 成功 · ${s.failed} 失败" + if (s.cancelled) " · 已取消" else ""
                            s.failed > 0 -> "❌ 上传失败 · ${s.failed} 张"
                            else -> "⏹ 已取消"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)

                        if (s.singleUrl != null) {
                            SelectionContainer {
                                Text(
                                    s.singleUrl!!,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            s.items.forEachIndexed { idx, item ->
                                when (val st = item.state) {
                                    is ItemState.Done -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "${idx + 1}. ${item.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        SelectionContainer {
                                            Text(
                                                st.url,
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    is ItemState.Failed -> Text(
                                        "❌ ${item.name.ifBlank { "第 ${idx + 1} 张" }}：${st.reason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    else -> Unit
                                }
                            }
                            if (s.skipped > 0) {
                                Text(
                                    "⏭ ${s.skipped} 张未上传（已取消）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        if (s.done > 0) {
                            val totalSize = s.items.sumOf { (it.state as? ItemState.Done)?.size ?: 0L }
                            Text(
                                "共 ${s.done} 张 · ${MainViewModel.fmtSize(totalSize)}\n${MainViewModel.fmtQuota(s.quotaTotal, s.quotaUsed)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (s.pushStatus.isNotBlank()) {
                            Text(s.pushStatus, style = MaterialTheme.typography.bodySmall)
                        }

                        if (s.urls.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { copy(s.urls.joinToString("\n"), "链接") }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (s.urls.size == 1) "链接" else "全部链接")
                                }
                                OutlinedButton(onClick = {
                                    copy(s.urls.joinToString("\n") { "![]($it)" }, "Markdown")
                                }) { Text("MD") }
                                OutlinedButton(onClick = {
                                    copy(s.urls.joinToString("\n") { "<img src=\"$it\" />" }, "HTML")
                                }) { Text("HTML") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (s.failed > 0) {
                                TextButton(onClick = { vm.retryFailed() }) { Text("重试失败项") }
                            }
                            if (s.singleUrl != null) {
                                TextButton(onClick = {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.singleUrl)))
                                }) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("浏览器打开")
                                }
                            }
                            if (s.done > 0) {
                                TextButton(onClick = { vm.repush() }) {
                                    Icon(Icons.Default.Send, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("推送飞书")
                                }
                            }
                            TextButton(onClick = { vm.dismissUpload() }) { Text("关闭") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
