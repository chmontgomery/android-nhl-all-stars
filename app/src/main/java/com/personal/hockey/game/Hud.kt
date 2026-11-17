package com.personal.hockey.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Top-left scoreboard: `HOM 1 | 0 AWY | 2nd 14:32` (three-letter codes), the team chips in team colours. */
@Composable
fun Hud(state: HudState, homeColor: Color, awayColor: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Chip(homeColor) {
            Text(state.homeCode, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                state.homeScore.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.padding(start = 8.dp).testTag("hud-home-score"),
            )
        }
        Chip(awayColor) {
            Text(
                state.awayScore.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.padding(end = 8.dp).testTag("hud-away-score"),
            )
            Text(state.awayCode, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Chip(Color(0xAA000000)) {
            Text(state.periodLabel, color = Color.White, fontSize = 16.sp)
            Text(
                state.clock, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.padding(start = 8.dp).testTag("hud-clock"),
            )
        }
    }
}

@Composable
private fun Chip(color: Color, content: @Composable () -> Unit) {
    Row(
        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
