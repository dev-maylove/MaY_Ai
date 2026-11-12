package id.mayai.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF090A12)
private val Panel = Color(0xFF151727)
private val PanelAlt = Color(0xFF1C1E32)
private val Violet = Color(0xFF9B72FF)
private val Cyan = Color(0xFF48DDF2)
private val TextMain = Color(0xFFF3F1FA)
private val TextMuted = Color(0xFF9A9BB0)
private val UserBubble = Color(0xFF292044)
private val AiBubble = Color(0xFF202235)
private val ErrorTint = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        val repository = ChatRepository(
            applicationContext,
            db.sessionDao(),
            db.messageDao()
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(repository) as T
            }
        }
        setContent {
            MaYAiTheme {
                val vm: ChatViewModel = viewModel(factory = factory)
                ChatScreen(vm)
            }
        }
    }
}

@Composable
private fun MaYAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Violet,
            secondary = Cyan,
            background = Bg,
            surface = Panel,
            onBackground = TextMain,
            onSurface = TextMain,
            error = ErrorTint
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsState(initial = emptyList())
    val sessions by vm.sessions.collectAsState(initial = emptyList())
    val busy by vm.busy.collectAsState()
    val endpoint by vm.endpoint.collectAsState()
    val systemPrompt by vm.systemPrompt.collectAsState()
    val connectionOk by vm.connectionOk.collectAsState()
    val streamingText by vm.streamingText.collectAsState()
    val statusText by vm.statusText.collectAsState()
    val activeSessionId by vm.activeSessionId.collectAsState()

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var urlDraft by remember(endpoint) { mutableStateOf(endpoint) }
    var promptDraft by remember(systemPrompt) { mutableStateOf(systemPrompt) }

    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(Unit) {
        vm.loadSettings()
        vm.checkConnection()
    }

    LaunchedEffect(messages.size, streamingText) {
        val total = messages.size + if (streamingText.isNotEmpty() && busy) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem((total - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) drawerOpen = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Panel,
                modifier = Modifier.width(300.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sesi chat", color = TextMain, fontSize = 18.sp)
                        IconButton(onClick = {
                            vm.createSession()
                            drawerOpen = false
                        }) {
                            Icon(Icons.Default.Add, "Sesi baru", tint = Cyan)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = PanelAlt)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            val selected = session.id == activeSessionId
                            Surface(
                                color = if (selected) PanelAlt else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.selectSession(session.id)
                                        drawerOpen = false
                                    }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            session.title,
                                            color = TextMain,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            formatTime(session.updatedAt),
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = { vm.deleteSession(session.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            "Hapus sesi",
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(16.dp)
                .imePadding()
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { drawerOpen = true }) {
                    Icon(Icons.Default.Menu, "Menu sesi", tint = Cyan)
                }
                Column(Modifier.weight(1f)) {
                    Text("MaY_Ai", color = TextMain, fontSize = 22.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ASISTEN AI LOKAL",
                            color = Cyan,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusDot(connectionOk)
                        if (busy) {
                            Spacer(Modifier.width(6.dp))
                            Text("streaming…", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(Icons.Default.Delete, "Hapus pesan sesi ini", tint = TextMuted)
                }
                IconButton(onClick = {
                    urlDraft = endpoint
                    promptDraft = systemPrompt
                    showSettings = true
                }) {
                    Icon(Icons.Default.Settings, "Pengaturan", tint = Cyan)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Chat area
            Surface(
                color = Panel,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val showEmpty = messages.isEmpty() && streamingText.isEmpty()
                if (showEmpty) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Halo, saya MaY_Ai.", color = TextMain, fontSize = 22.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tanyakan apa saja. Pastikan llama-server aktif.\nGeser dari kiri atau ketuk menu untuk ganti sesi.",
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            // Skip empty placeholder while streaming (we show streaming bubble)
                            if (msg.role == "assistant" && msg.content.isEmpty() && busy) {
                                // will be shown via streamingText
                            } else if (msg.content.isNotEmpty()) {
                                MessageBubble(msg)
                            }
                        }
                        if (busy && streamingText.isNotEmpty()) {
                            item(key = "streaming") {
                                MessageBubble(
                                    ChatMessage(
                                        id = -1,
                                        sessionId = activeSessionId ?: 0,
                                        role = "assistant",
                                        content = streamingText
                                    ),
                                    isStreaming = true
                                )
                            }
                        }
                    }
                }
            }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = Cyan,
                    trackColor = Panel
                )
            }

            Spacer(Modifier.height(10.dp))

            // Input
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(Panel, RoundedCornerShape(16.dp))
                        .padding(15.dp),
                    textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                    cursorBrush = SolidColor(Cyan),
                    maxLines = 5,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = input.trim()
                            if (text.isNotEmpty() && !busy) {
                                vm.send(text)
                                input = ""
                                keyboard?.hide()
                            }
                        }
                    ),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Tulis pesan...", color = TextMuted)
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty() && !busy) {
                            vm.send(text)
                            input = ""
                            keyboard?.hide()
                        }
                    },
                    enabled = !busy && input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Kirim")
                }
            }

            Text(
                "Server: $endpoint",
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Pengaturan") },
            text = {
                Column {
                    Text("Alamat llama-server", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        singleLine = true,
                        placeholder = { Text("http://127.0.0.1:8080") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("System prompt", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Prompt ini dikirim sebagai pesan system di setiap request.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.setEndpoint(urlDraft.trim())
                        vm.setSystemPrompt(promptDraft)
                        vm.checkConnection()
                        showSettings = false
                    }
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Batal") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Hapus pesan sesi ini?") },
            text = { Text("Semua pesan di sesi aktif akan dihapus. Sesi tetap ada.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearCurrentSession()
                    showClearConfirm = false
                }) { Text("Hapus", color = ErrorTint) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun StatusDot(ok: Boolean?) {
    val color = when (ok) {
        true -> Color(0xFF4ADE80)
        false -> ErrorTint
        null -> TextMuted
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(50))
    )
}

@Composable
private fun MessageBubble(msg: ChatMessage, isStreaming: Boolean = false) {
    val isUser = msg.role == "user"
    val isError = !isUser && msg.content.startsWith("Gagal menghubungi AI:")
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            when {
                isUser -> "KAMU"
                isStreaming -> "MAY_AI · …"
                else -> "MAY_AI"
            },
            color = when {
                isUser -> Cyan
                isError -> ErrorTint
                else -> Violet
            },
            fontSize = 10.sp
        )
        Surface(
            color = if (isUser) UserBubble else AiBubble,
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                msg.content + if (isStreaming) " ▍" else "",
                Modifier.padding(12.dp),
                color = if (isError) ErrorTint else TextMain,
                fontSize = 14.sp
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    return try {
        SimpleDateFormat("dd MMM HH:mm", Locale("id")).format(Date(ms))
    } catch (_: Exception) {
        ""
    }
}

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    val messages = repository.messages
    val sessions = repository.sessions
    val busy = repository.busy
    val endpoint = repository.endpoint
    val systemPrompt = repository.systemPrompt
    val connectionOk = repository.connectionOk
    val streamingText = repository.streamingText
    val statusText = repository.statusText
    val activeSessionId = repository.activeSessionId

    fun loadSettings() = viewModelScope.launch { repository.loadSettings() }
    fun checkConnection() = viewModelScope.launch { repository.checkConnection() }
    fun send(text: String) = viewModelScope.launch { repository.send(text) }
    fun setEndpoint(value: String) = viewModelScope.launch { repository.setEndpoint(value) }
    fun setSystemPrompt(value: String) = viewModelScope.launch { repository.setSystemPrompt(value) }
    fun clearCurrentSession() = viewModelScope.launch { repository.clearCurrentSession() }
    fun createSession() = viewModelScope.launch { repository.createSession() }
    fun selectSession(id: Long) = viewModelScope.launch { repository.selectSession(id) }
    fun deleteSession(id: Long) = viewModelScope.launch { repository.deleteSession(id) }
}
