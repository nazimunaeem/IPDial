package com.ipdial.ui.screens.dialpad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ipdial.data.model.Contact
import com.ipdial.ui.components.ContactAvatar
import com.ipdial.ui.screens.clickableWithRipple

internal val T9_MAP = mapOf(
    'a' to '2', 'b' to '2', 'c' to '2',
    'd' to '3', 'e' to '3', 'f' to '3',
    'g' to '4', 'h' to '4', 'i' to '4',
    'j' to '5', 'k' to '5', 'l' to '5',
    'm' to '6', 'n' to '6', 'o' to '6',
    'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
    't' to '8', 'u' to '8', 'v' to '8',
    'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
)

@Composable
fun SuggestedContactRow(contact: Contact, onNumberClick: (String) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        ContactAvatar(
            name = contact.name,
            photoUri = contact.photoUri,
            size = 40.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            contact.numbers.forEach { number ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableWithRipple { onNumberClick(number) }
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
