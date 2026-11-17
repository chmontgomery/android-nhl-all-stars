package com.personal.hockey.ui.select

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.personal.hockey.settings.Level
import com.personal.hockey.settings.Settings
import com.personal.hockey.teams.Team

private val CardWidth = 164.dp
private val CardHeight = 84.dp

/** Pick "You" and "Opponent" from [teams] (any number of them), a level, and play. */
@Composable
fun TeamSelectScreen(
    teams: List<Team>,
    initial: Settings,
    onPlay: (homeId: String, awayId: String, level: Level) -> Unit,
) {
    /** The team after [id] in [teams], wrapping. */
    fun nextAfter(id: String): String {
        val i = teams.indexOfFirst { it.id == id }
        return teams[(i + 1) % teams.size].id
    }

    var homeId by remember {
        mutableStateOf(initial.homeId.takeIf { id -> teams.any { it.id == id } } ?: teams.firstOrNull()?.id.orEmpty())
    }
    var awayId by remember {
        val known = initial.awayId.takeIf { id -> teams.any { it.id == id } }
        val fallback = teams.getOrNull(1)?.id ?: teams.firstOrNull()?.id.orEmpty()
        val away = known ?: fallback
        mutableStateOf(if (away == homeId && teams.isNotEmpty()) nextAfter(homeId) else away)
    }
    var level by remember { mutableStateOf(initial.level) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NHL All Stars", color = MaterialTheme.colorScheme.tertiary,
                fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            LevelSwitch(level) { level = it }
            Box(Modifier.width(16.dp))
            PlayButton(enabled = teams.isNotEmpty()) { onPlay(homeId, awayId, level) }
        }
        TeamRow("You", "you", teams, homeId) { picked ->
            homeId = picked
            if (awayId == picked) awayId = nextAfter(picked)
        }
        TeamRow("Opponent", "opp", teams, awayId) { picked ->
            awayId = picked
            if (homeId == picked) homeId = nextAfter(picked)
        }
    }
}

@Composable
private fun TeamRow(label: String, tag: String, teams: List<Team>, selectedId: String, onPick: (String) -> Unit) {
    Column {
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        // Start with the remembered team in view, however long the list.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = teams.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
        LazyRow(
            Modifier.fillMaxWidth().testTag("$tag-row"),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        ) {
            items(teams, key = { it.id }) { team ->
                TeamCard(team, team.id == selectedId, Modifier.testTag("$tag-${team.id}")) { onPick(team.id) }
            }
        }
    }
}

@Composable
private fun TeamCard(team: Team, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val wheat = MaterialTheme.colorScheme.tertiary
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(CardWidth, CardHeight)
            .clip(shape)
            .background(Color(team.primary))
            .border(if (selected) BorderStroke(4.dp, wheat) else BorderStroke(1.dp, Color(0x55FFFFFF)), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrestMark(team, 48.dp)
            Text(
                team.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 3, modifier = Modifier.weight(1f),
            )
        }
        // In the corner above the crest, so selecting a card never narrows or covers its name.
        if (selected) {
            Text(
                "\u2713", color = wheat, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

@Composable
private fun LevelSwitch(level: Level, onPick: (Level) -> Unit) {
    val wheat = MaterialTheme.colorScheme.tertiary
    Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0x33FFFFFF))) {
        for (option in Level.entries) {
            val on = option == level
            Box(
                Modifier
                    .height(48.dp)
                    .width(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (on) wheat else Color.Transparent)
                    .testTag("level-${option.name}")
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onPick(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (option) {
                        Level.EASY -> "Easy"
                        Level.MEDIUM -> "Medium"
                        Level.HARD -> "Hard"
                    },
                    color = if (on) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PlayButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .width(150.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) MaterialTheme.colorScheme.secondary else Color(0x44FFFFFF))
            .testTag("play")
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("Play", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}
