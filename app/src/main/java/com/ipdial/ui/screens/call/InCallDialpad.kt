package com.ipdial.ui.screens.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ipdial.ui.SipViewModel
import com.ipdial.ui.screens.clickableNoRipple

@Composable
fun InCallDialpad(vm: SipViewModel, onHide: () -> Unit) {
    var dtmfString by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp > 600
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val keyHeight = if (isWide) 68.dp else if (isLandscape) 44.dp else 52.dp
    val spacing = if (isWide) 8.dp else if (isLandscape) 6.dp else 4.dp
    val keyStyle = when {
        isWide -> MaterialTheme.typography.displaySmall
        isLandscape -> MaterialTheme.typography.headlineSmall
        else -> MaterialTheme.typography.headlineSmall
    }
    val displayTextStyle = if (isLandscape) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium

    // On landscape the keypad lives in a (possibly narrow) side pane, so cap its
    // width and keep it dense enough to fit the reduced available height.
    val keypadWidth = 420.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Display pressed digits with back button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = keypadWidth)
                .fillMaxWidth()
                .height(if (isLandscape) 40.dp else 48.dp)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
        ) {
            Text(
                text = dtmfString,
                style = displayTextStyle,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            if (dtmfString.isNotEmpty()) {
                IconButton(onClick = { dtmfString = dtmfString.dropLast(1) }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace", tint = MaterialTheme.colorScheme.onBackground)
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        TextButton(onClick = onHide) {
            Text("Hide keypad")
        }

        val keys = listOf(
            "1","2","3","4","5","6","7","8","9","*","0","#"
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier
                .widthIn(max = keypadWidth)
                .fillMaxWidth()
                .padding(horizontal = if (isLandscape) 12.dp else 20.dp)
        ) {
            keys.chunked(3).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    row.forEach { digit ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .height(keyHeight)
                                .clip(RoundedCornerShape(50))
                                .clickableNoRipple {
                                    dtmfString += digit
                                    vm.dialPad(digit[0])
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    digit,
                                    style = keyStyle,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}