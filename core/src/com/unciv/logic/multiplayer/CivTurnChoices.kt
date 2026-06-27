package com.unciv.logic.multiplayer

import kotlinx.serialization.Serializable

/**
 * Payload sent by a non-host player with their EndTurn signal.
 * The host applies these choices to the authoritative game state before advancing the turn.
 */
@Serializable
data class CivTurnChoices(
    val civName: String,
    /** cityId -> construction name (null = nothing queued) */
    val cityConstructions: Map<String, String?>,
    /** current tech being researched, null means none */
    val currentTechResearch: String?,
)
