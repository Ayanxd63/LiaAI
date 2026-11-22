package com.Lia.assistant.voice

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * V15 — the offline on-device LLM (llama.cpp, vendored binding).
 *
 * Engine: the vendored llama.cpp Android binding (`llama-lib`, MIT) —
 * Qwen2.5-0.5B-Instruct Q4_K_M, CPU only, ~8–12 tokens/sec on the
 * Infinix HOT 40i (estimate — unverified without a real device).
 * It is a FALLBACK for no internet, not a replacement for the online
 * engines: replies are shorter and simpler, and Roman Urdu is weak
 * on a 0.5B model. Image stays online-only.
 *
 * How it fits the AiChat API (read from the vendored binding):
 * - `loadModel(path)` then `setSystemPrompt(...)` right after load.
 * - The native side keeps the conversation history itself, so each
 *   turn sends ONLY the latest user message (`sendUserPrompt`).
 * - A changed system prompt (personality / memory update) needs a
 *   reload: unload + load + set the new prompt (native history resets).
 * - The native library loads ASYNC inside the engine singleton, so
 *   this client waits for `State.Initialized` before loading a model.
 *
 * Guardrails (plan §2.5): the model is never initialised at startup
 * (lazy, only when offline mode is actually used); max 4 threads is
 * the native default; replies are capped to a short length; the model
 * auto-unloads after ~5 minutes idle (frees RAM and battery).
 *
 * All failures return [ChatReplyResult.Error] with plain language —
 * never a stack trace, never a crash. A missing native library (an
 * ABI the build did not ship, e.g. armeabi-v7a) reports offline as
 * unavailable on this phone.
 */
object OfflineLlmClient {

    /** Reply length cap for the tiny model (short replies by design). */
    private const val DEFAULT_PREDICT_LENGTH = 192

    /** Auto-unload the model after this long without a turn. */
    private const val IDLE_UNLOAD_MS = 5L * 60L * 1000L

    /** Max wait for the native library to finish loading. */
    private const val ENGINE_INIT_TIMEOUT_MS = 30_000L

    private val turnMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Application context, kept only to unload the engine later. */
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var loadedSystemPrompt: String? = null

    @Volatile
    private var lastUsedMs: Long = 0L

    private var idleUnloadJob: Job? = null

    /**
     * True when the native offline library shipped for this phone's
     * ABI (arm64-v8a / x86_64). False on ABIs the build does not cover.
     */
    fun isAvailable(context: Context): Boolean {
        return try {
            val libDir = context.applicationInfo.nativeLibraryDir
            File(libDir, "libai-chat.so").exists()
        } catch (_: Exception) {
            false
        }
    }

    /** True when the model file is downloaded AND verified. */
    fun isModelReady(context: Context): Boolean =
        OfflineModelManager.isModelReady(context)

    /**
     * One offline chat turn. Sends only [userMessage] — the engine
     * keeps the conversation history itself. Collects the token flow
     * into one reply string. [onToken] gets the accumulated text after
     * every token, so the UI can show the reply growing (progressive
     * display). Never throws.
     */
    suspend fun chat(
        context: Context,
        systemInstruction: String,
        userMessage: String,
        predictLength: Int = DEFAULT_PREDICT_LENGTH,
        onToken: ((String) -> Unit)? = null
    ): ChatReplyResult {
        if (!isAvailable(context)) {
            return ChatReplyResult.Error(
                "Offline AI is phone par nahi chal sakta — ye phone ka processor support nahi karta."
            )
        }
        val model = OfflineModelManager.MODEL_QWEN_0_5B
        val modelFile = OfflineModelManager.modelFile(context, model)
        if (!OfflineModelManager.isModelReady(context, model)) {
            return ChatReplyResult.Error(
                "Offline model abhi download nahi hua — Settings > Offline AI se Wi-Fi par download karo."
            )
        }
        OfflineModelManager.loadBlockedReason(context, model)?.let { reason ->
            return ChatReplyResult.Error(reason)
        }
        if (userMessage.isBlank()) {
            return ChatReplyResult.Error("Kuch likho ya bolo to jawab dun.")
        }

        return try {
            turnMutex.withLock {
                appContext = context.applicationContext
                val engine = AiChat.getInferenceEngine(context.applicationContext)
                ensureModelLoaded(context, engine, modelFile.absolutePath, systemInstruction)
                lastUsedMs = System.currentTimeMillis()
                idleUnloadJob?.cancel()

                val reply = StringBuilder()
                engine.sendUserPrompt(userMessage, predictLength).collect { token ->
                    reply.append(token)
                    onToken?.invoke(reply.toString())
                }
                val text = reply.toString().trim()
                scheduleIdleUnload(context)
                if (text.isBlank()) {
                    ChatReplyResult.Error("Offline jawab khaali aaya — dobara try karo.")
                } else {
                    ChatReplyResult.Success(text)
                }
            }
        } catch (e: UnsupportedArchitectureException) {
            unloadQuietly()
            ChatReplyResult.Error("Ye offline model is phone par nahi chal sakta.")
        } catch (e: Exception) {
            unloadQuietly()
            ChatReplyResult.Error(
                "Offline jawab nahi de saki — dobara try karo ya online engine use karo."
            )
        }
    }

    /** Unload the model now (frees RAM). Safe to call any time. */
    fun unloadNow() {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        unloadQuietly()
    }

    private suspend fun ensureModelLoaded(
        context: Context,
        engine: InferenceEngine,
        modelPath: String,
        systemInstruction: String
    ) {
        // The native library loads async inside the singleton — wait
        // for it before the first loadModel, or it throws. A model
        // already loaded (ModelReady) counts as ready too.
        val ready = withTimeoutOrNull(ENGINE_INIT_TIMEOUT_MS) {
            engine.state.first {
                it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
            }
        }
        if (ready is InferenceEngine.State.Error) {
            throw IllegalStateException("Offline engine failed to start.")
        }
        if (ready == null) {
            throw IllegalStateException("Offline engine timed out while starting.")
        }

        val needsReload = loadedModelPath != modelPath ||
            loadedSystemPrompt != systemInstruction
        if (!needsReload && engine.state.value is InferenceEngine.State.ModelReady) {
            return
        }
        if (loadedModelPath != null) {
            // Personality / memory changed: the system prompt must be
            // set right after a load, so reload (native history resets).
            unloadQuietly()
        }
        engine.loadModel(modelPath)
        engine.setSystemPrompt(systemInstruction)
        loadedModelPath = modelPath
        loadedSystemPrompt = systemInstruction
    }

    private fun scheduleIdleUnload(context: Context) {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            if (System.currentTimeMillis() - lastUsedMs >= IDLE_UNLOAD_MS) {
                unloadQuietly()
            }
        }
    }

    private fun unloadQuietly() {
        loadedModelPath = null
        loadedSystemPrompt = null
        val ctx = appContext ?: return
        runCatching {
            // getInstance may itself throw when the native lib is absent;
            // never let unload crash the caller.
            AiChat.getInferenceEngine(ctx).cleanUp()
        }
    }
}
