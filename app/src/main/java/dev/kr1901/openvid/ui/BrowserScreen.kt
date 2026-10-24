package dev.kr1901.openvid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.kr1901.openvid.engine.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AUDIO_REGEX = Regex("""(?i)\.(mp3|m4a|aac|ogg|opus)(\?|#|$)""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    controller: BrowserController,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val tab = controller.active

    var editing by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    var selected by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val formats = remember { mutableStateListOf<MediaFormat>() }
    var selectedFormat by remember { mutableStateOf<String?>(null) }
    val picked = remember { mutableStateListOf<String>() }

    fun loadFormats(url: String) {
        selected = url
        error = null
        formats.clear()
        selectedFormat = null
        fetching = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { resolveFormats(url) }
            fetching = false
            formats.addAll(list)
            selectedFormat = (list.firstOrNull { it.kind != "audio" } ?: list.firstOrNull())?.id
            if (list.isEmpty()) error = "No downloadable formats were found for this link."
        }
    }

    fun quickDownload(url: String) {
        scope.launch {
            busy = true
            val format = withContext(Dispatchers.IO) { resolveBestFormat(url) }
            busy = false
            if (format != null) {
                launchDownload(context, format, fileNameOf(url))
                onMessage("Queued download")
            } else {
                onMessage("Couldn't find a downloadable stream")
            }
        }
    }

    fun downloadMany(urls: List<String>) {
        if (urls.isEmpty()) return
        scope.launch {
            busy = true
            var queued = 0
            withContext(Dispatchers.IO) {
                urls.forEach { url ->
                    val format = resolveBestFormat(url)
                    if (format != null) {
                        launchDownload(context, format, fileNameOf(url))
                        queued++
                    }
                }
            }
            busy = false
            showSheet = false
            picked.clear()
            onMessage(if (queued == 0) "Nothing downloadable found" else "Queued $queued download(s)")
        }
    }

    // Back: leave the keyboard, then the tab switcher, then page history.
    BackHandler(enabled = editing || showTabs || tab.canGoBack) {
        when {
            editing -> editing = false
            showTabs -> showTabs = false
            else -> controller.back()
        }
    }

    // Probe the active page for media; reset the address editor on tab change.
    LaunchedEffect(tab.id) {
        editing = false
        while (true) {
            delay(2500)
            controller.probe()
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { controller.ensureWebView(tab) },
                modifier = Modifier.fillMaxSize(),
            )

            if (tab.loading) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                )
            }

            MediaChip(
                count = tab.detected.size,
                onClick = {
                    selected = null
                    picked.clear()
                    showSheet = true
                },
            )
        }

        BrowserBottomBar(
            controller = controller,
            tab = tab,
            editing = editing,
            onStartEditing = { editing = true },
            onStopEditing = { editing = false },
            onGo = { controller.go(it) },
            onDownload = {
                selected = null
                picked.clear()
                showSheet = true
            },
            onTabs = { showTabs = true },
            onMessage = onMessage,
        )
    }

    if (showTabs) {
        TabsOverlay(controller = controller, onDismiss = { showTabs = false })
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                when {
                    selected != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                selected = null
                                formats.clear()
                                error = null
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    fileNameOf(selected!!),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    hostOf(selected!!),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        when {
                            fetching -> Box(
                                Modifier.fillMaxWidth().height(140.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }

                            error != null -> {
                                Text(error!!, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(10.dp))
                                FilledTonalButton(onClick = { loadFormats(selected!!) }) { Text("Retry") }
                            }

                            else -> {
                                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                    items(formats, key = { it.id }) { format ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { selectedFormat = format.id }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = format.id == selectedFormat,
                                                onClick = { selectedFormat = format.id },
                                            )
                                            Text(
                                                format.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                PrimaryAction(
                                    text = "Download",
                                    icon = Icons.Filled.Download,
                                    onClick = {
                                        formats.firstOrNull { it.id == selectedFormat }?.let { format ->
                                            launchDownload(context, format, fileNameOf(selected!!))
                                            showSheet = false
                                            onMessage("Queued download")
                                        }
                                    },
                                    enabled = selectedFormat != null,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    tab.detected.isEmpty() -> {
                        Text(
                            "Nothing detected yet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Play the video for a moment so the page starts streaming, or try " +
                                "extracting from the page link itself. If a site hides the video, " +
                                "try the desktop-site option from the browser menu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryAction(
                            text = "Try this page's link",
                            icon = Icons.Filled.Language,
                            onClick = { loadFormats(tab.url) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    else -> {
                        Text(
                            "Videos on this page",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${tab.detected.size} downloadable stream(s) found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val allPicked = picked.size == tab.detected.size
                            TextButton(onClick = {
                                if (allPicked) {
                                    picked.clear()
                                } else {
                                    picked.clear()
                                    picked.addAll(tab.detected)
                                }
                            }) {
                                Text(if (allPicked) "Clear selection" else "Select all")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { loadFormats(tab.url) }) {
                                Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("This page")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(
                            Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(tab.detected, key = { it }) { url ->
                                VideoCard(
                                    title = fileNameOf(url),
                                    subtitle = hostOf(url),
                                    isAudio = AUDIO_REGEX.containsMatchIn(url),
                                    selected = picked.contains(url),
                                    selectable = true,
                                    onToggleSelect = {
                                        if (picked.contains(url)) picked.remove(url) else picked.add(url)
                                    },
                                    onDownload = { quickDownload(url) },
                                    onClick = { loadFormats(url) },
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val targets =
                            if (picked.isEmpty()) tab.detected.toList()
                            else tab.detected.filter { picked.contains(it) }
                        PrimaryAction(
                            text = when {
                                busy -> "Queueing…"
                                picked.isEmpty() -> "Download all (${targets.size})"
                                else -> "Download selected (${targets.size})"
                            },
                            icon = Icons.Filled.Download,
                            onClick = { downloadMany(targets) },
                            enabled = targets.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserBottomBar(
    controller: BrowserController,
    tab: BrowserTab,
    editing: Boolean,
    onStartEditing: () -> Unit,
    onStopEditing: () -> Unit,
    onGo: (String) -> Unit,
    onDownload: () -> Unit,
    onTabs: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.back() }, enabled = tab.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            if (editing) {
                val focusRequester = remember { FocusRequester() }
                var value by remember(tab.url) {
                    mutableStateOf(TextFieldValue(tab.url, TextRange(0, tab.url.length)))
                }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("Search or enter address", maxLines = 1) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        onGo(value.text)
                        onStopEditing()
                    }),
                    trailingIcon = {
                        IconButton(onClick = onStopEditing) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    },
                )
                Spacer(Modifier.width(4.dp))
                FilledIconButton(onClick = {
                    onGo(value.text)
                    onStopEditing()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go")
                }
            } else {
                AddressPill(tab = tab, onClick = onStartEditing, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))

                Box {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "Download")
                    }
                    CountBadge(count = tab.detected.size, modifier = Modifier.align(Alignment.TopEnd))
                }
                Box {
                    IconButton(onClick = onTabs) {
                        Icon(Icons.Filled.Tab, contentDescription = "Tabs")
                    }
                    CountBadge(count = controller.tabs.size, showWhenOne = false, modifier = Modifier.align(Alignment.TopEnd))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (controller.desktopMode) "Mobile site" else "Desktop site") },
                            leadingIcon = {
                                Icon(
                                    if (controller.desktopMode) Icons.Filled.Smartphone else Icons.Filled.DesktopWindows,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                controller.toggleDesktopMode()
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reload") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = {
                                controller.reload()
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy link") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = {
                                copyLink(context, tab.url)
                                onMessage("Link copied")
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                shareLink(context, tab.url)
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open in new tab") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = {
                                controller.openInNewTab(tab.url)
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Close tab") },
                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                            onClick = {
                                controller.closeTab(controller.activeIndex)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressPill(tab: BrowserTab, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (tab.secure) Icons.Filled.Lock else Icons.Filled.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                hostOf(tab.url),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CountBadge(count: Int, showWhenOne: Boolean = true, modifier: Modifier = Modifier) {
    val visible = count > 0 && (showWhenOne || count > 1)
    if (!visible) return
    Box(
        modifier
            .padding(top = 4.dp, end = 2.dp)
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99" else "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun BoxScope.MediaChip(count: Int, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Row(
                Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (count == 1) "1 video" else "$count videos",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun TabsOverlay(controller: BrowserController, onDismiss: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tabs",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    controller.openTab()
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
                TextButton(onClick = {
                    controller.closeAllTabs()
                    onDismiss()
                }) {
                    Text("Close all")
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(controller.tabs, key = { _, t -> t.id }) { index, item ->
                    SurfaceCard(onClick = {
                        controller.selectTab(index)
                        onDismiss()
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (index == controller.activeIndex) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier.size(38.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (item.secure) Icons.Filled.Lock else Icons.Filled.Language,
                                        contentDescription = null,
                                        tint = if (index == controller.activeIndex) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    hostOf(item.url),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { controller.closeTab(index) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Close tab",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyLink(context: Context, url: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("url", url))
}

private fun shareLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, "Share link"))
}
