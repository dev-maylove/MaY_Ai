package id.mayai.assistant

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "may_ai_settings")

private val KEY_ENDPOINT = stringPreferencesKey("endpoint")
private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
private val KEY_ACTIVE_SESSION = longPreferencesKey("active_session_id")

private const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080"
private const val DEFAULT_SYSTEM_PROMPT =
    "Namamu MaY_Ai. Kamu adalah asisten AI yang ramah, teliti, dan praktis. " +
        "Jawab dalam bahasa Indonesia yang jelas dan ringkas. Jangan mengaku sebagai Qwen."

/**
 * Error eksplisit dari server di dalam stream SSE
 * (event: error, atau payload {"error": ...}).
 * Tidak perlu di-fallback ke non-stream — penyebabnya biasanya sama.
 */
class SseServerError(message: String) : IOException(message)

class ChatApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildMessagesJson(systemPrompt: String, history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT })
        )
        history.takeLast(24).forEach { msg ->
            if (msg.role == "user" || msg.role == "assistant") {
                messages.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
        }
        return messages
    }

    /**
     * Coba streaming dulu; jika gagal (server tidak support SSE / respons kosong /
     * HTTP error yang mengindikasikan non-stream only), otomatis fallback ke non-stream.
     * [onDelta] hanya dipanggil saat mode streaming berhasil mengirim token.
     * [onFallback] dipanggil sekali saat beralih ke non-stream (opsional untuk UI).
     */
    companion object {
        /** Jumlah percobaan total (1 awal + retry). */
        const val MAX_ATTEMPTS = 3
        /** Delay dasar backoff (ms); attempt ke-n menunggu base * 2^(n-1). */
        const val RETRY_BASE_DELAY_MS = 800L
    }

    /**
     * Satu putaran: stream → (opsional) fallback non-stream.
     * Dipakai oleh [completeWithRetry].
     */
    private suspend fun completeWithFallback(
        baseUrl: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit = {},
        onFallback: () -> Unit = {}
    ): String {
        var tokensReceived = 0
        val trackingDelta: (String) -> Unit = { piece ->
            if (piece.isNotEmpty()) tokensReceived += piece.length
            onDelta(piece)
        }
        return try {
            completeStreaming(baseUrl, systemPrompt, history, trackingDelta)
        } catch (streamError: Exception) {
            if (streamError is SseServerError) throw streamError
            // Partial stream: jangan fallback agar teks yang sudah muncul tidak rusak
            if (tokensReceived > 0) throw streamError
            if (!isLikelyStreamUnsupported(streamError)) throw streamError

            onFallback()
            try {
                completeNonStreaming(baseUrl, systemPrompt, history).also { full ->
                    if (full.isNotEmpty()) onDelta(full)
                }
            } catch (nonStreamError: Exception) {
                val streamMsg = streamError.message?.take(120) ?: streamError.javaClass.simpleName
                val nonMsg = nonStreamError.message?.take(200) ?: "kesalahan tidak diketahui"
                throw IOException("Stream gagal ($streamMsg); non-stream juga gagal: $nonMsg")
            }
        }
    }

    /**
     * Retry otomatis dengan exponential backoff untuk error sementara.
     * Tidak mengulang: [SseServerError], HTTP 4xx (kecuali 408/429), partial stream.
     *
     * [onRetry] dipanggil sebelum jeda, dengan (attemptIndex 1-based, delayMs, error).
     * [onDelta] dari percobaan gagal sebelumnya tidak di-reset di sini —
     * pemanggil (Repository) yang membersihkan bubble jika perlu.
     */
    suspend fun completeWithRetry(
        baseUrl: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit = {},
        onFallback: () -> Unit = {},
        onRetry: (attempt: Int, delayMs: Long, error: Exception) -> Unit = { _, _, _ -> }
    ): String {
        var lastError: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return completeWithFallback(
                    baseUrl = baseUrl,
                    systemPrompt = systemPrompt,
                    history = history,
                    onDelta = onDelta,
                    onFallback = onFallback
                )
            } catch (e: Exception) {
                lastError = e
                val retryable = isRetryable(e)
                val hasMore = attempt < MAX_ATTEMPTS
                if (!retryable || !hasMore) throw e
                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)) // 800, 1600, 3200...
                onRetry(attempt, delayMs, e)
                delay(delayMs)
            }
        }
        throw lastError ?: IOException("Gagal setelah $MAX_ATTEMPTS percobaan")
    }

    /** Error yang layak di-retry (sementara / jaringan / overload). */
    private fun isRetryable(error: Exception): Boolean {
        if (error is SseServerError) return false
        val msg = (error.message ?: "").lowercase()
        // Partial / logika klien — jangan ulang
        if (msg.contains("respons kosong") && !msg.contains("stream")) return false
        return when {
            msg.contains("timeout") -> true
            msg.contains("timed out") -> true
            msg.contains("failed to connect") -> true
            msg.contains("connection refused") -> true
            msg.contains("connection reset") -> true
            msg.contains("broken pipe") -> true
            msg.contains("unreachable") -> true
            msg.contains("network is unreachable") -> true
            msg.contains("software caused connection abort") -> true
            msg.contains("unexpected end of stream") -> true
            msg.contains("http 408") -> true // request timeout
            msg.contains("http 429") -> true // rate limit
            msg.contains("http 502") -> true
            msg.contains("http 503") -> true
            msg.contains("http 504") -> true
            // 4xx lain: salah request / auth — jangan retry
            msg.contains("http 400") -> false
            msg.contains("http 401") -> false
            msg.contains("http 403") -> false
            msg.contains("http 404") -> false
            msg.contains("http 422") -> false
            // Stream kosong kadang transient di server lokal yang masih load model
            msg.contains("respons stream kosong") -> true
            else -> {
                // IOException generik tanpa HTTP code → anggap transient
                error is IOException && !msg.contains("http 4")
            }
        }
    }

    /** Heuristik: error yang mengisyaratkan server tidak (benar) support SSE. */
    private fun isLikelyStreamUnsupported(error: Exception): Boolean {
        val msg = (error.message ?: "").lowercase()
        return when {
            error is SseServerError -> false
            msg.contains("respons stream kosong") -> true
            msg.contains("json non-stream") -> true
            msg.contains("bukan sse") -> true
            msg.contains("unexpected end") -> true
            msg.contains("malformed") -> true
            msg.contains("http 400") -> true
            msg.contains("http 415") -> true
            msg.contains("http 502") -> false
            msg.contains("http 503") -> false
            msg.contains("http 404") -> false
            msg.contains("failed to connect") -> false
            msg.contains("connection refused") -> false
            msg.contains("timed out") -> false
            msg.contains("timeout") -> false
            msg.contains("unreachable") -> false
            else -> true
        }
    }

    suspend fun completeStreaming(
        baseUrl: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", "local-model")
            .put("messages", buildMessagesJson(systemPrompt, history))
            .put("temperature", 0.7)
            .put("stream", true)

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty().take(300)
                throw IOException("HTTP ${response.code}: $err")
            }
            val contentType = response.header("Content-Type").orEmpty()
            val source = response.body?.source()
                ?: throw IOException("Body kosong dari server")

            // Beberapa server mengabaikan stream=true dan mengembalikan JSON biasa
            if (contentType.contains("application/json", ignoreCase = true) &&
                !contentType.contains("event-stream", ignoreCase = true)
            ) {
                val raw = source.readUtf8()
                val content = extractNonStreamContent(raw)
                if (content.isNotEmpty()) {
                    onDelta(content)
                    return@withContext content
                }
                throw IOException("Server mengembalikan JSON non-stream tanpa content")
            }

            parseSse(source, onDelta)
        }
    }

    suspend fun completeNonStreaming(
        baseUrl: String,
        systemPrompt: String,
        history: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", "local-model")
            .put("messages", buildMessagesJson(systemPrompt, history))
            .put("temperature", 0.7)
            .put("stream", false)

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${responseText.take(300)}")
            }
            val content = extractNonStreamContent(responseText)
            if (content.isEmpty()) throw IOException("Respons kosong dari server")
            content
        }
    }

    private fun extractNonStreamContent(responseText: String): String {
        return try {
            val root = JSONObject(responseText)
            // Error object di respons non-stream
            extractErrorMessage(root)?.let { throw SseServerError(it) }
            root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
                .trim()
        } catch (e: SseServerError) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Ambil pesan error dari berbagai bentuk payload OpenAI-compatible:
     * - {"error": "teks"}
     * - {"error": {"message": "...", "type": "...", "code": "..."}}
     * - {"message": "..."} di root saat gagal
     */
    private fun extractErrorMessage(root: JSONObject): String? {
        if (root.has("error")) {
            val err = root.opt("error")
            when (err) {
                is String -> if (err.isNotBlank()) return err.trim()
                is JSONObject -> {
                    val msg = err.optString("message")
                        .ifBlank { err.optString("msg") }
                        .ifBlank { err.optString("detail") }
                        .ifBlank { err.toString() }
                    val type = err.optString("type").ifBlank { err.optString("code") }
                    return if (type.isNotBlank() && msg.isNotBlank()) {
                        "$type: $msg"
                    } else {
                        msg.ifBlank { null }
                    }
                }
            }
        }
        // Beberapa server: {"choices":[], "message":"error..."}
        if (!root.has("choices") || root.optJSONArray("choices")?.length() == 0) {
            val msg = root.optString("message").ifBlank { root.optString("msg") }
            if (msg.isNotBlank() && !root.has("id")) return msg
        }
        return null
    }

    private fun parseSse(source: BufferedSource, onDelta: (String) -> Unit): String {
        val full = StringBuilder()
        var sawDataLine = false
        var currentEvent = "" // dari baris "event: ..."
        val dataBuffer = StringBuilder() // multi-line data: field (SSE spec)

        fun flushDataEvent() {
            val data = dataBuffer.toString().trim()
            dataBuffer.clear()
            val eventName = currentEvent
            currentEvent = ""
            if (data.isEmpty()) return
            if (data == "[DONE]") return // signal selesai ditangani di caller

            // event: error + data payload
            if (eventName.equals("error", ignoreCase = true)) {
                val msg = try {
                    val root = JSONObject(data)
                    extractErrorMessage(root) ?: root.optString("message").ifBlank { data }
                } catch (_: Exception) {
                    data
                }
                throw SseServerError(msg.take(400))
            }

            try {
                val root = JSONObject(data)
                // data: {"error": ...} tanpa event: error
                extractErrorMessage(root)?.let { throw SseServerError(it.take(400)) }

                val choices = root.optJSONArray("choices") ?: return
                if (choices.length() == 0) {
                    // choices kosong + mungkin error tersembunyi sudah dicek di atas
                    return
                }
                val choice = choices.getJSONObject(0)
                // finish_reason = "error" / content_filter
                val finish = choice.optString("finish_reason")
                if (finish.equals("error", ignoreCase = true) ||
                    finish.equals("content_filter", ignoreCase = true)
                ) {
                    val detail = choice.optJSONObject("delta")?.optString("content")
                        ?: choice.optString("text")
                    throw SseServerError(
                        "finish_reason=$finish" + if (detail.isNullOrBlank()) "" else ": $detail"
                    )
                }
                val delta = choice.optJSONObject("delta")
                val piece = when {
                    delta != null -> delta.optString("content", "")
                    else -> choice.optJSONObject("message")?.optString("content", "") ?: ""
                }
                if (piece.isNotEmpty()) {
                    full.append(piece)
                    onDelta(piece)
                }
            } catch (e: SseServerError) {
                throw e
            } catch (_: Exception) {
                // skip malformed chunk
            }
        }

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break

            // Baris kosong = akhir satu event (SSE spec)
            if (line.isEmpty()) {
                if (dataBuffer.isNotEmpty()) {
                    val peek = dataBuffer.toString().trim()
                    if (peek == "[DONE]") break
                    flushDataEvent()
                }
                continue
            }

            // Comment / heartbeat
            if (line.startsWith(":")) continue

            // event: <name>
            if (line.startsWith("event:")) {
                currentEvent = line.substringAfter("event:").trim()
                continue
            }

            // id: / retry: — abaikan
            if (line.startsWith("id:") || line.startsWith("retry:")) continue

            // data: <payload> (boleh multi-line)
            if (line.startsWith("data:") || line.startsWith("data: ")) {
                sawDataLine = true
                val payload = line.substringAfter("data:").let {
                    // Spasi pertama setelah colon opsional menurut spec
                    if (it.startsWith(" ")) it.substring(1) else it
                }
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(payload)
                // Banyak server OpenAI mengirim satu data: per event tanpa mengandalkan
                // blank line secara ketat; flush segera jika JSON lengkap / [DONE]
                val soFar = dataBuffer.toString().trim()
                if (soFar == "[DONE]") {
                    dataBuffer.clear()
                    break
                }
                if (soFar.startsWith("{") && soFar.endsWith("}")) {
                    flushDataEvent()
                }
                continue
            }

            // Body ternyata JSON non-SSE (server mengabaikan stream)
            if (!sawDataLine && line.trimStart().startsWith("{")) {
                val rest = buildString {
                    append(line)
                    while (!source.exhausted()) {
                        val n = source.readUtf8Line() ?: break
                        append(n)
                    }
                }
                // Bisa error object atau content
                try {
                    val root = JSONObject(rest)
                    extractErrorMessage(root)?.let { throw SseServerError(it.take(400)) }
                } catch (e: SseServerError) {
                    throw e
                } catch (_: Exception) { /* fall through */ }
                val content = extractNonStreamContent(rest)
                if (content.isNotEmpty()) {
                    onDelta(content)
                    return content
                }
                throw IOException("Body JSON bukan SSE dan tanpa content")
            }
        }

        // Flush sisa buffer (event terakhir tanpa blank line)
        if (dataBuffer.isNotEmpty()) {
            val peek = dataBuffer.toString().trim()
            if (peek != "[DONE]") flushDataEvent()
        }

        val result = full.toString().trim()
        if (result.isEmpty()) throw IOException("Respons stream kosong dari server")
        return result
    }

    suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.trimEnd('/') + "/v1/models"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}

class ChatRepository(
    private val context: Context,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val api: ChatApi = ChatApi()
) {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _endpoint = MutableStateFlow(DEFAULT_ENDPOINT)
    val endpoint = _endpoint.asStateFlow()

    private val _systemPrompt = MutableStateFlow(DEFAULT_SYSTEM_PROMPT)
    val systemPrompt = _systemPrompt.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId = _activeSessionId.asStateFlow()

    private val _connectionOk = MutableStateFlow<Boolean?>(null)
    val connectionOk = _connectionOk.asStateFlow()

    /** Teks yang sedang di-stream (untuk bubble sementara). */
    private val _streamingText = MutableStateFlow("")
    val streamingText = _streamingText.asStateFlow()

    /** Status singkat (retry, fallback) untuk UI. */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText = _statusText.asStateFlow()

    val sessions: Flow<List<ChatSession>> = sessionDao.observeAll()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<List<ChatMessage>> = _activeSessionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else messageDao.observeBySession(id)
    }

    suspend fun loadSettings() {
        val prefs = context.dataStore.data.first()
        _endpoint.value = prefs[KEY_ENDPOINT] ?: DEFAULT_ENDPOINT
        _systemPrompt.value = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
        val savedSession = prefs[KEY_ACTIVE_SESSION]
        if (savedSession != null && sessionDao.getById(savedSession) != null) {
            _activeSessionId.value = savedSession
        } else {
            ensureActiveSession()
        }
    }

    private suspend fun ensureActiveSession() {
        val existing = sessionDao.observeAll().first()
        if (existing.isNotEmpty()) {
            _activeSessionId.value = existing.first().id
            persistActiveSession(existing.first().id)
        } else {
            createSession("Obrolan baru")
        }
    }

    private suspend fun persistActiveSession(id: Long) {
        context.dataStore.edit { it[KEY_ACTIVE_SESSION] = id }
    }

    suspend fun setEndpoint(value: String) {
        val cleaned = value.trim().trimEnd('/')
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            _endpoint.value = cleaned
            context.dataStore.edit { it[KEY_ENDPOINT] = cleaned }
        }
    }

    suspend fun setSystemPrompt(value: String) {
        val cleaned = value.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
        _systemPrompt.value = cleaned
        context.dataStore.edit { it[KEY_SYSTEM_PROMPT] = cleaned }
    }

    suspend fun checkConnection() {
        _connectionOk.value = api.ping(_endpoint.value)
    }

    suspend fun createSession(title: String = "Obrolan baru"): Long {
        val id = sessionDao.insert(ChatSession(title = title))
        _activeSessionId.value = id
        persistActiveSession(id)
        return id
    }

    suspend fun selectSession(id: Long) {
        if (sessionDao.getById(id) != null) {
            _activeSessionId.value = id
            persistActiveSession(id)
        }
    }

    suspend fun deleteSession(id: Long) {
        sessionDao.delete(id)
        if (_activeSessionId.value == id) {
            val remaining = sessionDao.observeAll().first()
            if (remaining.isNotEmpty()) {
                selectSession(remaining.first().id)
            } else {
                createSession("Obrolan baru")
            }
        }
    }

    suspend fun renameSession(id: Long, title: String) {
        val t = title.trim().ifBlank { "Obrolan" }
        sessionDao.updateTitle(id, t)
    }

    suspend fun clearCurrentSession() {
        val sid = _activeSessionId.value ?: return
        messageDao.clearSession(sid)
    }

    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        var sid = _activeSessionId.value
        if (sid == null) {
            sid = createSession(trimmed.take(40))
        }

        _busy.value = true
        _streamingText.value = ""
        _statusText.value = null
        var assistantMsgId: Long? = null
        try {
            messageDao.insert(ChatMessage(sessionId = sid, role = "user", content = trimmed))
            // Auto-title dari pesan pertama jika masih default
            val session = sessionDao.getById(sid)
            if (session != null && (session.title == "Obrolan baru" || session.title.isBlank())) {
                sessionDao.updateTitle(sid, trimmed.take(40))
            }
            sessionDao.touch(sid)

            val history = messageDao.getBySession(sid)
            // Placeholder assistant message yang di-update saat stream
            assistantMsgId = messageDao.insert(
                ChatMessage(sessionId = sid, role = "assistant", content = "")
            )

            val answer = api.completeWithRetry(
                baseUrl = _endpoint.value,
                systemPrompt = _systemPrompt.value,
                history = history,
                onDelta = { piece ->
                    _streamingText.value = _streamingText.value + piece
                },
                onFallback = {
                    _statusText.value = "Beralih ke mode non-stream…"
                },
                onRetry = { attempt, delayMs, _ ->
                    _streamingText.value = ""
                    _statusText.value =
                        "Mencoba lagi ($attempt/${ChatApi.MAX_ATTEMPTS - 1})… ${delayMs}ms"
                }
            )

            messageDao.updateContent(assistantMsgId, answer)
            _streamingText.value = ""
            _statusText.value = null
            _connectionOk.value = true
            sessionDao.touch(sid)
        } catch (e: Exception) {
            val reason = e.message?.take(200) ?: "kesalahan tidak diketahui"
            val errText =
                "Gagal menghubungi AI: $reason\n\nPastikan llama-server berjalan dan URL benar."
            if (assistantMsgId != null) {
                messageDao.updateContent(assistantMsgId, errText)
            } else {
                messageDao.insert(
                    ChatMessage(sessionId = sid, role = "assistant", content = errText)
                )
            }
            _streamingText.value = ""
            _statusText.value = null
            _connectionOk.value = false
        } finally {
            _busy.value = false
        }
    }
}
