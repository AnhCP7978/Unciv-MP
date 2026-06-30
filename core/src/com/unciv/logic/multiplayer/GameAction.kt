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
    @SerialName("attack")
    data class AttackAction(
        val unitId: Int,
        val targetTileX: Int,
        val targetTileY: Int,
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
    @SerialName("declareWar")
    data class DeclareWarAction(
        val otherCivName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("cityBombard")
    data class CityBombardAction(
        val cityId: String,
        val targetTileX: Int,
        val targetTileY: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("greatPerson")
    data class GreatPersonAction(
        val unitId: Int,
        val actionType: String, // "HurryResearch", "HurryPolicy", "HurryWonder", "HurryBuilding", "ConstructImprovement", "ConductTradeMission"
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("upgrade")
    data class UpgradeAction(
        val unitId: Int,
        val upgradeToUnitName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("promote")
    data class PromoteAction(
        val unitId: Int,
        val promotionName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("purchase")
    data class PurchaseAction(
        val constructionName: String,
        val cityId: String,
        val queuePosition: Int = -1,
        val stat: String,   // "Gold" or "Faith"
        val tileX: Int? = null,
        val tileY: Int? = null,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("fortify")
    data class FortifyAction(
        val unitId: Int,
        val fortifyType: String, // "Fortify" or "FortifyUntilHealed"
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

    @Serializable
    @SerialName("pillage")
    data class PillageAction(
        val unitId: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("adoptPolicy")
    data class AdoptPolicyAction(
        val policyName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("disbandUnit")
    data class DisbandUnitAction(
        val unitId: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("foundPantheon")
    data class FoundPantheonAction(
        val beliefName: String,
        override val civName: String,
    ) : GameAction()
}

/**
 * Wrapper sent over the wire so the recipient knows which game this belongs to.
 *
 * @param validated When `false`, this is a raw action from a non-host client.
 *                  When `true`, the host has validated this action — apply it.
 */
@Serializable
data class GameActionEnvelope(
    val gameId: String,
    val action: GameAction,
    val validated: Boolean = false,
)