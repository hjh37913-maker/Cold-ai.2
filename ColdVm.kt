package com.coldai.assistant.ui

import android.app.Application
import android.content.Context
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coldai.assistant.R
import com.coldai.assistant.ai.GeminiClient
import com.coldai.assistant.ai.GeminiException
import com.coldai.assistant.ai.Kind
import com.coldai.assistant.ai.UiError
import com.coldai.assistant.cmd.CommandRouter
import com.coldai.assistant.cmd.Confirm
import com.coldai.assistant.cmd.PassToAi
import com.coldai.assistant.cmd.Remember
import com.coldai.assistant.cmd.Reply
import com.coldai.assistant.data.AppDb
import com.coldai.assistant.data.Chat
import com.coldai.assistant.data.Memory
import com.coldai.assistant.data.Msg
import com.coldai.assistant.data.Prefs
import com.coldai.assistant.voice.VoiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Status { IDLE, LISTENING, THINKING, SPEAKING }

@OptIn(ExperimentalCoroutinesApi::class)
class ColdVm(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    val prefs = Prefs(app)
    val voice = VoiceManager(app)
    val settings = prefs.state

    val chatId = MutableStateFlow<Long?>(null)
    private val temp = MutableStateFlow<List<Msg>>(emptyList()) // сообщения, когда история отключена
    val status = MutableStateFlow(Status.IDLE)
    val partial = MutableStateFlow("")
    val error = MutableStateFlow<UiError?>(null)
    val pending = MutableStateFlow<Confirm?>(null)

    val chats = dao.chats().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memories = dao.memories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messages: StateFlow<List<Msg>> =
        combine(settings.map { it.saveHistory }.distinctUntilChanged(), chatId) { s, id -> Pair(s, id) }
            .flatMapLatest { (s, id) ->
                when {
                    !s -> temp
                    id == null -> flowOf(emptyList<Msg>())
                    else -> dao.msgs(id)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    private suspend fun addMsg(role: String, text: String) {
        val now = System.currentTimeMillis()
        if (!settings.value.saveHistory) {
            temp.value = temp.value + Msg(id = System.nanoTime(), chatId = 0, role = role, text = text, ts = now)
            return
        }
        val id = chatId.value ?: dao.insertChat(Chat(title = text.take(40), updated = now)).also { chatId.value = it }
        dao.insertMsg(Msg(chatId = id, role = role, text = text, ts = now))
        dao.touch(id, now)
    }

    private suspend fun reply(ctx: Context, text: String) {
        addMsg("assistant", text)
        status.value = Status.IDLE
        val s = settings.value
        if (s.autoSpeak) {
            status.value = Status.SPEAKING
            voice.speak(text, s.lang, s.rate, s.pitch, s.voice) { status.value = Status.IDLE }
        }
    }

    fun send(ctx: Context, text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        job?.cancel()
        voice.stopSpeaking()
        job = viewModelScope.launch {
            error.value = null
            partial.value = ""
            try {
                addMsg("user", q)
                when (val r = CommandRouter.handle(ctx, q)) {
                    is Reply -> reply(ctx, r.text)
                    is Remember -> {
                        dao.insertMemory(Memory(text = r.fact, ts = System.currentTimeMillis()))
                        reply(ctx, ctx.getString(R.string.remembered))
                    }
                    is Confirm -> { pending.value = r; status.value = Status.IDLE }
                    PassToAi -> ask(ctx)
                }
            } catch (e: CancellationException) {
                status.value = Status.IDLE
                throw e
            } catch (e: GeminiException) {
                error.value = UiError(e.kind, e.detail); status.value = Status.IDLE
            } catch (e: Exception) {
                error.value = UiError(Kind.OTHER, e.message ?: ""); status.value = Status.IDLE
            }
        }
    }

    private suspend fun ask(ctx: Context) {
        val s = settings.value
        status.value = Status.THINKING
        val hist: List<Msg> = if (s.saveHistory) dao.msgsOnce(chatId.value ?: return) else temp.value
        val mem = if (s.useMemory) dao.memoriesOnce().map { it.text } else emptyList()
        val sys = ctx.getString(R.string.system_prompt) +
            if (mem.isEmpty()) "" else "\n\n" + ctx.getString(R.string.memory_header) + "\n" + mem.joinToString("\n") { "- $it" }
        val h = hist.takeLast(24)
            .map { (if (it.role == "user") "user" else "model") to it.text }
            .dropWhile { it.first == "model" }
        reply(ctx, GeminiClient.chat(s.apiKey, s.model, sys, h))
    }

    /** Пользователь подтвердил действие и разрешения получены. */
    fun resolvePending(ctx: Context) {
        val p = pending.value ?: return
        pending.value = null
        viewModelScope.launch {
            val text = try { p.run() } catch (e: Exception) { ctx.getString(R.string.err_generic) + ": " + (e.message ?: "") }
            reply(ctx, text)
        }
    }

    fun cancelPending(ctx: Context, denied: Boolean) {
        if (pending.value == null) return
        pending.value = null
        viewModelScope.launch { reply(ctx, ctx.getString(if (denied) R.string.no_perm else R.string.cancelled)) }
    }

    fun toggleMic(ctx: Context) {
        if (status.value == Status.LISTENING) { voice.finishListening(); return }
        job?.cancel()
        voice.stopSpeaking()
        error.value = null
        partial.value = ""
        status.value = Status.LISTENING
        voice.listen(
            settings.value.lang,
            { partial.value = it },
            { text -> partial.value = ""; status.value = Status.IDLE; send(ctx, text) },
            { code ->
                partial.value = ""
                status.value = Status.IDLE
                if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    error.value = UiError(Kind.STT, code.toString())
            }
        )
    }

    fun cancel() {
        job?.cancel()
        voice.stopSpeaking()
        voice.cancelListening()
        partial.value = ""
        status.value = Status.IDLE
    }

    fun testVoice(ctx: Context) {
        val s = settings.value
        voice.speak(ctx.getString(R.string.voice_test), s.lang, s.rate, s.pitch, s.voice) {}
    }

    fun newChat() { cancel(); chatId.value = null; temp.value = emptyList() }
    fun openChat(id: Long) { cancel(); chatId.value = id }
    fun deleteChat(id: Long) = viewModelScope.launch {
        dao.deleteMsgs(id); dao.deleteChat(id)
        if (chatId.value == id) chatId.value = null
    }
    fun clearHistory() = viewModelScope.launch {
        cancel(); dao.clearMsgs(); dao.clearChats(); chatId.value = null; temp.value = emptyList()
    }

    fun addMemory(text: String) = viewModelScope.launch {
        if (text.isNotBlank()) dao.insertMemory(Memory(text = text.trim(), ts = System.currentTimeMillis()))
    }
    fun deleteMemory(id: Long) = viewModelScope.launch { dao.deleteMemory(id) }
    fun clearMemories() = viewModelScope.launch { dao.clearMemories() }

    override fun onCleared() { voice.release() }
}
