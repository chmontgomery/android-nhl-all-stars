package com.personal.hockey.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun PauseOverlay(
    soundOn: Boolean,
    vibrationOn: Boolean,
    onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onResume: () -> Unit,
    onQuit: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        var confirming by rememberSaveable { mutableStateOf(false) }
        // Scrolls when large text makes it taller than a short landscape screen.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = androidx.compose.material3.MaterialTheme.typography.headlineMedium
            // Fixed colours: the theme's own button colours are too dark to read on the dark scrim.
            val solid = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF14121A))
            val outlined = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            val rim = BorderStroke(2.dp, Color.White)
            if (confirming) {
                Text("Quit game?", color = Color.White, style = title)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Button(onClick = { confirming = false }, colors = solid) { Text("No") }
                    OutlinedButton(onClick = onQuit, colors = outlined, border = rim) { Text("Yes") }
                }
            } else {
                // Resume on top and Quit at the bottom, with the switches between: no near-miss quits.
                Text("Paused", color = Color.White, style = title)
                Button(onClick = onResume, colors = solid) { Text("Resume") }
                SwitchRow("Sound", soundOn, onSoundChange)
                SwitchRow("Vibration", vibrationOn, onVibrationChange)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { confirming = true }, colors = outlined, border = rim) { Text("Quit") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.width(220.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White)
        Switch(checked = checked, onCheckedChange = null)
    }
}
