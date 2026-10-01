package com.imgink.uploader

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imgink.uploader.data.SettingsRepo
import com.imgink.uploader.data.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: Updater.UpdateInfo) : UpdateState
    data class Downloading(val received: Long, val total: Long) : UpdateState {
        val percent: Int
            get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
    }
    data class Ready(val info: Updater.UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)

    val autoCheckUpdate: StateFlow<Boolean> =
        repo.autoCheckUpdate.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val directDownload: StateFlow<Boolean> =
        repo.directDownload.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionName: String = Updater.currentVersionName(app)
    val currentVersionCode: Long = Updater.currentVersionCode(app)

    /** 每个进程只静默自动检查一次 */
    private var autoChecked = false

    fun saveAutoCheckUpdate(v: Boolean) {
        viewModelScope.launch { repo.saveAutoCheckUpdate(v) }
    }

    fun saveDirectDownload(v: Boolean) {
        viewModelScope.launch { repo.saveDirectDownload(v) }
    }

    /** 启动静默检查：只有发现新版本才弹窗，无更新或失败均不打扰 */
    fun autoCheckIfNeeded() {
        if (autoChecked || !autoCheckUpdate.value) {
            autoChecked = true
            return
        }
        autoChecked = true
        viewModelScope.launch {
            try {
                val info = Updater.checkForUpdate(getApplication(), directDownload.value)
                if (info != null) _state.value = UpdateState.Available(info)
            } catch (_: Exception) {
            }
        }
    }

    fun checkNow() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = Updater.checkForUpdate(getApplication(), directDownload.value)
                _state.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "检查更新失败")
            }
        }
    }

    fun download(info: Updater.UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(0, 0)
        viewModelScope.launch {
            try {
                val apk = Updater.downloadAndVerify(
                    getApplication(), info, directDownload.value
                ) { received, total ->
                    _state.value = UpdateState.Downloading(received, total)
                }
                _state.value = UpdateState.Ready(info, apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "下载失败")
            }
        }
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Idle
    }

    /** 安装：无「安装未知应用」权限时先跳系统设置，返回是否已发起安装 */
    fun install(context: Context, apk: File): Boolean {
        return if (Updater.canInstall(context)) {
            Updater.installApk(context, apk)
            true
        } else {
            Updater.openInstallPermissionSettings(context)
            false
        }
    }
}
