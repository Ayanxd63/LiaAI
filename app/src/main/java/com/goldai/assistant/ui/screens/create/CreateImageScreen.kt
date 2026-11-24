package com.goldai.assistant.ui.screens.create

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.goldai.assistant.data.ApiKeyStore
import com.goldai.assistant.data.EnginePreferences
import com.goldai.assistant.monetization.AdManager
import com.goldai.assistant.ui.components.NovaButton
import com.goldai.assistant.ui.components.NovaButtonType
import com.goldai.assistant.ui.components.NovaTextField
import com.goldai.assistant.ui.theme.LocalNovaColors
import com.goldai.assistant.ui.theme.nightSky
import com.goldai.assistant.voice.GeminiImageClient
import com.goldai.assistant.voice.ImageGenResult
import com.goldai.assistant.voice.PollinationsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * V20 — Create Image, simplified to the bare minimum: one prompt
 * field, one big Generate button, the result image, Save / Share.
 * Removed: the style picker (Sketch / Cartoon / Realistic — the
 * sketch style is now the fixed default), the photo picker
 * (restyling a photo needed a Gemini key and was a second,
 * confusing flow). Text-to-image stays free, no key.
 */
@Composable
fun CreateImageScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? Activity

    var prompt by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var generatedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var generatedMimeType by remember { mutableStateOf("image/png") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // V15 (fast images): elapsed-seconds counter while Flux works, so
    // the wait feels alive instead of frozen — and a one-tap retry when
    // the free tier is busy, instead of a dead end.
    var generatingSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isGenerating) {
        if (!isGenerating) return@LaunchedEffect
        generatingSeconds = 0
        while (true) {
            delay(1_000L)
            generatingSeconds++
        }
    }

    // V12: Pollinations Flux is the default — free, no key. Gemini is
    // used only when the user added a key AND picked Gemini in Settings.
    val useGemini = EnginePreferences.useGemini(context)
    val generatedBitmap = remember(generatedBytes) {
        generatedBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    fun generate() {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "Pehle describe karo — kaisi image chahiye?"
            return
        }
        isGenerating = true
        errorMessage = null
        statusMessage = null
        scope.launch(Dispatchers.IO) {
            // V20: sketch is the fixed style (was the default before).
            val fullPrompt = "Create a black-and-white pencil sketch style drawing of: $trimmed"
            val result = if (useGemini) {
                GeminiImageClient.generate(
                    apiKey = ApiKeyStore.getKey(context),
                    prompt = fullPrompt,
                    inputImageBytes = null,
                    inputMimeType = null
                )
            } else {
                PollinationsClient.generateImage(prompt = fullPrompt)
            }
            withContext(Dispatchers.Main) {
                isGenerating = false
                when (result) {
                    is ImageGenResult.Success -> {
                        generatedBytes = result.bytes
                        generatedMimeType = result.mimeType
                        // V22: maybe show an interstitial (every 3rd
                        // image, max once per 10 min, never during a
                        // voice conversation). Image generation is
                        // unlimited — no quota since the subscription
                        // was removed (user order 2026-11-24).
                        AdManager.onImageGenerated(activity)
                    }
                    is ImageGenResult.Error -> errorMessage = result.message
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
            Text(
                text = "Create Image",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = colors.textPrimary
            )
        }
        Text(
            text = "Describe it, Lia draws it — free, no key.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Prompt field
        NovaTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = "Describe the image… e.g. a cat sitting on a rooftop at sunset",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        NovaButton(
            text = if (isGenerating) "Generating…" else "Generate",
            onClick = { generate() },
            type = NovaButtonType.PRIMARY,
            enabled = !isGenerating,
            modifier = Modifier.fillMaxWidth()
        )

        if (isGenerating) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Lia is drawing your image… (${generatingSeconds}s)",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
            Text(
                text = "Flux free tier kabhi kabhi busy hota hai — 30 second tak wait karo.",
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 36.dp, top = 4.dp)
            )
        }

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.error
            )
            // V15: a busy free tier is a "try again in a moment" error,
            // not a dead end — one tap retries with the same prompt.
            val isBusy = message.contains("busy", ignoreCase = true) ||
                message.contains("Bohat sare log")
            if (isBusy) {
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(
                    text = "Dobara try karo",
                    onClick = { generate() },
                    type = NovaButtonType.SECONDARY,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Generated image, full width, with Save + Share
        generatedBitmap?.let { bitmap ->
            Spacer(modifier = Modifier.height(20.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Generated image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                NovaButton(
                    text = "Save",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val bytes = generatedBytes ?: return@launch
                            val saved = saveImageToGallery(context, bytes, generatedMimeType)
                            withContext(Dispatchers.Main) {
                                statusMessage = if (saved != null) {
                                    "Saved to your gallery as $saved."
                                } else {
                                    "Could not save the image. Please try again."
                                }
                            }
                        }
                    },
                    type = NovaButtonType.SECONDARY,
                    icon = Icons.Default.Save,
                    modifier = Modifier.weight(1f)
                )
                NovaButton(
                    text = "Share",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val bytes = generatedBytes ?: return@launch
                            val shared = shareImage(context, bytes, generatedMimeType)
                            withContext(Dispatchers.Main) {
                                if (!shared) {
                                    statusMessage = "Could not share the image. Please try again."
                                }
                            }
                        }
                    },
                    type = NovaButtonType.PRIMARY,
                    icon = Icons.Default.Share,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.success
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Save to the device gallery via MediaStore (no storage permission
 * needed on Android 10+; on older versions it lands in Pictures/Lia). */
private fun saveImageToGallery(context: Context, bytes: ByteArray, mimeType: String): String? {
    val extension = if (mimeType.contains("jpeg") || mimeType.contains("jpg")) "jpg" else "png"
    val fileName = "lia-image-${System.currentTimeMillis()}.$extension"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lia")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: run { resolver.delete(uri, null, null); return null }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        }
        fileName
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        null
    }
}

/** Share via the repo's existing FileProvider (cache-path "shared/" is
 * already declared in file_paths.xml). */
private fun shareImage(context: Context, bytes: ByteArray, mimeType: String): Boolean {
    return try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val extension = if (mimeType.contains("jpeg") || mimeType.contains("jpg")) "jpg" else "png"
        val file = File(dir, "lia-image-${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Image").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (_: Exception) {
        false
    }
}
