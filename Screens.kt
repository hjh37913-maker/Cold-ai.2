@file:OptIn(ExperimentalMaterial3Api::class)

package com.coldai.assistant.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coldai.assistant.R
import com.coldai.assistant.ai.Kind
import com.coldai.assistant.ai.UiError
import com.coldai.assistant.cmd.Confirm
import kotlinx.coroutines.launch

enum class Screen { CHAT, MEMORY, SETTINGS }

@Composable
fun ColdApp(vm: ColdVm, onMic: () -> Unit, onConfirm: (Confirm) -> Unit, onLang: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    ColdTheme(settings.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var screen by remember { mutableStateOf(Screen.CHAT) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                if (wide) {
                    PermanentNavigationDrawer(drawerContent = {
                        PermanentDrawerSheet(Modifier.width(300.dp)) { Sidebar(vm, { screen = it }) {} }
                    }) { ChatOrOther(screen, vm, null, onMic, onConfirm, onLang) { screen = Screen.CHAT } }
                } else {
                    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                        ModalDrawerSheet { Sidebar(vm, { screen = it }) { scope.launch { drawer.close() } } }
                    }) { ChatOrOther(screen, vm, { scope.launch { drawer.open() } }, onMic, onConfirm, onLang) { screen = Screen.CHAT } }
                }
            }
        }
    }
}

@Composable
private fun ChatOrOther(
    screen: Screen, vm: ColdVm, onMenu: (() -> Unit)?, onMic: () -> Unit,
    onConfirm: (Confirm) -> Unit, onLang: (String) -> Unit, onBack: () -> Unit
) {
    when (screen) {
        Screen.CHAT -> ChatScreen(vm, onMenu, onMic, onConfirm)
        Screen.MEMORY -> MemoryScreen(vm, onBack)
        Screen.SETTINGS -> SettingsScreen(vm, onLang, onBack)
    }
}

@Composable
private fun Sidebar(vm: ColdVm, onNav: (Screen) -> Unit, afterPick: () -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxHeight().padding(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp))
        Button({ vm.newChat(); onNav(Screen.CHAT); afterPick() }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.new_chat))
        }
        Text(stringResource(R.string.history), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(chats, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openChat(c.id); onNav(Screen.CHAT); afterPick() }.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ vm.deleteChat(c.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
            }
        }
        TextButton({ onNav(Screen.MEMORY); afterPick() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.memory)) }
        TextButton({ onNav(Screen.SETTINGS); afterPick() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings)) }
    }
}

@Composable
private fun errText(e: UiError): String = when (e.kind) {
    Kind.NO_KEY -> stringResource(R.string.err_no_key)
    Kind.NETWORK -> stringResource(R.string.err_network)
    Kind.RATE -> stringResource(R.string.err_rate)
    Kind.MODEL -> stringResource(R.string.err_model)
    Kind.KEY -> stringResource(R.string.err_key)
    Kind.EMPTY -> stringResource(R.string.err_empty)
    Kind.STT -> stringResource(R.string.err_stt, e.detail)
    Kind.PERM -> stringResource(R.string.err_perm)
    Kind.OTHER -> stringResource(R.string.err_other, e.detail)
}

@Composable
private fun ChatScreen(vm: ColdVm, onMenu: (() -> Unit)?, onMic: () -> Unit, onConfirm: (Confirm) -> Unit) {
    val ctx = LocalContext.current
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    val busy = status == Status.THINKING || status == Status.SPEAKING

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.app_name))
                    Text(
                        stringResource(
                            when (status) {
                                Status.IDLE -> R.string.status_ready
                                Status.LISTENING -> R.string.status_listening
                                Status.THINKING -> R.string.status_thinking
                                Status.SPEAKING -> R.string.status_speaking
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            navigationIcon = {
                if (onMenu != null) IconButton(onMenu) { Icon(Icons.Default.Menu, stringResource(R.string.menu)) }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (msgs.isEmpty() && partial.isEmpty()) {
                    Text(
                        stringResource(R.string.welcome), Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    items(msgs, key = { it.id }) { m -> Bubble(m.text, m.role == "user") }
                    if (partial.isNotEmpty()) item { Bubble(partial, true) }
                }
            }
            if (status == Status.THINKING) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(errText(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input, { input = it }, Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.hint)) },
                    maxLines = 4, shape = RoundedCornerShape(24.dp)
                )
                IconButton(onMic) {
                    Icon(
                        painterResource(R.drawable.ic_mic), stringResource(R.string.mic),
                        tint = if (status == Status.LISTENING) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
                if (busy) {
                    IconButton({ vm.cancel() }) { Icon(Icons.Default.Close, stringResource(R.string.stop)) }
                } else {
                    IconButton({ vm.send(ctx, input); input = "" }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                    }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.cancelPending(ctx, false) },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(p.prompt) },
            confirmButton = { TextButton({ onConfirm(p) }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton({ vm.cancelPending(ctx, false) }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun Bubble(text: String, user: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 560.dp)
            ) {
                SelectionContainer { Text(text, Modifier.padding(12.dp)) }
            }
        }
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
        }
    }
}

@Composable
private fun MemoryScreen(vm: ColdVm, onBack: () -> Unit) {
    val mem by vm.memories.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Page(stringResource(R.string.memory), onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.memory_new)) })
            Spacer(Modifier.width(8.dp))
            Button({ vm.addMemory(text); text = "" }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) }
        }
        Spacer(Modifier.height(12.dp))
        if (mem.isEmpty()) Text(stringResource(R.string.memory_empty))
        mem.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.text, Modifier.weight(1f))
                IconButton({ vm.deleteMemory(m.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
            HorizontalDivider()
        }
        if (mem.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ vm.clearMemories() }) { Text(stringResource(R.string.clear_all)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingsScreen(vm: ColdVm, onLang: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val ready by vm.voice.ttsReady.collectAsStateWithLifecycle()
    var key by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var model by remember(s.model) { mutableStateOf(s.model) }
    var menu by remember { mutableStateOf(false) }
    val voices = remember(s.lang, ready) { vm.voice.voices(s.lang) }

    Page(stringResource(R.string.settings), onBack) {
        Section(stringResource(R.string.s_language))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.lang == "ru", { onLang("ru") }, { Text("Русский") })
            FilterChip(s.lang == "uk", { onLang("uk") }, { Text("Українська") })
        }

        Section(stringResource(R.string.s_theme))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.theme == "system", { vm.prefs.update { a -> a.copy(theme = "system") } }, { Text(stringResource(R.string.theme_system)) })
            FilterChip(s.theme == "light", { vm.prefs.update { a -> a.copy(theme = "light") } }, { Text(stringResource(R.string.theme_light)) })
            FilterChip(s.theme == "dark", { vm.prefs.update { a -> a.copy(theme = "dark") } }, { Text(stringResource(R.string.theme_dark)) })
        }

        Section(stringResource(R.string.s_gemini))
        OutlinedTextField(
            key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.api_key)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.model)) }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Button({ vm.prefs.update { a -> a.copy(apiKey = key.trim(), model = model.trim()) } }) { Text(stringResource(R.string.save)) }
        Text(stringResource(R.string.api_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))

        Section(stringResource(R.string.s_voice))
        SwitchRow(stringResource(R.string.auto_speak), s.autoSpeak) { v -> vm.prefs.update { a -> a.copy(autoSpeak = v) } }
        Text(stringResource(R.string.rate))
        Slider(s.rate, { v -> vm.prefs.update { a -> a.copy(rate = v) } }, valueRange = 0.5f..2f)
        Text(stringResource(R.string.pitch))
        Slider(s.pitch, { v -> vm.prefs.update { a -> a.copy(pitch = v) } }, valueRange = 0.5f..2f)
        Box {
            OutlinedButton({ menu = true }) { Text(s.voice.ifEmpty { stringResource(R.string.voice_default) }) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.voice_default)) }, { vm.prefs.update { a -> a.copy(voice = "") }; menu = false })
                voices.forEach { v ->
                    DropdownMenuItem({ Text(v.name) }, { vm.prefs.update { a -> a.copy(voice = v.name) }; menu = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button({ vm.testVoice(ctx) }) { Text(stringResource(R.string.voice_test_btn)) }

        Section(stringResource(R.string.s_memory))
        SwitchRow(stringResource(R.string.save_history), s.saveHistory) { v -> vm.prefs.update { a -> a.copy(saveHistory = v) } }
        SwitchRow(stringResource(R.string.use_memory), s.useMemory) { v -> vm.prefs.update { a -> a.copy(useMemory = v) } }
        Spacer(Modifier.height(8.dp))
        OutlinedButton({ vm.clearHistory() }) { Text(stringResource(R.string.clear_history)) }

        Section(stringResource(R.string.s_wake))
        Text(stringResource(R.string.wake_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.s_about), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
    }
}
