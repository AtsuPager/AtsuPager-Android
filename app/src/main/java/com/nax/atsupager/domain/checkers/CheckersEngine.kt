package com.nax.atsupager.domain.checkers

import kotlin.math.abs
import kotlin.math.sign

object CheckersEngine {
    // . = Светлая (не исп.), _ = Пустая темная, w/b = Простые, W/B = Дамки
    // Расстановка: a1 (7,0) - темная. Темные клетки там, где (r+c) нечетное.
    const val INITIAL_STATE = ".b.b.b.b/b.b.b.b./.b.b.b.b/_._._._./._._._._/w.w.w.w./.w.w.w.w/w.w.w.w. w"

    fun stateToBoard(state: String): Array<CharArray> {
        val board = Array(8) { CharArray(8) { '.' } }
        val parts = state.split(" ")
        val rows = parts[0].split("/")
        for (r in 0..7) {
            val rowStr = rows[r]
            for (c in 0..7) {
                board[r][c] = rowStr[c]
            }
        }
        return board
    }

    fun boardToState(board: Array<CharArray>, nextTurn: String, activePiece: Pair<Int, Int>? = null): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            for (c in 0..7) {
                sb.append(board[r][c])
            }
            if (r < 7) sb.append("/")
        }
        sb.append(" ").append(nextTurn)
        if (activePiece != null) {
            sb.append(" ").append(activePiece.first).append(",").append(activePiece.second)
        }
        return sb.toString()
    }

    fun getValidMoves(state: String, r: Int, c: Int): List<Pair<Int, Int>> {
        val parts = state.split(" ")
        val board = stateToBoard(state)
        val turn = parts[1]
        val activePieceStr = parts.getOrNull(2)
        
        val piece = board[r][c]
        if (piece == '_' || piece == '.') return emptyList()
        if (turn == "w" && piece.lowercaseChar() != 'w') return emptyList()
        if (turn == "b" && piece.lowercaseChar() != 'b') return emptyList()

        // Если мы в процессе серийного взятия, ходить может только активная фигура
        if (activePieceStr != null) {
            val coords = activePieceStr.split(",")
            if (r != coords[0].toInt() || c != coords[1].toInt()) return emptyList()
            return getPieceCaptures(board, r, c)
        }

        // Правило обязательного взятия
        val captures = getPieceCaptures(board, r, c)
        val anyCapturePossible = canAnyPieceCapture(board, turn)

        return if (anyCapturePossible) {
            captures
        } else {
            getRegularMoves(board, r, c)
        }
    }

    private fun canAnyPieceCapture(board: Array<CharArray>, turn: String): Boolean {
        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p.lowercaseChar() == turn[0]) {
                    if (getPieceCaptures(board, r, c).isNotEmpty()) return true
                }
            }
        }
        return false
    }

    private fun getPieceCaptures(board: Array<CharArray>, r: Int, c: Int): List<Pair<Int, Int>> {
        val piece = board[r][c]
        val isKing = piece.isUpperCase()
        val targets = mutableListOf<Pair<Int, Int>>()
        val directions = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)

        for ((dr, dc) in directions) {
            if (isKing) {
                // Дамка: бьет через любое расстояние
                var tr = r + dr
                var tc = c + dc
                var opponentFound = false
                while (tr in 0..7 && tc in 0..7) {
                    val p = board[tr][tc]
                    if (p == '.') break
                    if (p == '_') {
                        if (opponentFound) targets.add(tr to tc)
                    } else if (p.lowercaseChar() == piece.lowercaseChar()) {
                        break // Своя фигура на пути
                    } else {
                        if (opponentFound) break // Вторая вражеская фигура на пути
                        opponentFound = true
                    }
                    tr += dr
                    tc += dc
                }
            } else {
                // Простая: бьет на 2 клетки (включая назад)
                val midR = r + dr
                val midC = c + dc
                val endR = r + 2 * dr
                val endC = c + 2 * dc
                if (endR in 0..7 && endC in 0..7 && board[endR][endC] == '_') {
                    val midP = board[midR][midC]
                    if (midP != '_' && midP != '.' && midP.lowercaseChar() != piece.lowercaseChar()) {
                        targets.add(endR to endC)
                    }
                }
            }
        }
        return targets
    }

    private fun getRegularMoves(board: Array<CharArray>, r: Int, c: Int): List<Pair<Int, Int>> {
        val piece = board[r][c]
        val isKing = piece.isUpperCase()
        val moves = mutableListOf<Pair<Int, Int>>()
        val directions = if (isKing) {
            listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        } else {
            if (piece == 'w') listOf(-1 to -1, -1 to 1) else listOf(1 to -1, 1 to 1)
        }

        for ((dr, dc) in directions) {
            var tr = r + dr
            var tc = c + dc
            if (isKing) {
                while (tr in 0..7 && tc in 0..7 && board[tr][tc] == '_') {
                    moves.add(tr to tc)
                    tr += dr
                    tc += dc
                }
            } else {
                if (tr in 0..7 && tc in 0..7 && board[tr][tc] == '_') {
                    moves.add(tr to tc)
                }
            }
        }
        return moves
    }

    fun makeMove(state: String, fromR: Int, fromC: Int, toR: Int, toC: Int): String? {
        val board = stateToBoard(state)
        val parts = state.split(" ")
        val turn = parts[1]
        val piece = board[fromR][fromC]
        
        if ((toR to toC) !in getValidMoves(state, fromR, fromC)) return null

        val dr = toR - fromR
        val dc = toC - fromC
        val isCapture = abs(dr) > 1

        board[toR][toC] = piece
        board[fromR][fromC] = '_'

        if (isCapture) {
            val stepR = dr.sign
            val stepC = dc.sign
            var currR = fromR + stepR
            var currC = fromC + stepC
            while (currR != toR) {
                board[currR][currC] = '_'
                currR += stepR
                currC += stepC
            }
            
            // Если простая стала дамкой В ПРОЦЕССЕ боя, она продолжает бой как дамка
            if (!piece.isUpperCase()) {
                if (turn == "w" && toR == 0) board[toR][toC] = 'W'
                if (turn == "b" && toR == 7) board[toR][toC] = 'B'
            }

            if (getPieceCaptures(board, toR, toC).isNotEmpty()) {
                return boardToState(board, turn, activePiece = toR to toC)
            }
        } else {
            // Обычное превращение в дамку в конце хода
            if (turn == "w" && toR == 0) board[toR][toC] = 'W'
            if (turn == "b" && toR == 7) board[toR][toC] = 'B'
        }

        val nextTurn = if (turn == "w") "b" else "w"
        return boardToState(board, nextTurn)
    }

    fun getWinner(state: String): String? {
        val board = stateToBoard(state)
        val parts = state.split(" ")
        val turn = parts[1]
        
        var whiteCanMove = false
        var blackCanMove = false
        var whitePieces = 0
        var blackPieces = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val p = board[r][c]
                if (p.lowercaseChar() == 'w') {
                    whitePieces++
                    if (getValidMoves(state, r, c).isNotEmpty()) whiteCanMove = true
                } else if (p.lowercaseChar() == 'b') {
                    blackPieces++
                    if (getValidMoves(state, r, c).isNotEmpty()) blackCanMove = true
                }
            }
        }

        if (whitePieces == 0 || (turn == "w" && !whiteCanMove)) return "black"
        if (blackPieces == 0 || (turn == "b" && !blackCanMove)) return "white"
        return null
    }
}
