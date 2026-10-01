package com.imgink.uploader.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.imgink.uploader.MainViewModel
import com.imgink.uploader.data.ImgItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ui by vm.history.collectAsState()
    var deleting by remember { mutableStateOf<ImgItem?>(null) }

    LaunchedEffect(Unit) { vm.loadHistory(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上传历史${ui.total?.let { "（$it）" } ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ui.error?.let {
                Text(
                    "⚠️ $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (ui.items.isEmpty() && !ui.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无记录", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ui.items, key = { it.id ?: it.pathname?.hashCode() ?: it.url.hashCode() }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = item.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name ?: "(未命名)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeText = item.size?.toDoubleOrNull()?.let { MainViewModel.fmtSize(it.toLong()) } ?: ""
                                    Text(
                                        "${item.upload_date ?: ""} · $sizeText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = {
                                    item.url?.let { url ->
                                        clipboard.setText(AnnotatedString(url))
                                        Toast.makeText(ctx, "链接已复制", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Icon(Icons.Default.ContentCopy, contentDescription = "复制链接") }
                                IconButton(onClick = {
                                    item.url?.let {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                                    }
                                }) { Icon(Icons.Default.OpenInBrowser, contentDescription = "打开") }
                                IconButton(onClick = { deleting = item }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                    item {
                        when {
                            ui.loading -> Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                            ui.page < ui.lastPage -> OutlinedButton(
                                onClick = { vm.loadHistory(false) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("加载更多") }
                        }
                    }
                }
            }
        }

        deleting?.let { item ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("删除图片") },
                text = { Text("确定删除「${item.name}」？该图床上的图片将被删除，不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        item.id?.let { vm.deleteImage(it) }
                        deleting = null
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
            )
        }
    }
}
