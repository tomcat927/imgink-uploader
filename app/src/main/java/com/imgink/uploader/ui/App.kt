package com.imgink.uploader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.imgink.uploader.MainViewModel
import com.imgink.uploader.UpdateViewModel
import com.imgink.uploader.ui.screens.HistoryScreen
import com.imgink.uploader.ui.screens.HomeScreen
import com.imgink.uploader.ui.screens.LoginScreen
import com.imgink.uploader.ui.screens.SettingsScreen
import com.imgink.uploader.ui.screens.UpdateDialog

@Composable
fun App(vm: MainViewModel, updateVm: UpdateViewModel) {
    var screen by rememberSaveable { mutableStateOf("home") }

    // 启动时按配置静默检查更新（每进程一次）
    LaunchedEffect(Unit) { updateVm.autoCheckIfNeeded() }

    // 分享进入：跳回首页并直接上传（单张 / 多张共用批量队列）
    val shared by vm.sharedUris.collectAsState()
    LaunchedEffect(shared) {
        if (!shared.isNullOrEmpty()) {
            screen = "home"
            vm.startBatch(shared!!)
            vm.consumeShared()
        }
    }

    val token by vm.token.collectAsState()

    when {
        token.isBlank() -> LoginScreen(vm)
        screen == "history" -> {
            BackHandler { screen = "home" }
            HistoryScreen(vm, onBack = { screen = "home" })
        }
        screen == "settings" -> {
            BackHandler { screen = "home" }
            SettingsScreen(vm, updateVm, onBack = { screen = "home" })
        }
        else -> HomeScreen(vm, onHistory = { screen = "history" }, onSettings = { screen = "settings" })
    }

    UpdateDialog(updateVm)
}
