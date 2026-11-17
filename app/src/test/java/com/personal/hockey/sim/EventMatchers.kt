package com.personal.hockey.sim

/** Event matchers that ignore the skater ids the events carry. */
fun List<SimEvent>.hasGoal(side: Side): Boolean = any { it is SimEvent.Goal && it.scorer == side }
fun List<SimEvent>.hasPickup(side: Side): Boolean = any { it is SimEvent.Pickup && it.side == side }
fun List<SimEvent>.hasPass(side: Side): Boolean = any { it is SimEvent.Pass && it.side == side }
