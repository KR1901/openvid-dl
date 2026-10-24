package dev.kr1901.openvid.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A small in-app browser that watches network requests for media URLs. Tap a
 * detected stream to send it back to the Home tab and fetch its formats.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String,
    onPickMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var address by remember { mutableStateOf(initialUrl) }
    val detected = remember { mutableStateListOf<String>() }
    val webView = remember { mutableStateOf<WebView?>(null) }
    val mediaRegex = remember { Regex("""(?i)\.(m3u8|mp4|webm|m4a|mp3)(\?|$)""") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                label = { Text("Address") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { webView.value?.loadUrl(address) }) { Text("Go") }
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                val url = request.url.toString()
                                if (mediaRegex.containsMatchIn(url) && !detected.contains(url)) {
                                    detected.add(url)
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                address = url
                            }
                        }
                        loadUrl(initialUrl)
                        webView.value = this
                    }
                },
            )
        }

        if (detected.isNotEmpty()) {
            Text(
                "Detected media",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(detected) { url ->
                    ListItem(
                        headlineContent = {
                            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable { onPickMedia(url) },
                    )
                }
            }
        }
    }
}
