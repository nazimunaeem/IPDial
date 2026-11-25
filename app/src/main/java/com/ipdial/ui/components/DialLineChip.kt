package com.ipdial.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.WifiCalling3
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Compact pill shown above the keypad that tells the user which line (SIM or IP account)
 * the next outgoing call will use. Tapping it opens the line picker.
 */
@Composable
fun DialLineChip(
    label: String,
    subtitle: String = "",
    isSim: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.testTag("dial_line_chip")
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isSim) Icons.Default.SimCard else Icons.Default.WifiCalling3,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (subtitle.isBlank()) label else "$label · $subtitle",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Change line",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
