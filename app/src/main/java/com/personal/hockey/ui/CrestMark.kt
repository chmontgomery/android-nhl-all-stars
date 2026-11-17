package com.personal.hockey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.personal.hockey.render.Crests
import com.personal.hockey.teams.Team

/** A team crest for use on a card or background in the team's primary colour: light trim over the accent. */
@Composable
fun CrestMark(team: Team, size: Dp) {
    Canvas(Modifier.size(size)) {
        Crests.draw(this, team.crest, this.size.width / 2f, this.size.height / 2f, this.size.minDimension,
            Color(team.trim), Color(team.secondary))
    }
}
