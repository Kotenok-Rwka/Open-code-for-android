package com.pocketcode.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import com.pocketcode.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** Внешняя ссылка: в превью показываем «Копировать» и «В браузере». */
fun isHttpUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")

/** Просмотр HTML-страницы проекта прямо в приложении. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(file: File, onBack: () -> Unit) = PreviewScreen("file://" + file.absolutePath, file.name, onBack)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(url: String, title: String, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.backIcon(), contentDescription = "Назад") } },
                actions = {
                    if (isHttpUrl(url)) {
                        TextButton(onClick = {
                            val cm = ctx.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("Ссылка", url))
                        }) { Text("Копировать") }
                        TextButton(onClick = {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            ctx.startActivity(Intent.createChooser(i, "Открыть в браузере"))
                        }) { Text("В браузере") }
                    }
                    TextButton(onClick = { web?.reload() }) { Text("Обновить") }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    web = this
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
