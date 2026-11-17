package com.personal.hockey.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.hockey.ui.CrestMark
import com.personal.hockey.teams.Team
import kotlinx.coroutines.delay

/** A child still mashing buttons from the game must not skip the score: taps wait this long. */
private const val TAP_GUARD_MS = 800L

@Composable
fun ResultScreen(
    home: Team,
    away: Team,
    homeScore: Int,
    awayScore: Int,
    onRematch: () -> Unit,
    onChangeTeams: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(TAP_GUARD_MS); armed = true }
    val headline = when {
        homeScore > awayScore -> "${home.name} win!"
        awayScore > homeScore -> "${away.name} win!"
        else -> "Tie game"
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text("Final", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Side(home, homeScore, "home")
            Side(away, awayScore, "away")
        }
        Text(
            headline, color = MaterialTheme.colorScheme.tertiary, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.testTag("headline"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton("Rematch", MaterialTheme.colorScheme.secondary) { if (armed) onRematch() }
            BigButton("Change teams", Color(0x33FFFFFF)) { if (armed) onChangeTeams() }
        }
    }
}

@Composable
private fun Side(team: Team, score: Int, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color(team.primary)), contentAlignment = Alignment.Center) {
            CrestMark(team, 48.dp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(team.name, color = Color(team.primary).lighten(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("$score", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("$tag-score"))
        }
    }
}

/** Team primaries can be near-black; blend toward white so names read on the dark background. */
private fun Color.lighten(): Color = Color(
    red = red + (1f - red) * 0.6f, green = green + (1f - green) * 0.6f, blue = blue + (1f - blue) * 0.6f,
)

@Composable
private fun BigButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .width(190.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(color)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}
