package com.ipdial.ui.screens.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Display pressed digits
        Text(
            text = dtmfString,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = 16.dp),
            textAlign = TextAlign.Center
        )

        TextButton(onClick = onHide) {
            Text("Hide keypad")
        }
        val keys = listOf(
            "1","2","3","4","5","6","7","8","9","*","0","#"
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(if (isWide) 8.dp else 4.dp),
            modifier = Modifier.padding(horizontal = if (isLandscape) (configuration.screenWidthDp * 0.3f).dp else 32.dp)
        ) {
            keys.chunked(3).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (isWide) 8.dp else 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    row.forEach { digit ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .height(if (isWide) 72.dp else 52.dp)
                                .clip(RoundedCornerShape(50))
                                .clickableNoRipple {
                                    dtmfString += digit
                                    vm.dialPad(digit[0])
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    digit,
                                    style = if (isWide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
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
