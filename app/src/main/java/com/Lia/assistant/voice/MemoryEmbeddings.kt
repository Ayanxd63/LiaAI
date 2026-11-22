package com.Lia.assistant.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.Lia.assistant.data.FactMemoryStore
import com.Lia.assistant.data.MemoryEmbeddingPreferences
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * V17 item 5 — on-device memory embeddings (MemEmbed pattern).
 *
 * Model: MiniLM-L6-v2 exported to TFLite (~22 MB, Apache-2.0), run
 * by TensorFlow Lite 2.17.0 — a separate runtime from sherpa-onnx,
 * because sherpa has no embedding model for this size and TFLite is
 * the standard home for small sentence transformers on Android.
 * Saved facts are embedded once (at search time, lazily) and the
 * vectors cached on-device; a question is embedded and ranked by
 * cosine similarity, so "meri ammi ka naam kya hai?" finds the fact
 * "Ammi ka naam Fatima hai" even when no word matches exactly.
 *
 * Same privacy rules as the V15 fact store: embeddings live only on
 * this phone (SharedPreferences, excluded from backup), are computed
 * from facts the user already saved — never from silent capture —
 * and are never sent anywhere. Deleting a fact deletes its vector;
 * clearing memory clears the vectors too.
 *
 * The tokenizer is a small Kotlin WordPiece implementation over the
 * model's vocab.txt (30,522 tokens, downloaded with the model). The
 * exact TFLite input signature is NOT documented in the model README,
 * so the driver discovers it at runtime: it reads the input tensor
 * count (2 = ids+mask, 3 = ids+mask+token-type) and shapes, and
 * handles both a pooled [1, 384] output and a token-level
 * [1, seq, 384] output (mean-pooled + L2-normalized here).
 *
 * TFLite works on every ABI — unlike sherpa, this feature does not
 * need arm64/x86_64.
 */
object MemoryEmbeddings {

    private const val TAG = "MemoryEmbeddings"
    private const val PREF_NAME = "lia_fact_embeddings"

    /** MiniLM-L6-v2 embedding dimension. */
    const val EMBEDDING_DIM = 384

    /** Max tokens per text (model context is 256; 128 is plenty). */
    private const val MAX_TOKENS = 128

    /** Cosine similarity below this is not a match. */
    private const val MATCH_THRESHOLD = 0.25f

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var vocab: List<String>? = null

    @Volatile
    private var vocabIndex: Map<String, Int>? = null

    @Volatile
    private var loadFailed = false

    /** True when the model files are downloaded (checked on IO). */
    fun isReady(context: Context): Boolean {
        return VoiceModelManager.isModelReady(context, VoiceModelManager.MODEL_MINILM)
    }

    /** Load the interpreter + vocab once (IO thread). False on failure. */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (interpreter != null && vocab != null) return true
        if (loadFailed) return false
        return runCatching {
            val dir = VoiceModelManager.engineDir(context, VoiceModelManager.MODEL_MINILM)
            val modelFile = File(dir, "sentence_transformer.tflite")
            val vocabFile = File(dir, "vocab.txt")
            val options = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(modelFile, options)
            val lines = vocabFile.readLines()
            vocab = lines
            vocabIndex = lines.mapIndexed { index, token -> token to index }.toMap()
            Log.d(TAG, "MiniLM loaded: ${lines.size} vocab tokens")
            true
        }.onFailure { e ->
            Log.w(TAG, "MiniLM load failed", e)
            loadFailed = true
        }.getOrDefault(false)
    }

    /** Embed [text] into a 384-d L2-normalized vector, or null. */
    fun embed(context: Context, text: String): FloatArray? {
        if (text.isBlank()) return null
        if (!ensureLoaded(context)) return null
        val interp = interpreter ?: return null
        val tokens = tokenize(text) ?: return null
        return runCatching {
            val inputCount = interp.inputTensorCount
            val inputShape = interp.getInputTensor(0).shape()
            // Fixed [1, N] shape: pad/truncate to N. Dynamic shape
            // ([1, -1]): resize to the real token count.
            val seqLen = if (inputShape.size >= 2 && inputShape[1] > 0) {
                inputShape[1]
            } else {
                runCatching {
                    interp.resizeInput(0, intArrayOf(1, tokens.size))
                    interp.allocateTensors()
                }
                tokens.size
            }
            val ids = IntArray(seqLen)
            val mask = IntArray(seqLen)
            for (i in 0 until minOf(tokens.size, seqLen)) {
                ids[i] = tokens[i]
                mask[i] = 1
            }
            val inputs: Array<Any> = if (inputCount >= 3) {
                arrayOf(arrayOf(ids), arrayOf(mask), arrayOf(IntArray(seqLen)))
            } else {
                arrayOf(arrayOf(ids), arrayOf(mask))
            }
            val outputShape = interp.getOutputTensor(0).shape()
            val output: Any = if (outputShape.size == 3) {
                // Token-level output [1, seq, 384] — mean-pool here.
                Array(1) { Array(seqLen) { FloatArray(EMBEDDING_DIM) } }
            } else {
                Array(1) { FloatArray(EMBEDDING_DIM) }
            }
            val outputs = mapOf(0 to output)
            interp.runForMultipleInputsOutputs(inputs, outputs)
            val vector: FloatArray = if (outputShape.size == 3) {
                @Suppress("UNCHECKED_CAST")
                val tokenVectors = (output as Array<Array<FloatArray>>)[0]
                meanPool(tokenVectors, mask)
            } else {
                @Suppress("UNCHECKED_CAST")
                (output as Array<FloatArray>)[0]
            }
            l2Normalize(vector)
        }.onFailure { e ->
            Log.w(TAG, "MiniLM embed failed", e)
        }.getOrNull()
    }

    /**
     * The semantic "Things I remember about you:" block for [query]:
     * top 5 facts by cosine similarity, at most 800 chars. Empty
     * string when embeddings are off, the model is not ready, or
     * nothing matches — callers fall back to the recency block then.
     * Runs on the calling thread (IO).
     */
    fun semanticMemoryBlock(context: Context, query: String): String {
        if (!MemoryEmbeddingPreferences.isEnabled(context)) return ""
        if (!isReady(context)) return ""
        val facts = FactMemoryStore.loadFacts(context)
        if (facts.isEmpty()) return ""
        val queryVec = embed(context, query) ?: return ""
        val scored = facts.mapNotNull { fact ->
            val vec = getFactEmbedding(context, fact.id)
                ?: embed(context, fact.text)?.also { putFactEmbedding(context, fact.id, it) }
                ?: return@mapNotNull null
            val sim = cosineSimilarity(queryVec, vec)
            if (sim >= MATCH_THRESHOLD) fact to sim else null
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return ""
        val lines = scored.take(5).joinToString("\n") { "- ${it.first.text}" }
        val block = "Things I remember about you:\n$lines"
        return if (block.length > 800) block.take(800) else block
    }

    /** Delete one fact's cached vector (call when the fact is deleted). */
    fun deleteFactEmbedding(context: Context, factId: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(factId).apply()
    }

    /** Delete every cached vector (call when memory is cleared). */
    fun clearAllEmbeddings(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun getFactEmbedding(context: Context, factId: String): FloatArray? {
        val encoded = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(factId, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / 4) { buffer.float }
        }.getOrNull()
    }

    private fun putFactEmbedding(context: Context, factId: String, vec: FloatArray) {
        val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buffer.putFloat(v)
        val encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(factId, encoded).apply()
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom == 0f) return 0f
        return (dot / denom).coerceIn(-1f, 1f)
    }

    private fun meanPool(tokenVectors: Array<FloatArray>, mask: IntArray): FloatArray {
        val out = FloatArray(EMBEDDING_DIM)
        var count = 0
        for (i in tokenVectors.indices) {
            if (i >= mask.size || mask[i] == 0) continue
            count++
            for (d in 0 until EMBEDDING_DIM) out[d] += tokenVectors[i][d]
        }
        if (count > 0) {
            for (d in 0 until EMBEDDING_DIM) out[d] /= count
        }
        return out
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return vec
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    // ------------------------------------------------------------------
    // WordPiece tokenizer (BERT-style, over the model's vocab.txt).
    // ------------------------------------------------------------------

    /**
     * Tokenize [text] into vocab ids with [CLS] … [SEP]. Null when the
     * vocab is not loaded. Lowercases, splits punctuation out, then
     * greedy longest-match WordPiece with "##" continuations.
     */
    private fun tokenize(text: String): IntArray? {
        val index = vocabIndex ?: return null
        val unkId = index["[UNK]"] ?: 100
        val clsId = index["[CLS]"] ?: 101
        val sepId = index["[SEP]"] ?: 102
        val ids = mutableListOf<Int>()
        ids.add(clsId)
        for (word in basicTokenize(text)) {
            if (ids.size >= MAX_TOKENS - 1) break
            if (word.length > 100) {
                ids.add(unkId)
                continue
            }
            var start = 0
            var failed = false
            val subTokens = mutableListOf<Int>()
            while (start < word.length) {
                var end = word.length
                var found: Int? = null
                while (start < end) {
                    val piece = if (start == 0) {
                        word.substring(start, end)
                    } else {
                        "##" + word.substring(start, end)
                    }
                    val id = index[piece]
                    if (id != null) {
                        found = id
                        break
                    }
                    end--
                }
                if (found == null) {
                    failed = true
                    break
                }
                subTokens.add(found)
                start = end
            }
            if (failed) {
                ids.add(unkId)
            } else {
                ids.addAll(subTokens)
            }
        }
        ids.add(sepId)
        return ids.toIntArray()
    }

    /** Lowercase + whitespace split + punctuation as own tokens. */
    private fun basicTokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val lower = text.lowercase()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current.clear()
            }
        }
        for (ch in lower) {
            when {
                ch.isWhitespace() -> flush()
                isPunctuation(ch) -> {
                    flush()
                    out.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        flush()
        return out
    }

    private fun isPunctuation(ch: Char): Boolean {
        return ch in "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }
}
