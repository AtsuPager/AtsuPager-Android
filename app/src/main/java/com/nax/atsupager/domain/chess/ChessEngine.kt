package com.nax.atsupager.domain.chess

import kotlin.math.abs

object ChessEngine {
    const val INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    fun fenToBoard(fen: String): Array<Array<Char?>> {
        val board = Array(8) { arrayOfNulls<Char>(8) }
        val parts = fen.split(" ")
        if (parts.isEmpty()) return board
        
        val rows = parts[0].split("/")
        for (i in 0 until 8) {
            if (i >= rows.size) break
            var col = 0
            for (char in rows[i]) {
                if (col >= 8) break
                if (char.isDigit()) {
                    col += char.toString().toInt()
                } else {
                    if (col < 8) {
                        board[i][col] = char
                        col++
                    }
                }
            }
        }
        return board
    }

    fun isSquareAttacked(board: Array<Array<Char?>>, row: Int, col: Int, attackerIsWhite: Boolean): Boolean {
        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c] ?: continue
                if (piece.isUpperCase() != attackerIsWhite) continue
                
                val dr = abs(row - r)
                val dc = abs(col - c)
                
                val attacked = when (piece.lowercaseChar()) {
                    'p' -> {
                        val dir = if (attackerIsWhite) -1 else 1
                        row - r == dir && dc == 1
                    }
                    'r' -> (r == row || c == col) && isPathClear(board, r, c, row, col)
                    'n' -> (dr == 2 && dc == 1) || (dr == 1 && dc == 2)
                    'b' -> (dr == dc) && isPathClear(board, r, c, row, col)
                    'q' -> (r == row || c == col || dr == dc) && isPathClear(board, r, c, row, col)
                    'k' -> dr <= 1 && dc <= 1
                    else -> false
                }
                if (attacked) return true
            }
        }
        return false
    }

    fun isInCheck(fen: String, isWhite: Boolean): Boolean {
        val board = fenToBoard(fen)
        val kingChar = if (isWhite) 'K' else 'k'
        var kingPos: Pair<Int, Int>? = null
        
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] == kingChar) {
                    kingPos = r to c
                    break
                }
            }
            if (kingPos != null) break
        }
        
        return kingPos?.let { isSquareAttacked(board, it.first, it.second, !isWhite) } ?: false
    }

    fun hasLegalMoves(fen: String): Boolean {
        val parts = fen.split(" ")
        val isWhiteTurn = parts[1] == "w"
        val board = fenToBoard(fen)

        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c] ?: continue
                if (piece.isUpperCase() != isWhiteTurn) continue

                for (tr in 0..7) {
                    for (tc in 0..7) {
                        if (isValidMove(fen, r, c, tr, tc)) return true
                    }
                }
            }
        }
        return false
    }

    fun getGameStatus(fen: String): GameStatus {
        val parts = fen.split(" ")
        val isWhiteTurn = parts[1] == "w"
        val inCheck = isInCheck(fen, isWhiteTurn)
        val hasMoves = hasLegalMoves(fen)

        return when {
            inCheck && !hasMoves -> if (isWhiteTurn) GameStatus.BLACK_WIN else GameStatus.WHITE_WIN
            !inCheck && !hasMoves -> GameStatus.DRAW_STALEMATE
            else -> GameStatus.ONGOING
        }
    }

    enum class GameStatus { ONGOING, WHITE_WIN, BLACK_WIN, DRAW_STALEMATE }

    fun isValidMove(fen: String, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): Boolean {
        if (fromRow !in 0..7 || fromCol !in 0..7 || toRow !in 0..7 || toCol !in 0..7) return false
        if (fromRow == toRow && fromCol == toCol) return false
        
        val board = fenToBoard(fen)
        val piece = board[fromRow][fromCol] ?: return false
        val target = board[toRow][toCol]
        val parts = fen.split(" ")
        val isWhiteTurn = parts[1] == "w"
        
        if (piece.isUpperCase() != isWhiteTurn) return false
        if (target != null && piece.isUpperCase() == target.isUpperCase()) return false

        val dr = abs(toRow - fromRow)
        val dc = abs(toCol - fromCol)
        val dir = if (piece.isUpperCase()) -1 else 1 

        val pseudoLegal = when (piece.lowercaseChar()) {
            'p' -> { 
                if (fromCol == toCol && target == null) {
                    if (toRow - fromRow == dir) true
                    else if (toRow - fromRow == 2 * dir && ((piece.isUpperCase() && fromRow == 6) || (!piece.isUpperCase() && fromRow == 1))) {
                        board[fromRow + dir][fromCol] == null
                    } else false
                } else if (toRow - fromRow == dir && dc == 1) {
                    if (target != null) true
                    else {
                        if (parts.size > 3 && parts[3] != "-") {
                            val epCoords = parts[3]
                            val epCol = epCoords[0] - 'a'
                            val epRow = 8 - (epCoords[1] - '0')
                            toRow == epRow && toCol == epCol
                        } else false
                    }
                } else false
            }
            'r' -> (fromRow == toRow || fromCol == toCol) && isPathClear(board, fromRow, fromCol, toRow, toCol)
            'n' -> (dr == 2 && dc == 1) || (dr == 1 && dc == 2)
            'b' -> (dr == dc) && isPathClear(board, fromRow, fromCol, toRow, toCol)
            'q' -> (fromRow == toRow || fromCol == toCol || dr == dc) && isPathClear(board, fromRow, fromCol, toRow, toCol)
            'k' -> {
                if (dr <= 1 && dc <= 1) true
                else if (dr == 0 && dc == 2) {
                    val castling = parts[2]
                    if (piece.isUpperCase()) {
                        if (toCol == 6 && castling.contains('K')) {
                            board[7][5] == null && board[7][6] == null && isPathClear(board, 7, 4, 7, 7) &&
                            !isSquareAttacked(board, 7, 4, false) && !isSquareAttacked(board, 7, 5, false)
                        } else if (toCol == 2 && castling.contains('Q')) {
                            board[7][1] == null && board[7][2] == null && board[7][3] == null && isPathClear(board, 7, 4, 7, 0) &&
                            !isSquareAttacked(board, 7, 4, false) && !isSquareAttacked(board, 7, 3, false)
                        } else false
                    } else {
                        if (toCol == 6 && castling.contains('k')) {
                            board[0][5] == null && board[0][6] == null && isPathClear(board, 0, 4, 0, 7) &&
                            !isSquareAttacked(board, 0, 4, true) && !isSquareAttacked(board, 0, 5, true)
                        } else if (toCol == 2 && castling.contains('q')) {
                            board[0][1] == null && board[0][2] == null && board[0][3] == null && isPathClear(board, 0, 4, 0, 0) &&
                            !isSquareAttacked(board, 0, 4, true) && !isSquareAttacked(board, 0, 3, true)
                        } else false
                    }
                } else false
            }
            else -> false
        }

        if (!pseudoLegal) return false

        val nextFen = move(fen, fromRow, fromCol, toRow, toCol, validate = false)
        return !isInCheck(nextFen, piece.isUpperCase())
    }

    private fun isPathClear(board: Array<Array<Char?>>, r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        val stepR = if (r1 == r2) 0 else if (r2 > r1) 1 else -1
        val stepC = if (c1 == c2) 0 else if (c2 > c1) 1 else -1
        var currR = r1 + stepR
        var currC = c1 + stepC
        while (currR != r2 || currC != c2) {
            if (currR !in 0..7 || currC !in 0..7) return false
            if (board[currR][currC] != null) return false
            currR += stepR
            currC += stepC
        }
        return true
    }

    fun move(fen: String, fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, promotion: Char? = null, validate: Boolean = true): String {
        if (validate && !isValidMove(fen, fromRow, fromCol, toRow, toCol)) return fen
        
        val board = fenToBoard(fen)
        val piece = board[fromRow][fromCol] ?: return fen
        val target = board[toRow][toCol]
        
        val parts = fen.split(" ").toMutableList()
        while (parts.size < 6) {
            when (parts.size) {
                1 -> parts.add("w")
                2 -> parts.add("KQkq")
                3 -> parts.add("-")
                4 -> parts.add("0")
                5 -> parts.add("1")
            }
        }

        var castling = parts[2]
        var enPassant = "-"
        
        if (piece.lowercaseChar() == 'k' && abs(toCol - fromCol) == 2) {
            if (toCol == 6) { 
                board[toRow][5] = board[toRow][7]
                board[toRow][7] = null
            } else if (toCol == 2) {
                board[toRow][3] = board[toRow][0]
                board[toRow][0] = null
            }
        }

        if (piece.lowercaseChar() == 'p' && fromCol != toCol && target == null) {
            val dir = if (piece.isUpperCase()) -1 else 1
            board[toRow - dir][toCol] = null
        }

        board[toRow][toCol] = piece
        board[fromRow][fromCol] = null

        if (piece == 'P' && toRow == 0) board[toRow][toCol] = promotion?.uppercaseChar() ?: 'Q'
        if (piece == 'p' && toRow == 7) board[toRow][toCol] = promotion?.lowercaseChar() ?: 'q'

        if (piece.lowercaseChar() == 'p' && abs(toRow - fromRow) == 2) {
            val epRow = if (piece.isUpperCase()) fromRow - 1 else fromRow + 1
            enPassant = toChessCoords(epRow, fromCol)
        }

        if (piece == 'K') castling = castling.replace("K", "").replace("Q", "")
        if (piece == 'k') castling = castling.replace("k", "").replace("q", "")
        if (piece == 'R' && fromRow == 7 && fromCol == 7) castling = castling.replace("K", "")
        if (piece == 'R' && fromRow == 7 && fromCol == 0) castling = castling.replace("Q", "")
        if (piece == 'r' && fromRow == 0 && fromCol == 7) castling = castling.replace("k", "")
        if (piece == 'r' && fromRow == 0 && fromCol == 0) castling = castling.replace("q", "")
        
        if (toRow == 0 && toCol == 0) castling = castling.replace("q", "")
        if (toRow == 0 && toCol == 7) castling = castling.replace("k", "")
        if (toRow == 7 && toCol == 0) castling = castling.replace("Q", "")
        if (toRow == 7 && toCol == 7) castling = castling.replace("K", "")
        
        if (castling.isEmpty()) castling = "-"

        val nextTurn = if (parts[1] == "w") "b" else "w"
        val halfMoveClock = if (piece.lowercaseChar() == 'p' || target != null) 0 
                           else (parts[4].toIntOrNull() ?: 0) + 1
        val fullMoveNumber = (parts[5].toIntOrNull() ?: 1) + if (parts[1] == "b") 1 else 0
        
        return "${boardToFen(board)} $nextTurn $castling $enPassant $halfMoveClock $fullMoveNumber"
    }

    private fun boardToFen(board: Array<Array<Char?>>): String {
        val sb = StringBuilder()
        for (i in 0 until 8) {
            var empty = 0
            for (j in 0 until 8) {
                if (board[i][j] == null) empty++
                else { if (empty > 0) sb.append(empty); empty = 0; sb.append(board[i][j]) }
            }
            if (empty > 0) sb.append(empty)
            if (i < 7) sb.append("/")
        }
        return sb.toString()
    }

    fun toChessCoords(row: Int, col: Int): String {
        if (row !in 0..7 || col !in 0..7) return "??"
        val colChar = ('a'.toInt() + col).toChar()
        val rowNum = 8 - row
        return "$colChar$rowNum"
    }
}
