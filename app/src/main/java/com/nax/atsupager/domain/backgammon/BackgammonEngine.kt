package com.nax.atsupager.domain.backgammon

import kotlin.random.Random

object BackgammonEngine {
    const val OFF_BOARD = 25

    data class GameState(
        val board: IntArray = IntArray(26), 
        val dice: List<Int> = emptyList(),
        val usedDice: List<Boolean> = emptyList(),
        val currentPlayer: String = "white",
        val headMovesCount: Int = 0,
        val moveIndex: Int = 0,
        val isFirstTurn: Boolean = true,
        val winner: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GameState) return false
            if (!board.contentEquals(other.board)) return false
            if (dice != other.dice) return false
            if (usedDice != other.usedDice) return false
            if (currentPlayer != other.currentPlayer) return false
            if (headMovesCount != other.headMovesCount) return false
            if (moveIndex != other.moveIndex) return false
            if (isFirstTurn != other.isFirstTurn) return false
            if (winner != other.winner) return false
            return true
        }

        override fun hashCode(): Int {
            var result = board.contentHashCode()
            result = 31 * result + dice.hashCode()
            result = 31 * result + usedDice.hashCode()
            result = 31 * result + currentPlayer.hashCode()
            result = 31 * result + headMovesCount
            result = 31 * result + moveIndex
            result = 31 * result + isFirstTurn.hashCode()
            result = 31 * result + (winner?.hashCode() ?: 0)
            return result
        }
    }

    data class MoveOption(
        val to: Int,
        val diceUsed: List<Int>,
        val isComposite: Boolean = false
    )

    fun createInitialState(): GameState {
        val board = IntArray(26)
        board[1] = 15 
        board[13] = -15
        return GameState(board = board)
    }

    fun rollDice(): List<Int> {
        val d1 = Random.nextInt(1, 7)
        val d2 = Random.nextInt(1, 7)
        return if (d1 == d2) listOf(d1, d1, d1, d1) else listOf(d1, d2)
    }

    /**
     * Вызывать сразу после броска кубиков для проверки возможности ходов.
     */
    fun onDiceRolled(state: GameState, rolledDice: List<Int>): GameState {
        val newState = state.copy(dice = rolledDice, usedDice = List(rolledDice.size) { false })
        if (!hasAnyPossibleMoves(newState)) {
            // Если ходов нет, сразу переключаем игрока
            return switchTurn(newState)
        }
        return newState.copy(moveIndex = state.moveIndex + 1)
    }

    private fun switchTurn(state: GameState): GameState {
        val isWhite = state.currentPlayer == "white"
        return state.copy(
            currentPlayer = if (isWhite) "black" else "white",
            dice = emptyList(),
            usedDice = emptyList(),
            headMovesCount = 0,
            isFirstTurn = if (!isWhite) false else state.isFirstTurn,
            moveIndex = state.moveIndex + 1
        )
    }

    fun stateToString(state: GameState): String {
        return "${state.board.joinToString(",")}|${state.dice.joinToString(",")}|${state.usedDice.joinToString(",") { if (it) "1" else "0" }}|${state.currentPlayer}|${state.headMovesCount}|${state.moveIndex}|${if (state.isFirstTurn) 1 else 0}|${state.winner ?: ""}"
    }

    fun stringToState(str: String): GameState {
        val parts = str.split("|")
        if (parts.size < 7) return createInitialState()
        val boardStr = parts[0].split(",")
        val board = IntArray(26) { i -> if (i < boardStr.size) boardStr[i].toIntOrNull() ?: 0 else 0 }
        val dice = if (parts[1].isEmpty()) emptyList() else parts[1].split(",").mapNotNull { it.toIntOrNull() }
        val usedDiceStr = if (parts[2].isEmpty()) emptyList() else parts[2].split(",")
        val usedDice = List(dice.size) { i -> usedDiceStr.getOrNull(i) == "1" }
        
        return GameState(
            board = board,
            dice = dice,
            usedDice = usedDice,
            currentPlayer = parts[3],
            headMovesCount = parts[4].toIntOrNull() ?: 0,
            moveIndex = parts[5].toIntOrNull() ?: 0,
            isFirstTurn = parts[6] == "1",
            winner = if (parts.size > 7 && parts[7].isNotEmpty()) parts[7] else null
        )
    }

    fun getPossibleMoveOptions(state: GameState, from: Int): List<MoveOption> {
        if (state.dice.isEmpty() || state.winner != null || from < 1 || from > 24) return emptyList()
        
        val options = mutableListOf<MoveOption>()
        val availableIndices = state.usedDice.indices.filter { !state.usedDice[it] }

        val checkedDiceValues = mutableSetOf<Int>()
        availableIndices.forEach { idx ->
            val die = state.dice[idx]
            if (die !in checkedDiceValues) {
                val to = getTargetPosition(state, from, die)
                if (canMove(state, from, to, specificDie = die)) {
                    options.add(MoveOption(to, listOf(die), isComposite = false))
                }
                checkedDiceValues.add(die)
            }
        }

        if (availableIndices.size >= 2) {
            val idx1 = availableIndices[0]
            val idx2 = availableIndices[1]
            val d1 = state.dice[idx1]
            val d2 = state.dice[idx2]

            val to1 = getTargetPosition(state, from, d1)
            if (to1 != OFF_BOARD && canMove(state, from, to1, specificDie = d1)) {
                val stateAfter1 = applyMoveLocally(state, from, to1, idx1)
                val finalTo = getTargetPosition(stateAfter1, to1, d2)
                if (canMove(stateAfter1, to1, finalTo, specificDie = d2)) {
                    options.add(MoveOption(finalTo, listOf(d1, d2), isComposite = true))
                }
            }

            if (d1 != d2) {
                val to2 = getTargetPosition(state, from, d2)
                if (to2 != OFF_BOARD && canMove(state, from, to2, specificDie = d2)) {
                    val stateAfter2 = applyMoveLocally(state, from, to2, idx2)
                    val finalTo = getTargetPosition(stateAfter2, to2, d1)
                    if (canMove(stateAfter2, to2, finalTo, specificDie = d1)) {
                        if (options.none { it.to == finalTo }) {
                            options.add(MoveOption(finalTo, listOf(d2, d1), isComposite = true))
                        }
                    }
                }
            }
        }
        return options
    }

    private fun applyMoveLocally(state: GameState, from: Int, to: Int, usedIdx: Int? = null): GameState {
        val newBoard = state.board.copyOf()
        val isWhite = state.currentPlayer == "white"
        if (from in 1..24) {
            if (isWhite) newBoard[from]-- else newBoard[from]++
        }
        if (to in 1..24) {
            if (isWhite) newBoard[to]++ else newBoard[to]--
        }
        val newUsed = state.usedDice.toMutableList()
        if (usedIdx != null && usedIdx in newUsed.indices) {
            newUsed[usedIdx] = true
        }
        return state.copy(board = newBoard, usedDice = newUsed)
    }

    fun getTargetPosition(state: GameState, from: Int, step: Int): Int {
        val isWhite = state.currentPlayer == "white"
        if (isWhite) {
            val to = from + step
            return if (to > 24) OFF_BOARD else to
        } else {
            var to = from + step
            if (from >= 13 && to > 24) to -= 24
            if (from <= 12 && to > 12) return OFF_BOARD
            return to
        }
    }

    fun canMove(state: GameState, from: Int, to: Int, specificDie: Int? = null): Boolean {
        if (from < 1 || from > 24) return false
        val isWhite = state.currentPlayer == "white"
        val count = state.board[from]
        if (isWhite && count <= 0) return false
        if (!isWhite && count >= 0) return false
        
        if (to == OFF_BOARD) {
            if (!isAllInHome(state, state.currentPlayer)) return false
            val dist = getDistanceToExit(state.currentPlayer, from)
            val availableDice = if (specificDie != null) listOf(specificDie) 
                               else state.dice.filterIndexed { i, _ -> !state.usedDice.getOrElse(i) { false } }
            return availableDice.any { it == dist || (it > dist && isFurthestPiece(state, from)) }
        }
        
        if (to < 1 || to > 24) return false
        if (isWhite && state.board[to] < 0) return false
        if (!isWhite && state.board[to] > 0) return false
        
        val headPos = if (isWhite) 1 else 13
        if (from == headPos) {
            val firstDie = state.dice.firstOrNull() ?: 0
            val limit = if (state.isFirstTurn && (firstDie in listOf(3, 4, 6)) && state.dice.distinct().size == 1) 2 else 1
            if (state.headMovesCount >= limit) return false
        }

        val stateAfter = applyMoveLocally(state, from, to)
        if (createsIllegalBlock(stateAfter, state.currentPlayer)) return false

        return true
    }

    private fun createsIllegalBlock(state: GameState, player: String): Boolean {
        val board = state.board
        val isWhite = player == "white"
        
        // 1. Блокировка всегда разрешена, если противник уже вывел хоть одну шашку
        var oppOnBoard = 0
        for (i in 1..24) {
            val c = board[i]
            if (isWhite && c < 0) oppOnBoard -= c
            if (!isWhite && c > 0) oppOnBoard += c
        }
        if (oppOnBoard < 15) return false

        // 2. Маршруты (Длинные нарды: оба против часовой)
        // Белые: 1 -> 12 -> 13 -> 24
        // Черные: 13 -> 24 -> 1 -> 12
        val myPath = if (isWhite) (1..24).toList() else (13..24).toList() + (1..12).toList()
        val oppPath = if (isWhite) (13..24).toList() + (1..12).toList() else (1..24).toList()
        
        var consecutive = 0
        for (i in myPath.indices) {
            val pos = myPath[i]
            val hasMine = if (isWhite) board[pos] > 0 else board[pos] < 0
            if (hasMine) {
                consecutive++
                if (consecutive >= 6) {
                    // Нашли блок из 6. 
                    // Правило: Блок разрешен, если ХОТЯ БЫ ОДНА шашка противника уже ПРОШЛА этот блок.
                    // "Прошла" означает, что она находится между концом этого блока и своим выходом.
                    
                    val blockPositions = myPath.subList(i - 5, i + 1).toSet()
                    val lastBlockIndexInOppPath = oppPath.indexOfLast { it in blockPositions }
                    
                    // Есть ли шашки противника дальше по их маршруту?
                    val anyOpponentAhead = (lastBlockIndexInOppPath + 1 until oppPath.size).any { idx ->
                        val p = oppPath[idx]
                        if (isWhite) board[p] < 0 else board[p] > 0
                    }
                    
                    if (!anyOpponentAhead) return true // Глухой забор - запрещено
                }
            } else {
                consecutive = 0
            }
        }
        return false
    }

    private fun isAllInHome(state: GameState, player: String): Boolean {
        return if (player == "white") (1..18).all { state.board[it] <= 0 }
        else (13..24).all { state.board[it] >= 0 } && (1..6).all { state.board[it] >= 0 }
    }

    private fun getDistanceToExit(player: String, from: Int): Int = if (player == "white") 25 - from else {
        if (from >= 13) (25 - from) + 12 else 13 - from
    }

    private fun isFurthestPiece(state: GameState, from: Int): Boolean {
        val isWhite = state.currentPlayer == "white"
        if (isWhite) {
            return (19 until from).all { state.board[it] <= 0 }
        } else {
            // Для черных дом - это 1..12. Самая дальняя шашка та, у которой путь до 13 больше.
            val homePath = (1..12).toList()
            val myIdx = homePath.indexOf(from)
            if (myIdx == -1) return false
            return homePath.subList(0, myIdx).all { state.board[it] >= 0 }
        }
    }

    fun applyMove(state: GameState, from: Int, to: Int, diceValue: Int): GameState {
        val newBoard = state.board.copyOf()
        val isWhite = state.currentPlayer == "white"
        if (from in 1..24) {
            if (isWhite) newBoard[from]-- else newBoard[from]++
        }
        if (to in 1..24) {
            if (isWhite) newBoard[to]++ else newBoard[to]--
        }
        
        val newUsedDice = state.usedDice.toMutableList()
        val diceIdx = state.dice.indices.firstOrNull { i -> state.dice[i] == diceValue && !newUsedDice.getOrElse(i) { false } }
        if (diceIdx != null && diceIdx < newUsedDice.size) newUsedDice[diceIdx] = true
        
        val headPos = if (isWhite) 1 else 13
        val newHeadCount = if (from == headPos) state.headMovesCount + 1 else state.headMovesCount
        
        val whiteLeft = (1..24).sumOf { if (newBoard[it] > 0) newBoard[it] else 0 }
        val blackLeft = (1..24).sumOf { if (newBoard[it] < 0) -newBoard[it] else 0 }
        
        var winner = state.winner
        if (whiteLeft == 0) winner = "white" else if (blackLeft == 0) winner = "black"
        
        val tempState = state.copy(board = newBoard, dice = state.dice, usedDice = newUsedDice, headMovesCount = newHeadCount, winner = winner)
        
        if (winner == null && (newUsedDice.all { it } || !hasAnyPossibleMoves(tempState))) {
            return switchTurn(tempState)
        }
        
        return state.copy(
            board = newBoard, 
            dice = state.dice, 
            usedDice = newUsedDice, 
            currentPlayer = state.currentPlayer, 
            headMovesCount = newHeadCount, 
            moveIndex = state.moveIndex + 1, 
            isFirstTurn = state.isFirstTurn, 
            winner = winner
        )
    }

    private fun hasAnyPossibleMoves(state: GameState): Boolean {
        if (state.winner != null || state.dice.isEmpty() || state.usedDice.all { it }) return false
        for (i in 1..24) {
            val count = state.board[i]
            if ((state.currentPlayer == "white" && count > 0) || (state.currentPlayer == "black" && count < 0)) {
                if (getPossibleMoveOptions(state, i).isNotEmpty()) return true
            }
        }
        return false
    }
}
