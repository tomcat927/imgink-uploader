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
import com.imgink.uploader.data.Feishu
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val hookFlow by vm.webhook.collectAsState()
    val secFlow by vm.secret.collectAsState()
    val autoFlow by vm.autoPush.collectAsState()
    val baseFlow by vm.baseUrl.collectAsState()

    // null 表示尚未从 DataStore 加载完成
    var hook by remember { mutableStateOf<String?>(null) }
    var sec by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(hookFlow) { if (hook == null) hook = hookFlow }
    LaunchedEffect(secFlow) { if (sec == null) sec = secFlow }
    LaunchedEffect(baseFlow) { if (base == null) base = baseFlow }
    LaunchedEffect(autoFlow) { if (auto == null) auto = autoFlow }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val hv = hook ?: ""
    val sv = sec ?: ""
    val bv = base ?: ""
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
                    vm.saveSettings(bv, hv, sv, av)
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

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("账号", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录 / 清除 Token")
            }

            Spacer(Modifier.padding(bottom = 24.dp))
            Text(
                "v0.1.0 · APK 由 GitHub Actions 自动构建",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
