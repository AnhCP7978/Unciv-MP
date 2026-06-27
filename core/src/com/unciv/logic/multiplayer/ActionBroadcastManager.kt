package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.multiplayer.chat.ChatWebSocket
import com.unciv.logic.multiplayer.chat.ChatStore
import com.unciv.logic.multiplayer.chat.Response
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.debug
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Orchestrates the 2-phase broadcast protocol for simultaneous multiplayer:
 *
 * **Non-host** players send actions via WebSocket → server relays to all
 * (including host) → host validates → host broadcasts acceptance/rejection.
 *
 * **Host** listens for all "end turn" signals → runs [SimultaneousTurnProcessor.processAdvance]
 * → uploads game file → broadcasts [Response.TurnAdvanced] so everyone downloads.
 */
class ActionBroadcastManager(private val worldScreen: WorldScreen) {

    private val gameId get() = worldScreen.gameInfo.gameId

    /** Host-only: tracks which players have ended their turn */
    private val playersFinishedTurn = mutableSetOf<String>()

    /** Prevents the local player from double-sending EndTurn */
    @Volatile
    var hasEndedTurn = false

    init {
        // Register as the action response handler in ChatStore
        ChatStore.onActionResponse = { response ->
            onActionResponse(response)
        }
    }

    /** Cleanup on WorldScreen dispose */
    fun dispose() {
        if (ChatStore.onActionResponse == this::onActionResponse)
            ChatStore.onActionResponse = null
    }

    // ──────────────────────────────────────
    //  Response dispatch
    // ──────────────────────────────────────

    private fun onActionResponse(response: Response) {
        Concurrency.run("HandleActionResponse") {
            when (response) {
                is Response.GameActionRelay -> {
                    applyRemoteAction(response.envelope)
                }
                is Response.GameActionRejected -> {
                    debug("Action %s rejected: %s", response.actionId, response.reason)
                }
                is Response.PlayerEndedTurn -> {
                    onRemotePlayerEndedTurn(response)
                }
                is Response.TurnAdvanced -> {
                    onTurnAdvanced(response)
                }
                else -> {}
            }
        }
    }

    // ──────────────────────────────────────
    //  Send (called when local player acts)
    // ──────────────────────────────────────

    @OptIn(ExperimentalUuidApi::class)
    /** Called when the local player performs an action that needs broadcast */
    fun sendMoveAction(unitId: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, civName: String) {
        val envelope = GameActionEnvelope(
            gameId = gameId,
            action = GameAction.MoveAction(
                unitId = unitId, from = TilePosition(fromX, fromY),
                to = TilePosition(toX, toY), civName = civName,
            ),
            actionId = Uuid.random().toString(),
            validated = isHost(),  // host validates immediately, non-host sends pending
        )
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(envelope)
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    fun sendFoundCityAction(unitId: Int, tileX: Int, tileY: Int, civName: String) {
        val envelope = GameActionEnvelope(
            gameId = gameId,
            action = GameAction.FoundCityAction(
                unitId = unitId, tileX = tileX, tileY = tileY, civName = civName,
            ),
            actionId = Uuid.random().toString(),
            validated = isHost(),
        )
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(envelope)
        )
    }

    /** Called when the local player clicks "End Turn" */
    fun sendEndTurn(civName: String) {
        if (hasEndedTurn) return  // prevent double-submit
        hasEndedTurn = true
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.EndTurn(gameId, civName)
        )
    }

    // ──────────────────────────────────────
    //  Apply remote actions (all clients)
    // ──────────────────────────────────────

    private fun applyRemoteAction(envelope: GameActionEnvelope) {
        when (val action = envelope.action) {
            is GameAction.MoveAction -> {
                if (!envelope.validated) {
                    if (isHost()) {
                        hostValidateMove(envelope)
                    }
                    return
                }
                debug("Applying remote move: unit %s -> (%s, %s)",
                    action.unitId, action.to.x, action.to.y)
                applyRemoteMove(action)
            }
            is GameAction.FoundCityAction -> {
                if (!envelope.validated) {
                    if (isHost()) hostValidateFoundCity(envelope)
                    return
                }
                debug("Applying remote found city: unit %s at (%s, %s)",
                    action.unitId, action.tileX, action.tileY)
                applyRemoteFoundCity(action)
            }
            else -> {}
        }
    }

    private fun applyRemoteMove(action: GameAction.MoveAction) {
        // Look up the unit — try destination tile first, then origin
        val tileMap = worldScreen.gameInfo.tileMap
        val destTile = tileMap[action.to.x, action.to.y]
        val originTile = tileMap[action.from.x, action.from.y]
        val unit = destTile.getUnits().firstOrNull { it.id == action.unitId }
            ?: originTile.getUnits().firstOrNull { it.id == action.unitId }
            ?: return

        unit.movement.moveToTile(destTile)
        Gdx.app.postRunnable {
            worldScreen.shouldUpdate = true
        }
    }

    /**
     * Host-only: validates a non-host player's move on the authoritative game state.
     * If valid, applies it locally and echoes the [GameActionEnvelope] with
     * [GameActionEnvelope.validated] = true so all clients apply it.
     */
    private fun hostValidateMove(envelope: GameActionEnvelope) {
        val action = envelope.action as? GameAction.MoveAction ?: return

        // Find the unit on the authoritative game state
        val tileMap = worldScreen.gameInfo.tileMap
        val originTile = tileMap[action.from.x, action.from.y]
        val unit = originTile.getUnits()
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName }
        if (unit == null || unit.currentMovement <= 0f) {
            debug("Host rejected move: unit %s invalid or has no movement", action.unitId)
            return
        }

        val targetTile = tileMap[action.to.x, action.to.y]
        if (!unit.movement.canMoveTo(targetTile)) {
            debug("Host rejected move: cannot move to (${action.to.x}, ${action.to.y})")
            return
        }

        // Apply the move on the authoritative state
        unit.movement.moveToTile(targetTile)

        // Echo back with validated=true so all clients apply it
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )

        Gdx.app.postRunnable {
            worldScreen.shouldUpdate = true
        }
    }

    // ──────────────────────────────────────
    //  Found City
    // ──────────────────────────────────────

    private fun applyRemoteFoundCity(action: GameAction.FoundCityAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val tile = tileMap[action.tileX, action.tileY]
        val unit = tile.getUnits().firstOrNull { it.id == action.unitId }
            ?: return
        unit.civ.addCity(tile.position, unit)
        Gdx.app.postRunnable {
            worldScreen.shouldUpdate = true
        }
    }

    private fun hostValidateFoundCity(envelope: GameActionEnvelope) {
        val action = envelope.action as? GameAction.FoundCityAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap
        val tile = tileMap[action.tileX, action.tileY]
        val unit = tile.getUnits().firstOrNull { it.id == action.unitId && it.civ.civName == action.civName }
        if (unit == null || !unit.hasMovement() || !tile.canBeSettled(unit.civ)) {
            debug("Host rejected found city: unit %s at (%s, %s)", action.unitId, action.tileX, action.tileY)
            return
        }
        // Apply on authoritative state
        unit.civ.addCity(tile.position, unit)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable {
            worldScreen.shouldUpdate = true
        }
    }

    // ──────────────────────────────────────
    //  Host-only: end-turn tracking
    // ──────────────────────────────────────

    fun isHost(): Boolean {
        val hostId = worldScreen.gameInfo.gameParameters.hostPlayerId
        return UncivGame.Current.settings.multiplayer.getUserId() == hostId
    }

    /**
     * Called on the **host** when they receive a [Response.PlayerEndedTurn] relayed
     * from the server. The server broadcasts this to ALL subscribers, including
     * the host. The host tracks who's done and triggers turn advancement when
     * all are finished.
     */
    private fun onRemotePlayerEndedTurn(response: Response.PlayerEndedTurn) {
        if (!isHost()) return  // non-hosts don't track this

        playersFinishedTurn.add(response.civName)

        val allHumans = worldScreen.gameInfo.civilizations
            .filter { it.isAlive() && it.playerType == PlayerType.Human }
            .map { it.civName }
            .toSet()

        debug("Player %s ended turn (%d/%d)", response.civName,
            playersFinishedTurn.size, allHumans.size)

        if (allHumans.all { it in playersFinishedTurn }) {
            hostAdvanceTurn()
        }
    }

    /** Host: run turn advancement, upload, and broadcast */
    private fun hostAdvanceTurn() {
        debug("All players finished — advancing turn")
        Concurrency.runOnNonDaemonThreadPool("SimultaneousTurnAdvance") {
            val gameClone = worldScreen.gameInfo.clone()
            gameClone.setTransients()

            SimultaneousTurnProcessor.processAdvance(gameClone)

            // Upload the new game state (existing pipeline — suspend function, OK in coroutine)
            UncivGame.Current.onlineMultiplayer.updateGame(gameClone)

            // Broadcast to all that the turn has advanced
            ChatWebSocket.requestMessageSend(
                com.unciv.logic.multiplayer.chat.Message.TurnAdvance(
                    gameId = gameId,
                    newTurns = gameClone.turns,
                )
            )

            // Load the new state locally (host)
            UncivGame.Current.loadGame(gameClone)
        }
    }

    /** Called when TurnAdvanced is received — non-host clients download the new game */
    private fun onTurnAdvanced(response: Response.TurnAdvanced) {
        if (isHost()) return  // host already loaded it
        debug("Turn advanced to %s, downloading new game state", response.newTurns)
        Concurrency.runOnNonDaemonThreadPool("SimultaneousDownloadGame") {
            UncivGame.Current.onlineMultiplayer.downloadGame(response.gameId)
        }
    }

    /** Reset the turn-end tracking (called when a new turn starts) */
    fun resetTurnTracking() {
        playersFinishedTurn.clear()
        hasEndedTurn = false
    }
}
