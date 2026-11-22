package com.Lia.assistant.ui.screens.create

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.Lia.assistant.data.HuggingFaceStore
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VideoGenResult
import com.Lia.assistant.voice.VideoSpace
import com.Lia.assistant.voice.VideoSpacesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v11 — Create Video: free AI video from public Hugging Face Spaces.
 *
 * The video is made on a public CogVideoX Space (NOT on this phone and
 * NOT through any paid API). Honest limits, shown on the screen itself:
 * free Spaces are slow (queue + a few minutes), clips are ~6 seconds,
 * and a free Hugging Face account gets roughly 1 short video a day.
 *
 * A free Hugging Face token is REQUIRED — verified live 2026-11-22:
 * without a token the ZeroGPU scheduler rejects the job outright
 * ("You have exceeded your ZeroGPU quota … Authenticate with a Hugging
 * Face token for more quota"). The token costs nothing and needs no
 * payment; the user pastes their own in Settings — Hugging Face, and it
 * is stored only on this phone (see HuggingFaceStore). Without it, the
 * screen explains why instead of failing mysteriously.
 *
 * Playback uses the framework VideoView (no new dependency); Save uses
 * MediaStore.Video and Share uses the repo's existing FileProvider —
 * both mirror CreateImageScreen's gallery/share flow.
 */
@Composable
fun CreateVideoScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var space by remember { mutableStateOf<VideoSpace>(VideoSpacesClient.ALL_SPACES.first()) }
    var isGenerating by remember { mutableStateOf(false) }
    var videoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var videoFile by remember { mutableStateOf<File?>(null) }
    // Plain holder (not Compose state): the VideoView is created by the
    // AndroidView factory, and writing state from there would recompose.
    val videoViewHolder = remember { object { var view: VideoView? = null } }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val cancelRequested = remember { AtomicBoolean(false) }
    val hasToken = HuggingFaceStore.hasToken(context)

    // Stop playback when the screen goes away.
    DisposableEffect(Unit) {
        onDispose {
            try {
                videoViewHolder.view?.stopPlayback()
            } catch (_: Exception) {
                // Already released — nothing to do.
            }
        }
    }

    fun generate() {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            errorMessage = "Please describe the video first."
            return
        }
        if (!HuggingFaceStore.hasToken(context)) {
            errorMessage = "A free Hugging Face token is needed for video. " +
                "Add one in Settings — Hugging Face."
            return
        }
        cancelRequested.set(false)
        isGenerating = true
        errorMessage = null
        statusMessage = "Starting…"
        videoBytes = null
        videoFile = null
        val token = HuggingFaceStore.getToken(context)
        val chosenSpace = space
        scope.launch(Dispatchers.IO) {
            val result = VideoSpacesClient.generate(
                space = chosenSpace,
                prompt = trimmed,
                token = token,
                onStatus = { message ->
                    scope.launch(Dispatchers.Main) { statusMessage = message }
                },
                isCancelled = { cancelRequested.get() }
            )
            withContext(Dispatchers.Main) {
                isGenerating = false
                when (result) {
                    is VideoGenResult.Success -> {
                        videoBytes = result.bytes
                        statusMessage = "Video ready! (${result.bytes.size / 1024} KB)"
                        errorMessage = null
                        // VideoView needs a file, so write the bytes to cache.
                        scope.launch(Dispatchers.IO) {
                            val file = try {
                                val f = File(
                                    context.cacheDir,
                                    "lia-video-${System.currentTimeMillis()}.mp4"
                                )
                                FileOutputStream(f).use { it.write(result.bytes) }
                                f
                            } catch (_: Exception) {
                                null
                            }
                            withContext(Dispatchers.Main) {
                                if (file != null) {
                                    videoFile = file
                                } else {
                                    errorMessage = "The video was made but " +
                                        "couldn't be played. Try saving it instead."
                                }
                            }
                        }
                    }
                    is VideoGenResult.Error -> {
                        errorMessage = result.message
                        statusMessage = null
                    }
                    VideoGenResult.Cancelled -> {
                        statusMessage = "Cancelled."
                        errorMessage = null
                    }
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
                text = "Create Video",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = colors.textPrimary
            )
        }
        Text(
            text = "Free AI video — 6 second clips",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 56.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Honest limits, always visible.
        Text(
            text = "Made on free public Hugging Face Spaces. They are slow — " +
                "queue + a few minutes per video — and a free account gets " +
                "roughly 1 short video a day. Clips are about 6 seconds.",
            fontSize = 13.sp,
            color = colors.textSecondary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Missing-token card — honest, with a button to Settings.
        if (!hasToken) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceGlass)
                    .padding(16.dp)
            ) {
                Text(
                    text = "A free Hugging Face token is needed for video.",
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Public video Spaces reject jobs without one — " +
                        "that is Hugging Face's own rule, not Lia's. The token " +
                        "is free and needs no payment: make one at " +
                        "huggingface.co → Settings → Access Tokens → New token " +
                        "(read), then paste it in Settings — Hugging Face. " +
                        "It stays only on this phone.",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                NovaButton(
                    text = "Open Settings",
                    onClick = { navController.navigate("huggingface_settings") },
                    type = NovaButtonType.PRIMARY
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Prompt field
        NovaTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = "Describe the video… e.g. a grey cat chasing a brown dog around a sunny garden",
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Model choice
        Text(
            text = "MODEL",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = colors.accent
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoSpacesClient.ALL_SPACES.forEach { option ->
                val selected = space.id == option.id
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) colors.accent else colors.surfaceGlass)
                        .clickable { space = option }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) colors.onAccent else colors.textPrimary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = space.subtitle,
            fontSize = 13.sp,
            color = colors.textSecondary
        )

        Spacer(modifier = Modifier.height(20.dp))

        NovaButton(
            text = if (isGenerating) "Creating…" else "Create Video",
            onClick = { generate() },
            type = NovaButtonType.PRIMARY,
            icon = Icons.Default.Movie,
            enabled = !isGenerating && hasToken,
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
                    text = statusMessage ?: "Working…",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            NovaButton(
                text = "Cancel",
                onClick = { cancelRequested.set(true) },
                type = NovaButtonType.SECONDARY
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Keep the app open while the video is made — " +
                    "closing it cancels the job.",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        }

        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = colors.error
            )
        }

        // Generated video, with playback + Save + Share
        videoFile?.let { file ->
            Spacer(modifier = Modifier.height(20.dp))
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val mediaController = MediaController(ctx)
                        mediaController.setAnchorView(this)
                        setMediaController(mediaController)
                        videoViewHolder.view = this
                    }
                },
                update = { view ->
                    if (view.tag != file.absolutePath) {
                        view.tag = file.absolutePath
                        view.setVideoPath(file.absolutePath)
                        view.requestFocus()
                        view.start()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceGlass)
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
                            val bytes = videoBytes ?: return@launch
                            val saved = saveVideoToGallery(context, bytes)
                            withContext(Dispatchers.Main) {
                                statusMessage = if (saved != null) {
                                    "Saved to your gallery as $saved."
                                } else {
                                    "Could not save the video. Please try again."
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
                            val bytes = videoBytes ?: return@launch
                            val shared = shareVideo(context, bytes)
                            withContext(Dispatchers.Main) {
                                if (!shared) {
                                    statusMessage = "Could not share the video. Please try again."
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
            if (!isGenerating) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = colors.success
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Save to the device gallery via MediaStore (no storage permission
 * needed on Android 10+; on older versions it lands in Movies/Lia). */
private fun saveVideoToGallery(context: Context, bytes: ByteArray): String? {
    val fileName = "lia-video-${System.currentTimeMillis()}.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Lia")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: run { resolver.delete(uri, null, null); return null }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
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
 * already declared in file_paths.xml) — same pattern as CreateImageScreen. */
private fun shareVideo(context: Context, bytes: ByteArray): Boolean {
    return try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "lia-video-${System.currentTimeMillis()}.mp4")
        file.writeBytes(bytes)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Video").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (_: Exception) {
        false
    }
}
