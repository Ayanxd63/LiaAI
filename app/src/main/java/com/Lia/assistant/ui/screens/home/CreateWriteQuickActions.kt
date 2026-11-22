package com.Lia.assistant.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.ui.theme.LocalNovaColors

/**
 * v10 — the two official LIA home quick actions that our build was
 * missing: "Create Image" ("Photos into sketches") and "Write"
 * ("Notes, code, content").
 *
 * HomeScreen.kt is intentionally NOT edited by this change. Wire this
 * row in by hand (see the insertion snippet in the v10 handoff):
 * place [CreateWriteQuickActions] on HomeScreen between the Talk/Type
 * pills and the "TRY SAYING" section. It lives in the same package as
 * HomeScreen, so no import is needed there. The "create_image" and
 * "write" routes are registered in NovaApp.
 */
@Composable
fun CreateWriteQuickActions(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CreateWriteActionCard(
            title = "Create Image",
            subtitle = "Photos into sketches",
            icon = Icons.Default.Image,
            onClick = { navController.navigate("create_image") },
            modifier = Modifier.weight(1f)
        )
        CreateWriteActionCard(
            title = "Write",
            subtitle = "Notes, code, content",
            icon = Icons.Default.Edit,
            onClick = { navController.navigate("write") },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CreateWriteActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceGlass)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = colors.textSecondary
            )
        }
    }
}
