package dev.kr1901.openvid.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private enum class Tab { HOME, BROWSER, DOWNLOADS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(sharedUrl: String?) {
    val vm: MainViewModel = viewModel()
    val context = LocalContext.current
    // Remembered here so the browsing session survives tab switches.
    val browser = remember { BrowserController(context) }

    var tab by remember { mutableStateOf(Tab.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            vm.setUrl(sharedUrl)
            tab = Tab.HOME
        }
    }

    Scaffold(
        topBar = {
            when (tab) {
                Tab.HOME -> TopAppBar(
                    title = { Text("openvid-dl", fontWeight = FontWeight.SemiBold) },
                )
                Tab.DOWNLOADS -> TopAppBar(title = { Text("Downloads") })
                Tab.BROWSER -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            Tab.HOME -> HomeScreen(vm, showMessage, modifier)
            Tab.BROWSER -> BrowserScreen(browser, showMessage, modifier)
            Tab.DOWNLOADS -> DownloadsScreen(modifier)
        }
    }
}
