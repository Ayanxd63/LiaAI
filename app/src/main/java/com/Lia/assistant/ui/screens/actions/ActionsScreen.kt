package com.Lia.assistant.ui.screens.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.action.ActionCatalogue
import com.Lia.assistant.action.CatalogueAction
import com.Lia.assistant.action.DeviceActions
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaInfoCard
import com.Lia.assistant.ui.components.NovaSectionHeader
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V16 (P1-3) — the Actions screen: an honest catalogue of everything
 * Lia can actually do on this phone, taken from the real
 * [DeviceCommand] list — nothing invented, nothing faked.
 *
 * V16 (APK-delta addition): the rows are DATA, not code — they come
 * from `assets/lia_actions.json` via [ActionCatalogue]. Adding a new
 * action is a JSON edit; the driver ([ActionCatalogue.toDeviceCommand])
 * maps each catalogue `command` to its real constructor. If the file
 * cannot be read, a tiny built-in fallback is shown and the screen
 * says so plainly instead of going blank.
 *
 * Every row is tappable: actions that need no input run at once and
 * show Lia's real reply; actions that need a name, query or number
 * ask for it first in a small dialog, then run. When something needs a
 * permission, the result says so plainly instead of pretending it
 * worked.
 */
@Composable
fun ActionsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resultText by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<CatalogueAction?>(null) }
    var pendingInput by remember { mutableStateOf("") }

    // Catalogue loads once from assets (IO thread); until it arrives
    // the screen shows a short loading line, never a blank page.
    var catalogue by remember { mutableStateOf<ActionCatalogue.LoadResult?>(null) }
    LaunchedEffect(Unit) {
        catalogue = withContext(Dispatchers.IO) { ActionCatalogue.load(context) }
    }

    fun runAction(action: CatalogueAction, input: String) {
        scope.launch(Dispatchers.IO) {
            val command = ActionCatalogue.toDeviceCommand(action, input)
            val reply = if (command == null) {
                "Ye action is version mein nahi hai."
            } else {
                try {
                    DeviceActions.execute(context, command)
                } catch (_: Exception) {
                    "Ye kaam nahi ho saka — dobara try karo."
                }
            }
            withContext(Dispatchers.Main) { resultText = reply }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 100.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                text = "Actions",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        }

        Text(
            text = "Jo kuch Lia phone par kar sakti hai — sab kuch yahan hai. " +
                "Tap karo to chalega; jo permission mangta hai, wo pehle batayega.",
            fontSize = 14.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        resultText?.let { result ->
            NovaInfoCard(
                title = "Lia ka jawab",
                body = result,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        val loaded = catalogue
        if (loaded == null) {
            Text(
                text = "Actions load ho rahi hain…",
                fontSize = 14.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            if (loaded.fromFallback) {
                NovaInfoCard(
                    title = "Catalogue load nahi hui",
                    body = "Actions ki list nahi mili — sirf basic actions " +
                        "dikha rahi hun. App reinstall karne se theek ho jayega.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            for (section in loaded.sections) {
                NovaSectionHeader(title = section.title)
                ActionGroup(rows = section.actions, onTap = { action ->
                    if (action.inputHint != null) {
                        pendingInput = ""
                        pendingAction = action
                    } else {
                        runAction(action, "")
                    }
                })
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Input dialog for actions that need a name, query or number.
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(text = action.name, color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        text = action.example,
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaTextField(
                        value = pendingInput,
                        onValueChange = { pendingInput = it },
                        placeholder = action.inputHint ?: ""
                    )
                }
            },
            confirmButton = {
                NovaButton(
                    text = "Run",
                    onClick = {
                        val input = pendingInput
                        pendingAction = null
                        runAction(action, input)
                    }
                )
            },
            dismissButton = {
                NovaButton(
                    text = "Cancel",
                    onClick = { pendingAction = null },
                    type = NovaButtonType.TEXT
                )
            },
            containerColor = colors.surfaceRaised
        )
    }
}

@Composable
private fun ActionGroup(
    rows: List<CatalogueAction>,
    onTap: (CatalogueAction) -> Unit
) {
    val colors = LocalNovaColors.current
    NovaGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (action in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = action.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = colors.textPrimary
                        )
                        Text(
                            text = action.example,
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                        action.needs?.let { need ->
                            Text(
                                text = need,
                                fontSize = 12.sp,
                                color = colors.warning
                            )
                        }
                    }
                    NovaButton(
                        text = action.button,
                        onClick = { onTap(action) },
                        type = NovaButtonType.SECONDARY
                    )
                }
            }
        }
    }
}
