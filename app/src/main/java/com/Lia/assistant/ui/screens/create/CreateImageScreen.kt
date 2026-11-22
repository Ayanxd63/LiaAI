package com.Lia.assistant.ui.screens.create

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.EnginePreferences
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.GeminiImageClient
import com.Lia.assistant.voice.ImageGenResult
import com.Lia.assistant.voice.PollinationsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Image styles offered on the Create Image screen. Sketch is the default,
 * matching the official "Photos into sketches" wording. */
enum class ImageStyle(val label: String, val instruction: String) {
    SKETCH("Sketch", "black-and-white pencil sketch style drawing"),
    CARTOON("Cartoon", "bright colourful cartoon style illustration"),
    REALISTIC("Realistic", "photorealistic style image")
}

@Composable
fun CreateImageScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(ImageStyle.SKETCH) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pickedMimeType by remember { mutableStateOf("image/jpeg") }
    var isGenerating by remember { mutableStateOf(false) }
    var generatedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var generatedMimeType by remember { mutableStateOf("image/png") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Photo picker — the standard ActivityResultContracts.GetContent route
    // (same rememberLauncherForActivityResult pattern PermissionsScreen
    // already uses). No new file-sharing mechanism is invented.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                withContext(Dispatchers.Main) {
                    if (bytes != null && bytes.isNotEmpty()) {
                        pickedUri = uri
                        pickedBytes = bytes
                        pickedMimeType = mime
                        errorMessage = null
                    } else {
                        errorMessage = "That photo could not be read. Please pick another one."
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "That photo could not be read. Please pick another one."
                }
            }
        }
    }

    // V12: Pollinations Flux is the default — free, no key. Gemini is
    // used only when the user added a key AND picked Gemini in Settings
    // (it is the only engine that can restyle a picked photo).
    val useGemini = EnginePreferences.useGemini(context)
    val pickedBitmap = remember(pickedBytes) {
        pickedBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val generatedBitmap = remember(generatedBytes) {
        generatedBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    fun generate() {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() && pickedBytes == null) {
            errorMessage = "Please describe the image, or pick a photo to restyle."
            return
        }
        // Pollinations is text-to-image only: restyling a picked photo
        // needs Gemini. Say so plainly instead of failing silently.
        if (pickedBytes != null && !useGemini) {
            errorMessage = "Photo restyle needs Gemini — add a key in Settings and pick the " +
                "Gemini engine, or describe an image from text (free, no key)."
            return
        }
        isGenerating = true
        errorMessage = null
        statusMessage = null
        scope.launch(Dispatchers.IO) {
            val fullPrompt = if (pickedBytes != null) {
                "Turn this photo into a ${style.instruction}. " +
                    if (trimmed.isNotEmpty()) "Keep the subject and composition, but follow this: $trimmed"
                    else "Keep the subject and composition of the photo."
            } else {
                "Create a ${style.instruction} of: $trimmed"
            }
            val result = if (useGemini) {
                GeminiImageClient.generate(
                    apiKey = ApiKeyStore.getKey(context),
                    prompt = fullPrompt,
                    inputImageBytes = pickedBytes,
                    inputMimeType = pickedMimeType
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
            text = "Photos into sketches",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // V12: free engine note — no key is needed for text-to-image.
        if (!useGemini) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceGlass)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Free image — no key needed.",
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Powered by Pollinations Flux. For sharper results or photo restyle, " +
                        "add a Gemini key in Settings and pick the Gemini engine.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Prompt field
        NovaTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = "Describe the image… e.g. a cat sitting on a rooftop at sunset",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Style choice
        Text(
            text = "STYLE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.accent
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImageStyle.values().forEach { option ->
                val selected = style == option
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.accent else colors.surfaceGlass)
                        .clickable { style = option }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) colors.onAccent else colors.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Optional photo to restyle
        NovaButton(
            text = if (pickedUri == null) "Pick a photo (optional)" else "Change photo",
            onClick = { pickPhoto.launch("image/*") },
            type = NovaButtonType.SECONDARY,
            icon = Icons.Default.PhotoLibrary
        )
        if (pickedUri != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Photo picked — it will be turned into a ${style.label.lowercase()} drawing.",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        }
        pickedBitmap?.let { bitmap ->
            Spacer(modifier = Modifier.height(12.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Picked photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        NovaButton(
            text = if (isGenerating) "Creating…" else "Create Image",
            onClick = { generate() },
            type = NovaButtonType.PRIMARY,
            icon = Icons.Default.Image,
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
                    text = "Lia is drawing your image…",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.error
            )
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
 * already declared in file_paths.xml) — same pattern as ResultToolbar. */
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
