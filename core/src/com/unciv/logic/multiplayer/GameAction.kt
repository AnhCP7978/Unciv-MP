package com.unciv.logic.multiplayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable stand-in for tile coordinates (Vector2 is not serializable).
 */
@Serializable
data class TilePosition(val x: Int, val y: Int)

/**
 * Actions a player can perform in simultaneous multiplayer mode.
 * Sent from client → host via WebSocket, then relayed by host → all.
 */
@Serializable
sealed class GameAction {
    abstract val civName: String

    @Serializable
    @SerialName("move")
    data class MoveAction(
        val unitId: Int,
        val from: TilePosition,
        val to: TilePosition,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("foundCity")
    data class FoundCityAction(
        val unitId: Int,
        val tileX: Int,
        val tileY: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("endTurn")
    data class EndTurnAction(
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("turnAdvanced")
    data class TurnAdvanced(
        val newTurns: Int,
        val gameId: String,
        override val civName: String = "",
    ) : GameAction()
}

/**
 * Wrapper sent over the wire so the recipient knows which game this belongs to
 * and can deduplicate by [actionId].
 *
 * @param validated When `false`, this is a raw action from a non-host client.
 *                  When `true`, the host has validated this action — apply it.
 */
@Serializable
data class GameActionEnvelope(
    val gameId: String,
    val action: GameAction,
    val actionId: String,   // UUID for idempotency
    val validated: Boolean = false,
)
