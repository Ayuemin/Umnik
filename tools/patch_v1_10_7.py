from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# Version
path = "app/build.gradle.kts"
s = read(path)
s = replace_once(s, "// Umnik v1.10.6", "// Umnik v1.10.7", "version comment")
s = replace_once(s, "versionCode = 106", "versionCode = 107", "versionCode")
s = replace_once(s, 'versionName = "1.10.6"', 'versionName = "1.10.7"', "versionName")
write(path, s)

# Media settings: response format is optional. Null/blank means Auto.
path = "app/src/main/java/com/ayuemin/ymnik/model/OpenRouterAdvancedSettings.kt"
s = read(path)
s = replace_once(
    s,
    '    val transcriptionModel: String = "",\n    val voice: String = ""\n)',
    '    val transcriptionModel: String = "",\n    val voice: String = "",\n    val responseFormat: String? = null\n)',
    "media response format"
)
write(path, s)

# UiState: separate optional response format for OR reply speech.
path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
s = read(path)
s = replace_once(
    s,
    '    val openRouterSpeechModel: String = "",\n    val openRouterSpeechVoice: String = "",\n',
    '    val openRouterSpeechModel: String = "",\n    val openRouterSpeechVoice: String = "",\n    val openRouterSpeechResponseFormat: String = "",\n',
    "UiState reply speech response format"
)
write(path, s)

# ChatViewModel: answer speech model/voice/format are independent and all extras are optional.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
s = read(path)
old_init = '''            openRouterSpeechModel = prefs.getString("reply_speech_model", null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: openRouterFeaturePrefs.media().speechModel,
            openRouterSpeechVoice = run {
                val replyModel = prefs.getString("reply_speech_model", null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: openRouterFeaturePrefs.media().speechModel
                prefs.getString(replySpeechVoiceKey(replyModel), null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: if (replyModel == openRouterFeaturePrefs.media().speechModel) openRouterFeaturePrefs.media().voice else ""
            },
'''
new_init = '''            openRouterSpeechModel = prefs.getString("reply_speech_model", "").orEmpty().trim(),
            openRouterSpeechVoice = run {
                val replyModel = prefs.getString("reply_speech_model", "").orEmpty().trim()
                if (replyModel.isBlank()) "" else prefs.getString(replySpeechVoiceKey(replyModel), "").orEmpty().trim()
            },
            openRouterSpeechResponseFormat = run {
                val replyModel = prefs.getString("reply_speech_model", "").orEmpty().trim()
                if (replyModel.isBlank()) "" else prefs.getString(replySpeechFormatKey(replyModel), "").orEmpty().trim()
            },
'''
s = replace_once(s, old_init, new_init, "reply speech initial state")
start = s.index('    private fun replySpeechVoiceKey(modelId: String): String =')
end = s.index('    fun isDiagnosticLoggingEnabled(): Boolean', start)
replacement = '''    private fun replySpeechVoiceKey(modelId: String): String = "reply_speech_voice::${modelId.trim()}"
    private fun replySpeechFormatKey(modelId: String): String = "reply_speech_format::${modelId.trim()}"

    private fun normalizeSpeechResponseFormat(value: String): String = when (value.trim().lowercase()) {
        "mp3" -> "mp3"
        "pcm" -> "pcm"
        else -> ""
    }

    fun setOpenRouterSpeechModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isBlank()) {
            prefs.edit().remove("reply_speech_model").apply()
        } else {
            prefs.edit().putString("reply_speech_model", clean).apply()
        }
        val savedVoice = if (clean.isBlank()) "" else prefs.getString(replySpeechVoiceKey(clean), "").orEmpty().trim()
        val savedFormat = if (clean.isBlank()) "" else prefs.getString(replySpeechFormatKey(clean), "").orEmpty().trim()
        _state.value = _state.value.copy(
            openRouterSpeechModel = clean,
            openRouterSpeechVoice = savedVoice,
            openRouterSpeechResponseFormat = normalizeSpeechResponseFormat(savedFormat),
            status = if (clean.isBlank()) "Модель озвучивания ответов не выбрана" else "Модель озвучивания ответов сохранена"
        )
    }

    fun setOpenRouterSpeechVoice(voice: String) {
        val model = _state.value.openRouterSpeechModel.trim()
        if (model.isBlank()) return
        val clean = voice.trim()
        val key = replySpeechVoiceKey(model)
        if (clean.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, clean).apply()
        _state.value = _state.value.copy(
            openRouterSpeechVoice = clean,
            status = if (clean.isBlank()) "Голос для ответов не задан" else "Голос озвучивания ответов сохранён"
        )
    }

    fun setOpenRouterSpeechResponseFormat(value: String) {
        val model = _state.value.openRouterSpeechModel.trim()
        if (model.isBlank()) return
        val clean = normalizeSpeechResponseFormat(value)
        val key = replySpeechFormatKey(model)
        if (clean.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, clean).apply()
        _state.value = _state.value.copy(
            openRouterSpeechResponseFormat = clean,
            status = if (clean.isBlank()) "Формат ответов: Авто" else "Формат ответов: ${clean.uppercase()}"
        )
    }

'''
s = s[:start] + replacement + s[end:]
write(path, s)

# Audio client: response_format is optional, Auto handles known provider constraints,
# actual content type determines the result format, and raw PCM can be wrapped as WAV.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterAudioClient.kt"
s = read(path)
s = replace_once(s, 'import java.io.IOException\nimport java.util.concurrent.TimeUnit\n', 'import java.io.IOException\nimport java.nio.ByteBuffer\nimport java.nio.ByteOrder\nimport java.util.concurrent.TimeUnit\n', "audio imports")
s = replace_once(
    s,
    '''    data class SpeechResult(
        val bytes: ByteArray,
        val mimeType: String,
        val format: String,
        val generationId: String? = null
    )
''',
    '''    data class SpeechResult(
        val bytes: ByteArray,
        val mimeType: String,
        val format: String,
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
        val generationId: String? = null
    )
''',
    "SpeechResult metadata"
)
start = s.index('    suspend fun synthesize(')
end = s.index('    companion object {', start)
new_synthesize = '''    suspend fun synthesize(
        apiKey: String,
        model: String,
        input: String,
        voice: String? = null,
        responseFormat: String? = null,
        speed: Double? = null,
        baseUrl: String = DEFAULT_BASE_URL
    ): SpeechResult {
        require(input.isNotBlank()) { "Нет текста для озвучивания" }
        val requestedFormat = normalizeSpeechResponseFormat(responseFormat)
        val automaticFormat = requestedFormat == null
        val firstFormat = resolveSpeechResponseFormat(model, requestedFormat)

        return suspendCancellableCoroutine { continuation ->
            var activeCall: Call? = null
            continuation.invokeOnCancellation { activeCall?.cancel() }

            fun enqueue(format: String?, retryAllowed: Boolean) {
                val payload = JsonObject().apply {
                    addProperty("model", model)
                    addProperty("input", input)
                    voice?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("voice", it) }
                    format?.let { addProperty("response_format", it) }
                    speed?.takeIf { it > 0.0 }?.let { addProperty("speed", it) }
                }
                val request = Request.Builder()
                    .url(endpoint(baseUrl, "audio/speech"))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("X-Title", "Umnik Android")
                    .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                    .build()

                val call = http.newCall(request)
                activeCall = call
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { current ->
                            if (!current.isSuccessful) {
                                val body = current.body?.string().orEmpty()
                                val suggested = if (automaticFormat && retryAllowed) suggestedSpeechFormat(body) else null
                                if (suggested != null && suggested != format && continuation.isActive) {
                                    enqueue(suggested, retryAllowed = false)
                                    return
                                }
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(apiError(current.code, body)))
                                return
                            }
                            runCatching {
                                val bytes = current.body?.bytes() ?: ByteArray(0)
                                if (bytes.isEmpty()) error("OpenRouter вернул пустой аудиофайл")
                                val contentType = current.header("Content-Type").orEmpty()
                                val mime = contentType.substringBefore(';').trim().lowercase()
                                val actualFormat = when (mime) {
                                    "audio/mpeg", "audio/mp3" -> "mp3"
                                    "audio/pcm", "audio/l16" -> "pcm"
                                    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                                    else -> format ?: firstFormat ?: "pcm"
                                }
                                SpeechResult(
                                    bytes = bytes,
                                    mimeType = mime.ifBlank {
                                        when (actualFormat) {
                                            "mp3" -> "audio/mpeg"
                                            "wav" -> "audio/wav"
                                            else -> "audio/pcm"
                                        }
                                    },
                                    format = actualFormat,
                                    sampleRateHz = contentTypeParameter(contentType, "rate")?.toIntOrNull(),
                                    channels = contentTypeParameter(contentType, "channels")?.toIntOrNull(),
                                    generationId = current.header("X-Generation-Id")
                                )
                            }.onSuccess { result ->
                                if (continuation.isActive) continuation.resume(result)
                            }.onFailure { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                })
            }

            enqueue(firstFormat, retryAllowed = true)
        }
    }

'''
s = s[:start] + new_synthesize + s[end:]
old_companion = '''    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        fun normalizeAudioFormat(value: String): String {
            val lower = value.trim().lowercase().substringAfterLast('.')
            return when (lower) {
                "mpeg", "mpga" -> "mp3"
                "mp4" -> "m4a"
                "x-wav", "wave" -> "wav"
                else -> lower.takeIf { it in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac") } ?: "mp3"
            }
        }
    }
'''
new_companion = '''    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        fun normalizeAudioFormat(value: String): String {
            val lower = value.trim().lowercase().substringAfterLast('.')
            return when (lower) {
                "mpeg", "mpga" -> "mp3"
                "mp4" -> "m4a"
                "x-wav", "wave" -> "wav"
                else -> lower.takeIf { it in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac") } ?: "mp3"
            }
        }

        fun normalizeSpeechResponseFormat(value: String?): String? = when (value?.trim()?.lowercase()) {
            "mp3" -> "mp3"
            "pcm" -> "pcm"
            else -> null
        }

        /** Auto is intentionally conservative: known single-format families get a safe value,
         * while unknown/current providers receive no response_format and may use their default. */
        fun resolveSpeechResponseFormat(model: String, requested: String?): String? {
            normalizeSpeechResponseFormat(requested)?.let { return it }
            val id = model.trim().lowercase()
            return when {
                "gemini" in id && ("tts" in id || "speech" in id) -> "pcm"
                "voxtral" in id && "tts" in id -> "mp3"
                else -> null
            }
        }

        fun pcmToWav(
            pcm: ByteArray,
            sampleRateHz: Int = 24_000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val safeRate = sampleRateHz.takeIf { it in 8_000..192_000 } ?: 24_000
            val safeChannels = channels.takeIf { it in 1..8 } ?: 1
            val safeBits = bitsPerSample.takeIf { it in setOf(8, 16, 24, 32) } ?: 16
            val blockAlign = safeChannels * safeBits / 8
            val byteRate = safeRate * blockAlign
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(safeChannels.toShort())
            header.putInt(safeRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(safeBits.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.size)
            return header.array() + pcm
        }

        private fun suggestedSpeechFormat(body: String): String? {
            val lower = body.lowercase()
            if ("response_format" !in lower && "format" !in lower) return null
            return when {
                Regex("only\\s+(supports?|accepts?)\\s+[^.]{0,40}pcm").containsMatchIn(lower) ||
                    Regex("pcm[^.]{0,20}only").containsMatchIn(lower) -> "pcm"
                Regex("only\\s+(supports?|accepts?)\\s+[^.]{0,40}mp3").containsMatchIn(lower) ||
                    Regex("mp3[^.]{0,20}only").containsMatchIn(lower) -> "mp3"
                else -> null
            }
        }

        private fun contentTypeParameter(contentType: String, name: String): String? = contentType
            .split(';')
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }
'''
s = replace_once(s, old_companion, new_companion, "audio companion")
write(path, s)

# Reply playback: voice and format are optional; PCM is wrapped into WAV before MediaPlayer.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterSpeechPlayer.kt"
s = read(path)
s = s.replace('import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs\n', '')
s = s.replace('    private val featurePrefs = OpenRouterFeaturePrefs(appContext)\n', '')
old_voice = '''        val baseUrl = viewModel.connectionTextEndpoint(profile.id)
        val replyVoice = appState.openRouterSpeechVoice.trim()
        if (replyVoice.isBlank()) {
            mutableState.value = OpenRouterSpeechPlaybackState(error = "Голос озвучивания ответов не выбран")
            return
        }
        val chunks = splitForSpeech(text)
'''
new_voice = '''        val baseUrl = viewModel.connectionTextEndpoint(profile.id)
        val replyVoice = appState.openRouterSpeechVoice.trim().takeIf { it.isNotBlank() }
        val replyFormat = appState.openRouterSpeechResponseFormat.trim().takeIf { it.isNotBlank() }
        val chunks = splitForSpeech(text)
'''
s = replace_once(s, old_voice, new_voice, "reply optional voice")
s = s.replace('''                            voice = replyVoice,
                            baseUrl = baseUrl
''', '''                            voice = replyVoice,
                            responseFormat = replyFormat,
                            baseUrl = baseUrl
''')
old_sig = '''        text: String,
        voice: String?,
        baseUrl: String
    ): File {
'''
new_sig = '''        text: String,
        voice: String?,
        responseFormat: String?,
        baseUrl: String
    ): File {
'''
s = replace_once(s, old_sig, new_sig, "synthesizeChunk signature")
s = replace_once(
    s,
    '''                    voice = voice,
                    responseFormat = "mp3",
                    baseUrl = baseUrl
                )
                val extension = result.format.lowercase()
                    .replace(Regex("[^a-z0-9]"), "")
                    .ifBlank { "mp3" }
                val file = withContext(Dispatchers.IO) {
                    File(cacheDir, "speech_${UUID.randomUUID()}.$extension").apply {
                        writeBytes(result.bytes)
                    }
                }
''',
    '''                    voice = voice,
                    responseFormat = responseFormat,
                    baseUrl = baseUrl
                )
                val pcm = result.format.equals("pcm", ignoreCase = true) || result.mimeType.equals("audio/pcm", ignoreCase = true)
                val extension = if (pcm) "wav" else result.format.lowercase()
                    .replace(Regex("[^a-z0-9]"), "")
                    .ifBlank { "mp3" }
                val playableBytes = if (pcm) {
                    OpenRouterAudioClient.pcmToWav(
                        pcm = result.bytes,
                        sampleRateHz = result.sampleRateHz ?: 24_000,
                        channels = result.channels ?: 1
                    )
                } else result.bytes
                val file = withContext(Dispatchers.IO) {
                    File(cacheDir, "speech_${UUID.randomUUID()}.$extension").apply {
                        writeBytes(playableBytes)
                    }
                }
''',
    "reply PCM playback"
)
write(path, s)

# Hub controller: keep document speech extras independent, optional, and format-aware.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
s = read(path)
s = replace_once(
    s,
    '''                val media = current.copy(
                    speechModel = model.id,
                    voice = if (current.speechModel == model.id) current.voice else ""
                )
''',
    '''                val media = current.copy(
                    speechModel = model.id,
                    voice = if (current.speechModel == model.id) current.voice else "",
                    responseFormat = if (current.speechModel == model.id) current.responseFormat else null
                )
''',
    "document speech model reset"
)
s = replace_once(s, 'val media = mutableState.value.media.copy(speechModel = "", voice = "")', 'val media = mutableState.value.media.copy(speechModel = "", voice = "", responseFormat = null)', "clear document speech")
s = replace_once(
    s,
    '''    fun updateReplySpeechVoice(voice: String) {
        viewModel.setOpenRouterSpeechVoice(voice)
        mutableState.value = mutableState.value.copy(status = if (voice.isBlank()) "Голос ответов снят" else "Голос ответов сохранён")
    }
''',
    '''    fun updateReplySpeechVoice(voice: String) {
        viewModel.setOpenRouterSpeechVoice(voice)
        mutableState.value = mutableState.value.copy(status = if (voice.isBlank()) "Голос ответов не задан" else "Голос ответов сохранён")
    }

    fun updateReplySpeechResponseFormat(format: String) {
        viewModel.setOpenRouterSpeechResponseFormat(format)
        mutableState.value = mutableState.value.copy(status = if (format.isBlank()) "Формат ответов: Авто" else "Формат ответов: ${format.uppercase()}")
    }
''',
    "reply format controller"
)
s = replace_once(
    s,
    '''                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result.bytes, result.mimeType, result.format)
''',
    '''                    voice = media.voice.takeIf { it.isNotBlank() },
                    responseFormat = media.responseFormat?.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result)
''',
    "document synthesize format"
)
old_answer = '''        val profile = openRouterProfile()
        val media = featurePrefs.media()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")
            return
        }
        if (media.speechModel.isBlank()) {
            viewModel.setOpenRouterSpeechModel("")
            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель озвучивания OpenRouter")
            return
        }
'''
new_answer = '''        val profile = openRouterProfile()
        val appState = viewModel.state.value
        val model = appState.openRouterSpeechModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")
            return
        }
        if (model.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель озвучивания ответов")
            return
        }
'''
s = replace_once(s, old_answer, new_answer, "legacy answer speech settings")
s = replace_once(
    s,
    '''                    model = media.speechModel,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result.bytes, result.mimeType, result.format)
''',
    '''                    model = model,
                    input = text,
                    voice = appState.openRouterSpeechVoice.takeIf { it.isNotBlank() },
                    responseFormat = appState.openRouterSpeechResponseFormat.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result)
''',
    "legacy answer synthesize format"
)
s = s.replace('assistantText = "Озвучка OpenRouter · ${media.speechModel.substringAfterLast(\'/\')}"', 'assistantText = "Озвучка OpenRouter · ${model.substringAfterLast(\'/\')}"')
s = replace_once(
    s,
    '''    private fun saveGeneratedAudio(bytes: ByteArray, mime: String, format: String): GeneratedFile =
        saveGeneratedBinary("umnik_speech_${System.currentTimeMillis()}.$format", mime, bytes)
''',
    '''    private fun saveGeneratedAudio(result: OpenRouterAudioClient.SpeechResult): GeneratedFile {
        val pcm = result.format.equals("pcm", ignoreCase = true) || result.mimeType.equals("audio/pcm", ignoreCase = true)
        val bytes = if (pcm) {
            OpenRouterAudioClient.pcmToWav(
                pcm = result.bytes,
                sampleRateHz = result.sampleRateHz ?: 24_000,
                channels = result.channels ?: 1
            )
        } else result.bytes
        val format = if (pcm) "wav" else result.format
        val mime = if (pcm) "audio/wav" else result.mimeType
        return saveGeneratedBinary("umnik_speech_${System.currentTimeMillis()}.$format", mime, bytes)
    }
''',
    "save PCM as WAV"
)
write(path, s)

# Hub UI: optional extras and separate response-format selection in both speech modes.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
s = read(path)
s = replace_once(
    s,
    '    var voice by remember(state.media.voice) { mutableStateOf(state.media.voice) }\n',
    '    var voice by remember(state.media.speechModel, state.media.voice) { mutableStateOf(state.media.voice) }\n    var speechResponseFormat by remember(state.media.speechModel, state.media.responseFormat) { mutableStateOf(state.media.responseFormat.orEmpty()) }\n',
    "document speech UI state"
)
old_speech_ui = '''                Text("Нейросетевая озвучка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CategoryModelPicker(
                    title = "Модель озвучивания",
                    current = state.media.speechModel,
                    models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.SPEECH) }
                )
                OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Voice, если модель поддерживает") }, singleLine = true)
                OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
                FilledTonalButton(
                    onClick = { speechTextPicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Загрузить текстовый файл") }
                Button(onClick = { controller.updateMedia(state.media.copy(voice = voice.trim())); controller.synthesize(speechText) }, enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Text("Создать аудио") }
'''
new_speech_ui = '''                Text("Нейросетевая озвучка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val selectedSpeechModel = state.catalog.firstOrNull { it.id == state.media.speechModel }
                val documentVoiceOptions = selectedSpeechModel?.parameterValues("voice").orEmpty()
                CategoryModelPicker(
                    title = "Модель озвучивания",
                    current = state.media.speechModel,
                    models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.SPEECH) }
                )
                if (state.media.speechModel.isNotBlank()) {
                    Text("Дополнительные параметры (необязательно)", modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                    Text(
                        "Некоторым моделям нужен голос или конкретный формат, другим достаточно самой модели. «Авто» не передаёт лишний формат и учитывает известные ограничения Gemini/Voxtral.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = voice.isBlank(),
                                onClick = { voice = "" },
                                label = { Text("Без голоса") }
                            )
                        }
                        items(documentVoiceOptions) { option ->
                            FilterChip(
                                selected = voice == option,
                                onClick = { voice = option },
                                label = { Text(option, maxLines = 1) }
                            )
                        }
                    }
                    OutlinedTextField(
                        voice,
                        { voice = it },
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = { Text("Voice / ID голоса (необязательно)") },
                        singleLine = true
                    )
                    Text("Формат ответа", modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(listOf("" to "Авто", "mp3" to "MP3", "pcm" to "PCM")) { (value, label) ->
                            FilterChip(
                                selected = speechResponseFormat == value,
                                onClick = { speechResponseFormat = value },
                                label = { Text(label) }
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = { controller.updateMedia(state.media.copy(voice = voice.trim(), responseFormat = speechResponseFormat.ifBlank { null })) },
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                    ) { Text("Сохранить параметры") }
                }
                OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
                FilledTonalButton(
                    onClick = { speechTextPicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Загрузить текстовый файл") }
                Button(
                    onClick = {
                        controller.updateMedia(state.media.copy(voice = voice.trim(), responseFormat = speechResponseFormat.ifBlank { null }))
                        controller.synthesize(speechText)
                    },
                    enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Создать аудио") }
'''
s = replace_once(s, old_speech_ui, new_speech_ui, "document speech controls")
# Replace reply page entirely up to ShellPage.
start = s.index('@Composable\nprivate fun ReplySpeechPage(')
end = s.index('@Composable\nprivate fun ShellPage', start)
new_reply_page = '''@Composable
private fun ReplySpeechPage(
    state: OpenRouterHubState,
    appState: UiState,
    controller: OpenRouterHubController
) {
    val selected = state.catalog.firstOrNull { it.id == appState.openRouterSpeechModel }
    val voiceOptions = selected?.parameterValues("voice").orEmpty()
    var manualVoice by remember(appState.openRouterSpeechModel, appState.openRouterSpeechVoice) {
        mutableStateOf(appState.openRouterSpeechVoice)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Кнопка OR под ответами", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Эти настройки не влияют на режим «+ → Озвучить». Достаточно выбрать модель; голос и формат задаются только если они нужны выбранному провайдеру.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            CategoryModelPicker(
                title = "Модель озвучивания ответов",
                current = appState.openRouterSpeechModel,
                models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                onSelect = controller::assignReplySpeechModel
            )
        }
        if (appState.openRouterSpeechModel.isNotBlank()) {
            item {
                Text("Голос (необязательно)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Если у модели есть голос по умолчанию, оставьте «Не задавать». Если OpenRouter требует voice, выберите вариант из списка или введите ID вручную.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = appState.openRouterSpeechVoice.isBlank(),
                            onClick = {
                                manualVoice = ""
                                controller.updateReplySpeechVoice("")
                            },
                            label = { Text("Не задавать") }
                        )
                    }
                    items(voiceOptions) { voice ->
                        FilterChip(
                            selected = appState.openRouterSpeechVoice == voice,
                            onClick = {
                                manualVoice = voice
                                controller.updateReplySpeechVoice(voice)
                            },
                            label = { Text(voice, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = manualVoice,
                    onValueChange = { manualVoice = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    label = { Text("ID голоса") },
                    placeholder = { Text("Оставьте пустым, если голос не нужен") },
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = { controller.updateReplySpeechVoice(manualVoice.trim()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text(if (manualVoice.isBlank()) "Сохранить без голоса" else "Сохранить голос") }
            }
            item {
                Text("Формат ответа", fontWeight = FontWeight.SemiBold)
                Text(
                    "Авто: для Gemini TTS используется PCM, для Voxtral TTS — MP3, а неизвестным моделям Umnik не навязывает формат. Если провайдер вернёт однозначную ошибку формата, Auto один раз повторит запрос с требуемым MP3/PCM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(listOf("" to "Авто", "mp3" to "MP3", "pcm" to "PCM")) { (value, label) ->
                        FilterChip(
                            selected = appState.openRouterSpeechResponseFormat == value,
                            onClick = { controller.updateReplySpeechResponseFormat(value) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "PCM Umnik автоматически оборачивает в WAV для воспроизведения на Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

'''
s = s[:start] + new_reply_page + s[end:]
write(path, s)

# Main settings: OR works with just a model; summaries no longer imply voice is mandatory.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
s = read(path)
s = s.replace('openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank() && state.openRouterSpeechVoice.isNotBlank()', 'openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank()')
s = replace_once(
    s,
    '''                    subtitle = when {
                        state.openRouterSpeechModel.isBlank() -> "Модель не выбрана"
                        state.openRouterSpeechVoice.isBlank() -> "${state.openRouterSpeechModel.substringAfterLast('/')} · голос не выбран"
                        else -> "${state.openRouterSpeechModel.substringAfterLast('/')} · ${state.openRouterSpeechVoice}"
                    },
''',
    '''                    subtitle = when {
                        state.openRouterSpeechModel.isBlank() -> "Модель не выбрана"
                        else -> buildList {
                            add(state.openRouterSpeechModel.substringAfterLast('/'))
                            if (state.openRouterSpeechVoice.isNotBlank()) add(state.openRouterSpeechVoice)
                            add(state.openRouterSpeechResponseFormat.ifBlank { "Авто" }.uppercase())
                        }.joinToString(" · ")
                    },
''',
    "reply speech settings subtitle"
)
s = replace_once(s, 'Text("Настроить модель и голос")', 'Text("Настроить модель и параметры")', "reply speech settings button")
s = replace_once(s, 'title = "Озвучивание текста и документов",\n                    subtitle = "Отдельная модель и голос",', 'title = "Озвучивание текста и документов",\n                    subtitle = "Отдельная модель и параметры",', "document speech settings subtitle")
write(path, s)

# Guide: explain optional voice, response format and independent settings.
path = "app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt"
s = read(path)
old = '''## Озвучить текст или документ
Режим **+ → Озвучить** делает отдельную аудиозапись из введённого текста или текстового файла. Для него используются **свои модель и голос**.

1. Нажмите **+ → Озвучить**.
2. Выберите Speech/Audio-модель.
3. Укажите совместимый с этой моделью **Voice / ID голоса**.
4. Введите текст или нажмите **Загрузить текстовый файл**.
5. Нажмите **Создать аудио**.

## Озвучить ответ кнопкой OR
Кнопка **OR** под ответом работает отдельно от режима выше. Она нужна для быстрого прослушивания ответа и не добавляет аудиофайл в чат.

Откройте **Настройки → Озвучивание ответов OpenRouter** и выберите отдельную модель и голос. Поэтому, например, ответы можно слушать бесплатной моделью, а большой документ озвучивать другой платной моделью.

**Важно:** у разных TTS-моделей разные голоса. После смены модели проверьте голос. Если модель или голос для ответов не выбраны, кнопка OR остаётся неактивной.
'''
new = '''## Озвучить текст или документ
Режим **+ → Озвучить** делает отдельную аудиозапись из введённого текста или текстового файла. Для него используются **свои модель и параметры**, независимо от озвучивания ответов.

1. Нажмите **+ → Озвучить**.
2. Выберите Speech/Audio-модель.
3. Если модели нужен **Voice / ID голоса**, выберите или введите его. Если не нужен — оставьте «Без голоса».
4. Формат обычно оставьте **Авто**. При необходимости можно вручную выбрать MP3 или PCM.
5. Введите текст или нажмите **Загрузить текстовый файл**.
6. Нажмите **Создать аудио**.

## Озвучить ответ кнопкой OR
Кнопка **OR** под ответом работает отдельно от режима выше. Она нужна для быстрого прослушивания ответа и не добавляет аудиофайл в чат.

Откройте **Настройки → Озвучивание ответов OpenRouter**. Здесь отдельно выбираются модель, необязательный голос и формат. Поэтому ответы можно слушать одной моделью, а документы озвучивать другой.

**Параметры TTS различаются:** большинству моделей нужен совместимый `voice`, но некоторые имеют голос по умолчанию и работают без него. Форматы тоже различаются: например, Gemini TTS требует PCM, а Voxtral TTS — MP3. В режиме **Авто** Umnik учитывает известные ограничения, а PCM автоматически превращает в WAV для обычного воспроизведения Android.

Если OpenRouter сообщает `explicit voice is required`, вернитесь в настройки этой озвучки и задайте голос. Если сообщает ошибку `response_format`, оставьте **Авто** или выберите поддерживаемый MP3/PCM. Кнопка OR активна уже после выбора модели; дополнительные параметры не считаются обязательными для всех моделей.
'''
s = replace_once(s, old, new, "guide speech section")
write(path, s)

# Changelog
path = "CHANGELOG.md"
s = read(path)
entry = '''## v1.10.7 - 2026-09-14

- TTS больше не навязывает MP3 всем моделям: `response_format` стал отдельным необязательным параметром с вариантами Авто / MP3 / PCM для озвучивания ответов и для режима `+ → Озвучить`.
- Режим Авто учитывает известные ограничения OpenRouter: Gemini TTS получает PCM, Voxtral TTS — MP3; для неизвестных моделей формат не навязывается. При однозначной ошибке формата Auto один раз повторяет запрос с требуемым MP3/PCM.
- Голос больше не считается обязательным параметром для всех TTS-моделей. Модель можно сохранить и использовать без дополнительных параметров; если конкретный провайдер требует `voice`, его можно выбрать или ввести отдельно.
- Настройки озвучивания ответов и текста/документов остаются полностью независимыми: каждая хранит свою модель, голос и формат.
- Сырые PCM-ответы автоматически оборачиваются в WAV перед воспроизведением/сохранением, поэтому Android может нормально проигрывать Gemini TTS.
- Памятка обновлена: описаны необязательный голос, выбор формата и типичные ошибки `voice` / `response_format`.
- Версия: 1.10.7 / versionCode 107.

'''
s = replace_once(s, "## Unreleased\n\n", "## Unreleased\n\n" + entry, "changelog")
write(path, s)

# Unit tests for provider-format compatibility and PCM wrapping.
test_path = ROOT / "app/src/test/java/com/ayuemin/ymnik/network/OpenRouterAudioClientTest.kt"
test_path.write_text('''package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAudioClientTest {
    @Test
    fun autoFormatUsesKnownSingleFormatProviders() {
        assertEquals(
            "pcm",
            OpenRouterAudioClient.resolveSpeechResponseFormat("google/gemini-3.1-flash-tts-preview", null)
        )
        assertEquals(
            "mp3",
            OpenRouterAudioClient.resolveSpeechResponseFormat("mistralai/voxtral-mini-tts-2603", null)
        )
        assertNull(OpenRouterAudioClient.resolveSpeechResponseFormat("x-ai/grok-voice-tts-1.0", null))
    }

    @Test
    fun explicitFormatWinsOverAuto() {
        assertEquals(
            "mp3",
            OpenRouterAudioClient.resolveSpeechResponseFormat("google/gemini-3.1-flash-tts-preview", "mp3")
        )
        assertEquals(
            "pcm",
            OpenRouterAudioClient.resolveSpeechResponseFormat("mistralai/voxtral-mini-tts-2603", "pcm")
        )
    }

    @Test
    fun pcmWrapperCreatesWaveContainer() {
        val wav = OpenRouterAudioClient.pcmToWav(byteArrayOf(0, 0, 1, 0), sampleRateHz = 24_000, channels = 1)
        assertTrue(wav.size >= 48)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
    }
}
''', encoding="utf-8")

print("v1.10.7 migration applied")
