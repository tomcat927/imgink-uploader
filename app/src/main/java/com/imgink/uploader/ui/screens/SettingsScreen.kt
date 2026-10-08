package com.imgink.uploader.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.imgink.uploader.MainViewModel
import com.imgink.uploader.UpdateViewModel
import com.imgink.uploader.data.Feishu
import com.imgink.uploader.data.OpenListLog
import com.imgink.uploader.data.Updater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, updateVm: UpdateViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val hookFlow by vm.webhook.collectAsState()
    val secFlow by vm.secret.collectAsState()
    val autoFlow by vm.autoPush.collectAsState()
    val baseFlow by vm.baseUrl.collectAsState()
    val folderFlow by vm.uploadFolder.collectAsState()

    // null 表示尚未从 DataStore 加载完成
    var hook by remember { mutableStateOf<String?>(null) }
    var sec by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(hookFlow) { if (hook == null) hook = hookFlow }
    LaunchedEffect(secFlow) { if (sec == null) sec = secFlow }
    LaunchedEffect(baseFlow) { if (base == null) base = baseFlow }
    LaunchedEffect(folderFlow) { if (folder == null) folder = folderFlow }
    LaunchedEffect(autoFlow) { if (auto == null) auto = autoFlow }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val updateState by updateVm.state.collectAsState()
    val autoCheck by updateVm.autoCheckUpdate.collectAsState()
    val directDl by updateVm.directDownload.collectAsState()

    // 远程日志配置（null = 未从 DataStore 加载完成）
    val rlOnFlow by vm.rlEnabled.collectAsState()
    val rlBaseFlow by vm.rlBaseUrl.collectAsState()
    val rlUserFlow by vm.rlUsername.collectAsState()
    val rlPassFlow by vm.rlPassword.collectAsState()
    val rlPathFlow by vm.rlTargetPath.collectAsState()
    val rlLastFlow by vm.rlLastUpload.collectAsState()
    var rlOn by remember { mutableStateOf<Boolean?>(null) }
    var rlBase by remember { mutableStateOf<String?>(null) }
    var rlUser by remember { mutableStateOf<String?>(null) }
    var rlPass by remember { mutableStateOf<String?>(null) }
    var rlPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rlOnFlow) { if (rlOn == null) rlOn = rlOnFlow }
    LaunchedEffect(rlBaseFlow) { if (rlBase == null) rlBase = rlBaseFlow }
    LaunchedEffect(rlUserFlow) { if (rlUser == null) rlUser = rlUserFlow }
    LaunchedEffect(rlPassFlow) { if (rlPass == null) rlPass = rlPassFlow }
    LaunchedEffect(rlPathFlow) { if (rlPath == null) rlPath = rlPathFlow }
    val rlOnV = rlOn ?: false
    val rlBaseV = rlBase ?: ""
    val rlUserV = rlUser ?: ""
    val rlPassV = rlPass ?: ""
    val rlPathV = rlPath ?: ""
    var rlBusy by remember { mutableStateOf<String?>(null) }
    var rlResult by remember { mutableStateOf<String?>(null) }
    var showLogViewer by remember { mutableStateOf(false) }

    // 手动检查结果为"已是最新"时用 Toast 轻提示，不弹窗打断
    val updateCtx = LocalContext.current
    LaunchedEffect(updateState) {
        if (updateState is com.imgink.uploader.UpdateState.UpToDate) {
            Toast.makeText(
                updateCtx,
                "已是最新版本 v${updateVm.currentVersionName}",
                Toast.LENGTH_SHORT
            ).show()
            updateVm.dismiss()
        }
    }

    val buildTime = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(updateVm.currentVersionCode * 1000))
    }

    val hv = hook ?: ""
    val sv = sec ?: ""
    val bv = base ?: ""
    val fv = folder ?: ""
    val av = auto ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("飞书推送", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = hv,
                onValueChange = { hook = it },
                label = { Text("Webhook URL") },
                placeholder = { Text("https://open.feishu.cn/open-apis/bot/v2/hook/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sv,
                onValueChange = { sec = it },
                label = { Text("签名密钥（未开启签名校验可留空）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上传成功后自动推送飞书群", Modifier.weight(1f))
                Switch(checked = av, onCheckedChange = { auto = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    vm.saveSettings(bv, hv, sv, av, fv)
                    Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
                OutlinedButton(
                    onClick = {
                        testing = true; testResult = null
                        scope.launch {
                            val r = Feishu.sendCard(
                                hv.trim(), sv.trim().ifBlank { null },
                                "📤 测试消息 · 图床",
                                "**这是一条测试消息**\n\n配置成功后，每次上传图片都会自动把链接推送到本群。",
                                null
                            )
                            testResult = r.fold({ "✅ 已发送，请在飞书群查看" }, { "❌ ${it.message}" })
                            testing = false
                        }
                    },
                    enabled = hv.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("发送测试消息")
                }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "群机器人安全设置建议：只开「签名校验」（填上方密钥），或开「自定义关键词」并设为「图床」；不要开 IP 白名单（手机 IP 不固定）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("图床 API", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = bv,
                onValueChange = { base = it },
                label = { Text("API 站点地址") },
                placeholder = { Text("https://img.ink/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fv,
                onValueChange = { folder = it },
                label = { Text("上传目标文件夹") },
                placeholder = { Text("默认 imgink，仅限英文字母数字") },
                supportingText = { Text("留空使用 imgink；非法字符会在上传时自动剔除") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("版本与更新", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前版本 v${updateVm.currentVersionName} · 构建于 $buildTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启动时自动检查更新", Modifier.weight(1f))
                Switch(checked = autoCheck, onCheckedChange = { updateVm.saveAutoCheckUpdate(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("更新下载直连（配合国内加速代理）", Modifier.weight(1f))
                Switch(checked = directDl, onCheckedChange = { updateVm.saveDirectDownload(it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { updateVm.checkNow() },
                    enabled = updateState !is com.imgink.uploader.UpdateState.Checking
                ) {
                    if (updateState is com.imgink.uploader.UpdateState.Checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("检查更新")
                }
            }
            Text(
                "每次推送代码都会自动构建新版本并发布，App 启动时检查到新版本即可一键下载安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("诊断日志", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用远程日志（OpenList）", Modifier.weight(1f))
                Switch(
                    checked = rlOnV,
                    onCheckedChange = {
                        rlOn = it
                        vm.setRemoteLogEnabled(it) // 开关即时落盘，不再依赖「保存」
                    }
                )
            }
            OutlinedTextField(
                value = rlBaseV,
                onValueChange = { rlBase = it },
                label = { Text("OpenList 地址") },
                placeholder = { Text("http://192.168.x.x:5244") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rlUserV,
                onValueChange = { rlUser = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rlPassV,
                onValueChange = { rlPass = it },
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rlPathV,
                onValueChange = { rlPath = it },
                label = { Text("日志目标路径") },
                placeholder = { Text("/imgink-uploader/logs") },
                supportingText = { Text("上传失败时会自动上传脱敏日志快照到该目录下的 install-<设备ID>/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        // 传 rlOn（可空）：未加载完成时不写 enabled，避免把已保存的开关覆盖掉
                        vm.persistRemoteLog(rlOn, rlBaseV, rlUserV, rlPassV, rlPathV)
                        Toast.makeText(ctx, "诊断配置已保存", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
                OutlinedButton(
                    onClick = {
                        rlBusy = "test"; rlResult = null
                        scope.launch {
                            val r = OpenListLog.testConnection(rlBaseV, rlUserV, rlPassV)
                            rlResult = r.fold(
                                onSuccess = { "✅ 连接成功" },
                                onFailure = { "❌ ${it.message}" }
                            )
                            rlBusy = null
                        }
                    },
                    enabled = rlBusy == null
                ) { Text(if (rlBusy == "test") "测试中…" else "测试连接") }
                OutlinedButton(
                    onClick = {
                        rlBusy = "upload"; rlResult = null
                        scope.launch {
                            // 先落盘再上传：uploadSnapshot 读的是 DataStore，不是本页的表单状态
                            vm.persistRemoteLog(rlOn, rlBaseV, rlUserV, rlPassV, rlPathV)
                            val r = OpenListLog.uploadSnapshot(ctx)
                            rlResult = r.fold(
                                onSuccess = { "✅ 已上传：$it" },
                                onFailure = { "❌ ${it.message}" }
                            )
                            rlBusy = null
                        }
                    },
                    enabled = rlBusy == null
                ) { Text(if (rlBusy == "upload") "上传中…" else "上传日志") }
            }
            rlResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (rlLastFlow.isNotBlank()) {
                Text(
                    "上次上传：$rlLastFlow",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showLogViewer = true }) { Text("查看本地日志") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("账号", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录 / 清除 Token")
            }

            Spacer(Modifier.padding(bottom = 24.dp))
            Text(
                "APK 由 GitHub Actions 自动构建并签名发布",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    if (showLogViewer) {
        LogViewerDialog(vm) { showLogViewer = false }
    }
}
