package com.silicicsoft.doctorcam.feature.test

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.silicicsoft.doctorcam.BuildConfig
import kotlinx.serialization.Serializable

@Serializable
data object TestRoute

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TestPage(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.testTag("uvc_vision_test_screen"),
        topBar = { TopAppBar(title = { Text("TestPage") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("UVC Vision 开发检查")
            Text("版本 ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
            Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
            Text("ABI ${Build.SUPPORTED_ABIS.joinToString()}")
            Text("当前为本地验证包。UVC 预览与抓拍需要真实摄像头；原生库和模型由 instrumentation 回归验证。")
            Button(onClick = onBack, modifier = Modifier.testTag("uvc_vision_test_back")) { Text("返回业务页面") }
        }
    }
}
