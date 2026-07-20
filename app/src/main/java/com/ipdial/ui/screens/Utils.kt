package com.ipdial.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString

fun cleanUri(uri: String): String =
    uri.replace("<", "").replace(">", "").removePrefix("sip:")
        .substringBefore("@")
        .substringBefore(";")

fun cleanDisplayName(name: String, uri: String): String {
    val cleanedName = name.replace("\"", "").trim()
    if (cleanedName.isEmpty() || cleanedName.startsWith("sip:") || cleanedName.startsWith("<sip:")) {
        return cleanUri(uri)
    }
    // If it's "Name" <sip:123@domain>, extract Name
    if (cleanedName.contains("<sip:")) {
        return cleanedName.substringBefore("<").trim()
    }
    return cleanedName
}

fun Modifier.clickableWithRipple(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    this.clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(),
        onClick = onClick
    )
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

fun Char.uppercaseCharCompat(): String = this.uppercaseChar().toString()

/**
 * Format phone number for display.
 * BD style: "01728867896" → "01728-867896"
 * International: "+8801728867896" → "+88017-28867896"
 * Raw digits are preserved for actual calls.
 */
fun formatDisplayNumber(raw: String): String {
    if (raw.isEmpty()) return ""
    val digits = raw.filter { it.isDigit() || it == '+' }
    if (digits.isEmpty()) return ""
    if (digits.startsWith("+")) {
        val num = digits.removePrefix("+")
        if (num.isEmpty()) return "+"
        if (num.length <= 5) return "+$num"
        return "+${num.substring(0, 5)}-${num.substring(5)}"
    }
    if (digits.length <= 5) return digits
    return "${digits.substring(0, 5)}-${digits.substring(5)}"
}

@Composable
fun TelegramSupportCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().clickable {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/IPDial"))
            context.startActivity(intent)
        },
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Join our Telegram channel for support and updates!",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Telegram",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

class PhoneNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val formatted = formatDisplayNumber(raw)
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var formattedIdx = 0
                var rawIdx = 0
                while (rawIdx < offset && formattedIdx < formatted.length) {
                    if (formatted[formattedIdx] != '-') rawIdx++
                    formattedIdx++
                }
                return formattedIdx
            }

            override fun transformedToOriginal(offset: Int): Int {
                var rawIdx = 0
                for (i in 0 until offset.coerceAtMost(formatted.length)) {
                    if (formatted[i] != '-') rawIdx++
                }
                return rawIdx.coerceAtMost(raw.length)
            }
        }
        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}
