package com.unciv.logic.multiplayer

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.TurnManager

/**
 * Handles turn advancement for simultaneous multiplayer mode.
 * Called by the HOST only, when all human players have ended their turn.
 *
 * This is intentionally separate from [GameInfo.nextTurn] which is designed
 * for sequential single-currentPlayer games. This processor treats all
 * human players equally — none is "the current player".
 */
object SimultaneousTurnProcessor {

    /**
     *  Advances the game turn in simultaneous mode.
     *
     *  1. End turn for all alive human players (collect gold, science, etc.)
     *  2. Process all alive AI players (start → automate → end)
     *  3. Increment world turn counter
     *  4. Start the new turn for all alive human players
     *  5. Reset the finished-turn tracking
     */
    fun processAdvance(gameInfo: GameInfo) {
        val isSimultaneous = gameInfo.gameParameters.isSimultaneousGame
        if (!isSimultaneous) return  // safety guard, should never be called in sequential

        // 1. End turn for all alive human players
        for (civ in gameInfo.civilizations.filter { it.isAlive() && it.playerType == PlayerType.Human }) {
            TurnManager(civ).endTurn()
        }

        // 2. Process all alive AI players
        for (civ in gameInfo.civilizations.filter { it.isAlive() && it.playerType == PlayerType.AI }) {
            TurnManager(civ).startTurn()
            TurnManager(civ).automateTurn()
            TurnManager(civ).endTurn()
        }

        // 3. Increment world turn
        gameInfo.turns++

        // 4. Start the new turn for all alive human players
        for (civ in gameInfo.civilizations.filter { it.isAlive() && it.playerType == PlayerType.Human }) {
            TurnManager(civ).startTurn()
        }

        // 5. Reset turn-end tracking
        gameInfo.simultaneousTurnState.reset()
        gameInfo.currentTurnStartTime = System.currentTimeMillis()
    }
}
