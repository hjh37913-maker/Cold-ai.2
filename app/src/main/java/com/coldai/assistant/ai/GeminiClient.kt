package com.coldai.assistant.ai

import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Kind { NO_KEY, NETWORK, RATE, MODEL, KEY, EMPTY, OTHER, STT, PERM }

data class UiError(val kind: Kind, val detail: String = "")

class GeminiException(val kind: Kind, val detail: String = "") : Exception(detail)

object GeminiClient {
    /** history: список пар (роль "user"/"model", текст). Отмена корутины закрывает соединение. */
    suspend fun chat(key: String, model: String, system: String, history: List<Pair<String, String>>): String {
        if (key.isBlank()) throw GeminiException(Kind.NO_KEY)
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .toString()

        return suspendCancellableCoroutine<String> { cont ->
            val conn = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/${Uri.encode(model)}:generateContent"
            ).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { try { conn.disconnect() } catch (_: Exception) { } }
            thread {
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-goog-api-key", key)
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code in 200..299) {
                        val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                        if (sb.isBlank()) throw GeminiException(Kind.EMPTY)
                        if (cont.isActive) cont.resume(sb.toString().trim())
                    } else {
                        val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (e: Exception) { "" }
                        val kind = when {
                            code == 429 -> Kind.RATE
                            code == 404 -> Kind.MODEL
                            code == 401 || code == 403 -> Kind.KEY
                            code == 400 && msg.contains("API key", ignoreCase = true) -> Kind.KEY
                            else -> Kind.OTHER
                        }
                        throw GeminiException(kind, msg)
                    }
                } catch (e: Throwable) {
                    val ex = when (e) {
                        is GeminiException -> e
                        is IOException -> GeminiException(Kind.NETWORK, e.message ?: "")
                        else -> GeminiException(Kind.OTHER, e.message ?: "")
                    }
                    if (cont.isActive) cont.resumeWithException(ex)
                }
            }
        }
    }
}
