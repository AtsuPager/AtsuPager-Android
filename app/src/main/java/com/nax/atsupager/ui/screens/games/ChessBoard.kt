package com.nax.atsupager.ui.screens.games

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.domain.chess.ChessEngine
import com.nax.atsupager.domain.chess.ChessMove
import com.nax.atsupager.domain.chess.PromotionData

@Composable
fun ChessBoard(
    fen: String,
    myColor: String,
    isMyTurn: Boolean,
    lastMove: ChessMove?,
    isCheck: Boolean = false,
    gameStatus: ChessEngine.GameStatus = ChessEngine.GameStatus.ONGOING,
    pendingPromotion: PromotionData? = null,
    onMove: (Int, Int, Int, Int) -> Unit,
    onPromote: (Char) -> Unit
) {
    val board = remember(fen) { ChessEngine.fenToBoard(fen) }
    var selectedSquare by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    
    val range = if (myColor == "white") 0..7 else 7 downTo 0

    val whiteInCheck = remember(fen) { ChessEngine.isInCheck(fen, true) }
    val blackInCheck = remember(fen) { ChessEngine.isInCheck(fen, false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF312E2B))
                .border(2.dp, Color.Gray)
        ) {
            Column {
                for (row in range) {
                    Row(modifier = Modifier.weight(1f)) {
                        for (col in range) {
                            val isDark = (row + col) % 2 != 0
                            val baseColor = if (isDark) Color(0xFF769656) else Color(0xFFEEEED2)
                            val isSelected = selectedSquare?.first == row && selectedSquare?.second == col
                            
                            val isLastMoveTarget = lastMove != null && 
                                                 ((lastMove.toRow == row && lastMove.toCol == col) ||
                                                  (lastMove.fromRow == row && lastMove.fromCol == col))
                            
                            val isLastMoveEnd = lastMove != null && lastMove.toRow == row && lastMove.toCol == col

                            val piece = board[row][col]
                            
                            val isKingInCheck = piece != null && piece.lowercaseChar() == 'k' && (
                                (piece.isUpperCase() && whiteInCheck) || 
                                (!piece.isUpperCase() && blackInCheck)
                            )

                            val lastMoveHighlight by animateColorAsState(
                                targetValue = if (isLastMoveTarget) Color.Yellow.copy(alpha = 0.4f) else Color.Transparent,
                                animationSpec = tween(500), label = ""
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(baseColor)
                                    .background(if (isSelected) Color.Blue.copy(alpha = 0.4f) else lastMoveHighlight)
                                    .then(
                                        if (isKingInCheck) Modifier.background(Color.Red.copy(alpha = 0.6f))
                                        else Modifier
                                    )
                                    .then(
                                        if (isLastMoveEnd) Modifier.border(2.dp, Color.Red)
                                        else Modifier
                                    )
                                    .clickable {
                                        if (!isMyTurn || gameStatus != ChessEngine.GameStatus.ONGOING) return@clickable
                                        
                                        val currentSelected = selectedSquare
                                        val pieceOnSquare = board[row][col]
                                        val isMyPiece = pieceOnSquare != null && (
                                            (myColor == "white" && pieceOnSquare.isUpperCase()) || 
                                            (myColor == "black" && !pieceOnSquare.isUpperCase())
                                        )

                                        if (currentSelected == null) {
                                            if (isMyPiece) {
                                                selectedSquare = row to col
                                            }
                                        } else {
                                            if (currentSelected.first == row && currentSelected.second == col) {
                                                selectedSquare = null
                                            } else if (isMyPiece) {
                                                selectedSquare = row to col
                                            } else {
                                                onMove(currentSelected.first, currentSelected.second, row, col)
                                                selectedSquare = null
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (piece != null) {
                                    ChessPieceIcon(piece)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pendingPromotion != null) {
            PromotionDialog(isWhite = myColor == "white", onSelect = onPromote)
        }

        Spacer(modifier = Modifier.height(12.dp))
        
        val statusText = when (gameStatus) {
            ChessEngine.GameStatus.WHITE_WIN -> stringResource(R.string.chess_white_win)
            ChessEngine.GameStatus.BLACK_WIN -> stringResource(R.string.chess_black_win)
            ChessEngine.GameStatus.DRAW_STALEMATE -> stringResource(R.string.chess_draw)
            ChessEngine.GameStatus.ONGOING -> {
                val prefix = if (isCheck) stringResource(R.string.chess_check) else ""
                val turn = if (isMyTurn) stringResource(R.string.chess_your_turn) else stringResource(R.string.chess_waiting_turn)
                "$prefix$turn"
            }
        }

        val statusBgColor by animateColorAsState(
            targetValue = when {
                gameStatus != ChessEngine.GameStatus.ONGOING -> MaterialTheme.colorScheme.errorContainer
                isCheck -> Color.Red.copy(alpha = 0.8f)
                isMyTurn -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.DarkGray
            }, label = "statusBg"
        )

        val statusTextColor = when {
            gameStatus != ChessEngine.GameStatus.ONGOING -> MaterialTheme.colorScheme.onErrorContainer
            isCheck || isMyTurn -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> Color.LightGray
        }

        Surface(
            color = statusBgColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusTextColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ChessPieceIcon(char: Char) {
    val resId = when (char) {
        'P' -> R.drawable.ic_chess_wp
        'R' -> R.drawable.ic_chess_wr
        'N' -> R.drawable.ic_chess_wn
        'B' -> R.drawable.ic_chess_wb
        'Q' -> R.drawable.ic_chess_wq
        'K' -> R.drawable.ic_chess_wk
        'p' -> R.drawable.ic_chess_bp
        'r' -> R.drawable.ic_chess_br
        'n' -> R.drawable.ic_chess_bn
        'b' -> R.drawable.ic_chess_bb
        'q' -> R.drawable.ic_chess_bq
        'k' -> R.drawable.ic_chess_bk
        else -> null
    }

    resId?.let {
        Image(
            painter = painterResource(id = it),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.9f)
        )
    }
}
