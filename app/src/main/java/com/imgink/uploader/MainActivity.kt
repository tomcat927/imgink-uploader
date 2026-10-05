package com.imgink.uploader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import com.imgink.uploader.UpdateViewModel
import com.imgink.uploader.ui.App
import com.imgink.uploader.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        val updateVm = ViewModelProvider(this)[UpdateViewModel::class.java]
        handleIntent(intent, vm)
        setContent {
            AppTheme {
                App(vm, updateVm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        handleIntent(intent, vm)
    }

    /** 接收系统分享：其他应用「分享 → ImgInk 图床」时直接触发上传（支持单张与多张） */
    private fun handleIntent(intent: Intent?, vm: MainViewModel) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        if (intent.type?.startsWith("image/") != true) return
        val uris = when (action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            else ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty().filterNotNull()
        }
        vm.onShared(uris)
    }
}
