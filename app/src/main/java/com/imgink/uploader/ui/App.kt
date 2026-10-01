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
import com.imgink.uploader.ui.screens.HistoryScreen
import com.imgink.uploader.ui.screens.HomeScreen
import com.imgink.uploader.ui.screens.LoginScreen
import com.imgink.uploader.ui.screens.SettingsScreen

@Composable
fun App(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf("home") }

    // 分享进入：跳回首页并直接上传
    val shared by vm.sharedUri.collectAsState()
    LaunchedEffect(shared) {
        if (shared != null) {
            screen = "home"
            vm.startUpload(shared!!)
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
            SettingsScreen(vm, onBack = { screen = "home" })
        }
        else -> HomeScreen(vm, onHistory = { screen = "history" }, onSettings = { screen = "settings" })
    }
}
