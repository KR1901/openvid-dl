package dev.kr1901.openvid.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab { HOME, BROWSER, DOWNLOADS }

@Composable
fun AppRoot(sharedUrl: String?) {
    val vm: MainViewModel = viewModel()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            vm.setUrl(sharedUrl)
            tab = Tab.HOME
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = tab == Tab.BROWSER,
                    onClick = { tab = Tab.BROWSER },
                    icon = { Icon(Icons.Filled.Public, contentDescription = "Browser") },
                    label = { Text("Browser") },
                )
                NavigationBarItem(
                    selected = tab == Tab.DOWNLOADS,
                    onClick = { tab = Tab.DOWNLOADS },
                    icon = { Icon(Icons.Filled.Download, contentDescription = "Downloads") },
                    label = { Text("Downloads") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(vm, modifier)
            Tab.BROWSER -> BrowserScreen(
                initialUrl = browserUrl,
                onPickMedia = { url ->
                    browserUrl = url
                    vm.setUrl(url)
                    tab = Tab.HOME
                },
                modifier = modifier,
            )
            Tab.DOWNLOADS -> DownloadsScreen(modifier)
        }
    }
}
