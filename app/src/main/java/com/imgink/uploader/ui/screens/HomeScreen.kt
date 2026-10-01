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
import androidx.compose.foundation.layout.size
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
import com.imgink.uploader.MainViewModel
import com.imgink.uploader.UploadState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onHistory: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by vm.uploadState.collectAsState()

    // 上传成功后自动复制链接（一次性副作用：复制过即标记，切页返回不重放）
    LaunchedEffect(state) {
        val s = state as? UploadState.Success ?: return@LaunchedEffect
        if (s.copied) return@LaunchedEffect
        clipboard.setText(AnnotatedString(s.url))
        Toast.makeText(ctx, "链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
        vm.markCopied(s)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.startUpload(it) }
    }
    var camFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile
        if (ok && f != null && f.exists()) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            vm.startUpload(uri)
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
                    Text("相册选择")
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
                "💡 也可以在任意应用（如截图预览）中「分享」选择本应用直接上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            when (val s = state) {
                is UploadState.Idle -> Unit
                is UploadState.Uploading -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⏳ 上传中：${s.name}", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { s.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${s.progress}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is UploadState.Success -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✅ 上传成功", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            Text(
                                s.url,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "文件：${s.name} · ${MainViewModel.fmtSize(s.size)}\n${MainViewModel.fmtQuota(s.quotaTotal, s.quotaUsed)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(s.pushStatus, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { copy(s.url, "链接") }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("链接")
                            }
                            OutlinedButton(onClick = { copy("![](${s.url})", "Markdown") }) { Text("MD") }
                            OutlinedButton(onClick = { copy("<img src=\"${s.url}\" />", "HTML") }) { Text("HTML") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
                            }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("浏览器打开")
                            }
                            TextButton(onClick = { vm.repush() }) {
                                Icon(Icons.Default.Send, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("重推飞书")
                            }
                            TextButton(onClick = { vm.dismissUpload() }) { Text("关闭") }
                        }
                    }
                }
                is UploadState.Error -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("❌ 上传失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.dismissUpload() }) { Text("知道了") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
