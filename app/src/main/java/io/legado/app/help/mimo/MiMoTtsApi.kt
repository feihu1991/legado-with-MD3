package io.legado.app.help.mimo

import android.util.Log
import io.legado.app.data.entities.CharacterVoice
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * MiMo TTS API 客户端
 * 支持预置音色合成、自定义音色合成、角色分析
 */
object MiMoTtsApi {

    private const val TAG = "MiMoTtsApi"
    private const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 使用预置音色合成语音
     */
    suspend fun synthesizeWithPreset(
        text: String,
        voice: String = "mimo_default",
        style: String? = null,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): ByteArray = withContext(Dispatchers.IO) {
        val messages = buildMessages(text, voice, style)
        val body = JSONObject().apply {
            put("model", "mimo-v2.5-tts")
            put("messages", messages)
            put("stream", false)
        }
        callTtsApi(body, apiKey, baseUrl)
    }

    /**
     * 使用自定义描述设计音色合成语音
     */
    suspend fun synthesizeWithDesign(
        text: String,
        voiceDescription: String,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): ByteArray = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", voiceDescription)
            })
            put(JSONObject().apply {
                put("role", "assistant")
                put("content", text)
            })
        }
        val body = JSONObject().apply {
            put("model", "mimo-v2.5-tts-voicedesign")
            put("messages", messages)
            put("stream", false)
        }
        callTtsApi(body, apiKey, baseUrl)
    }

    /**
     * 根据角色配置合成语音
     */
    suspend fun synthesize(
        text: String,
        voiceConfig: CharacterVoice,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): ByteArray {
        return when (voiceConfig.voiceType) {
            CharacterVoice.VOICE_TYPE_DESIGNED -> {
                synthesizeWithDesign(
                    text = text,
                    voiceDescription = voiceConfig.voiceDescription ?: "中性自然的朗读声音",
                    apiKey = apiKey,
                    baseUrl = baseUrl
                )
            }
            else -> {
                synthesizeWithPreset(
                    text = text,
                    voice = voiceConfig.presetVoiceId,
                    style = voiceConfig.style,
                    apiKey = apiKey,
                    baseUrl = baseUrl
                )
            }
        }
    }

    /**
     * 使用 MiMo Chat API 分析书籍角色
     */
    suspend fun analyzeCharacters(
        bookText: String,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): List<CharacterInfo> = withContext(Dispatchers.IO) {
        val prompt = """你是一个专业的有声书导演。分析以下小说文本，提取所有说话角色。
对于每个角色，给出简短的音色描述建议。

请严格按照以下JSON格式输出，不要输出其他内容：
{
  "characters": [
    {
      "name": "角色名",
      "gender": "男/女/未知",
      "age": "少年/青年/中年/老年",
      "personality": "性格特征",
      "voice_hint": "音色建议，如：低沉磁性/清脆甜美/沙哑沧桑",
      "suggested_voice": "从以下选项中选择一个最匹配的：mimo_default/bingtang/moli/suda/baihua"
    }
  ]
}

---文本内容---
$bookText"""

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "mimo-v2.5-pro")
            put("messages", messages)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw NoStackTraceException("角色分析请求失败: ${response.code} ${response.message}")
        }

        val responseBody = response.body?.string()
            ?: throw NoStackTraceException("角色分析返回为空")

        val json = JSONObject(responseBody)
        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        parseCharacterInfo(content)
    }

    /**
     * 使用 MiMo Chat API 将文本分段标注角色
     */
    suspend fun segmentTextByCharacter(
        chapterText: String,
        characters: List<CharacterVoice>,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): List<TextSegment> = withContext(Dispatchers.IO) {
        val characterNames = characters.joinToString("、") { it.characterName }
        val prompt = """你是一个有声书制作助手。请分析以下文本，为每段标注应该由哪个角色朗读。

已知角色列表：$characterNames（其中"旁白"用于非对话内容）

请严格按照以下JSON数组格式输出，不要输出其他内容：
[
  {"text": "段落内容", "speaker": "角色名", "emotion": "情感标签(如:平静/愤怒/悲伤/喜悦)"},
  {"text": "段落内容", "speaker": "角色名", "emotion": "情感标签"}
]

注意：
- 对话内容（引号内的文字）标注为说话角色
- 非对话内容（叙述、描写）标注为"旁白"
- 保持原文段落结构，不要合并或拆分段落

---章节文本---
$chapterText"""

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", "mimo-v2.5-pro")
            put("messages", messages)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw NoStackTraceException("文本分段请求失败: ${response.code} ${response.message}")
        }

        val responseBody = response.body?.string()
            ?: throw NoStackTraceException("文本分段返回为空")

        val json = JSONObject(responseBody)
        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        parseTextSegments(content)
    }

    private fun buildMessages(text: String, voice: String, style: String?): JSONArray {
        return JSONArray().apply {
            if (style != null) {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", style)
                })
            }
            put(JSONObject().apply {
                put("role", "assistant")
                put("content", text)
            })
        }
    }

    private fun callTtsApi(body: JSONObject, apiKey: String, baseUrl: String): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw NoStackTraceException("TTS合成失败: ${response.code} $errorBody")
        }

        val contentType = response.header("Content-Type", "")
        val inputStream = response.body?.byteStream()
            ?: throw NoStackTraceException("TTS返回为空")

        return if (contentType?.contains("audio") == true) {
            // 直接返回音频数据
            readFully(inputStream)
        } else {
            // JSON 响应，提取音频数据
            val responseBody = inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val message = choices.getJSONObject(0).getJSONObject("message")
                // 尝试从 content 中获取 base64 音频
                val content = message.optString("content", "")
                if (content.startsWith("data:audio")) {
                    val base64 = content.substringAfter("base64,")
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                } else {
                    throw NoStackTraceException("TTS返回格式不支持: $responseBody")
                }
            } else {
                throw NoStackTraceException("TTS返回无结果")
            }
        }
    }

    private fun readFully(inputStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(data).also { bytesRead = it } != -1) {
            buffer.write(data, 0, bytesRead)
        }
        return buffer.toByteArray()
    }

    private fun parseCharacterInfo(content: String): List<CharacterInfo> {
        val result = mutableListOf<CharacterInfo>()
        try {
            // 提取 JSON 部分
            val jsonStr = extractJson(content)
            val json = JSONObject(jsonStr)
            val characters = json.getJSONArray("characters")
            for (i in 0 until characters.length()) {
                val char = characters.getJSONObject(i)
                result.add(
                    CharacterInfo(
                        name = char.optString("name", ""),
                        gender = char.optString("gender", "未知"),
                        age = char.optString("age", "青年"),
                        personality = char.optString("personality", ""),
                        voiceHint = char.optString("voice_hint", ""),
                        suggestedVoice = char.optString("suggested_voice", "mimo_default")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析角色信息失败: $content", e)
        }
        return result
    }

    private fun parseTextSegments(content: String): List<TextSegment> {
        val result = mutableListOf<TextSegment>()
        try {
            val jsonStr = extractJson(content)
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                result.add(
                    TextSegment(
                        text = item.optString("text", ""),
                        speaker = item.optString("speaker", "旁白"),
                        emotion = item.optString("emotion", "平静")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析文本分段失败: $content", e)
        }
        return result
    }

    private fun extractJson(content: String): String {
        // 尝试从 markdown code block 或纯文本中提取 JSON
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\}|\\[[\\s\\S]*?\\])\\s*\\n?```")
        val match = codeBlockPattern.find(content)
        if (match != null) {
            return match.groupValues[1]
        }
        // 直接尝试解析
        val trimmed = content.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        // 尝试找到第一个 { 或 [
        val startIdx = trimmed.indexOfFirst { it == '{' || it == '[' }
        if (startIdx >= 0) {
            return trimmed.substring(startIdx)
        }
        return trimmed
    }

    data class CharacterInfo(
        val name: String,
        val gender: String,
        val age: String,
        val personality: String,
        val voiceHint: String,
        val suggestedVoice: String
    )

    data class TextSegment(
        val text: String,
        val speaker: String,
        val emotion: String
    )
}
