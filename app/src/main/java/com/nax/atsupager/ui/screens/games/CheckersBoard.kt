package com.nax.atsupager.ui.screens.games

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nax.atsupager.R
import com.nax.atsupager.domain.checkers.CheckersEngine
import com.nax.atsupager.domain.checkers.CheckersMove

@Composable
fun CheckersBoard(
    state: String,
    myColor: String,
    isMyTurn: Boolean,
    lastMove: CheckersMove?,
    winner: String? = null,
    onMove: (Int, Int, Int, Int) -> Unit
) {
    val board = remember(state) { CheckersEngine.stateToBoard(state) }
    var selectedSquare by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val range = if (myColor == "white") 0..7 else 7 downTo 0

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
                            val piece = board[row][col]
                            val isDark = (row + col) % 2 != 0
                            val baseColor = if (isDark) Color(0xFF769656) else Color(0xFFEEEED2)
                            val isSelected = selectedSquare?.first == row && selectedSquare?.second == col
                            
                            val isLastMoveTarget = lastMove != null && 
                                                 ((lastMove.toRow == row && lastMove.toCol == col) ||
                                                  (lastMove.fromRow == row && lastMove.fromCol == col))
                            
                            val isLastMoveEnd = lastMove != null && lastMove.toRow == row && lastMove.toCol == col

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
                                        if (isLastMoveEnd) Modifier.border(2.dp, Color.Red)
                                        else Modifier
                                    )
                                    .clickable {
                                        if (!isMyTurn || winner != null) return@clickable
                                        
                                        val currentSelected = selectedSquare
                                        val isMyPiece = piece != '_' && piece != '.' && (
                                            (myColor == "white" && piece.lowercaseChar() == 'w') || 
                                            (myColor == "black" && piece.lowercaseChar() == 'b')
                                        )

                                        if (currentSelected == null) {
                                            if (isMyPiece) selectedSquare = row to col
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
                                if (piece != '_' && piece != '.') {
                                    CheckersPiece(piece)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        
        val statusText = when {
            winner == "white" -> stringResource(R.string.checkers_white_win)
            winner == "black" -> stringResource(R.string.checkers_black_win)
            isMyTurn -> stringResource(R.string.chess_your_turn)
            else -> stringResource(R.string.chess_waiting_turn)
        }

        val statusBgColor by animateColorAsState(
            targetValue = when {
                winner != null -> MaterialTheme.colorScheme.errorContainer
                isMyTurn -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.DarkGray
            }, label = "statusBg"
        )

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
                color = if (isMyTurn || winner != null) MaterialTheme.colorScheme.onPrimaryContainer else Color.LightGray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun CheckersPiece(char: Char) {
    val isWhite = char.lowercaseChar() == 'w'
    val isKing = char.isUpperCase()
    
    Box(
        modifier = Modifier
            .fillMaxSize(0.8f)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (isWhite) listOf(Color.White, Color.LightGray) 
                            else listOf(Color(0xFF444444), Color.Black)
                )
            )
            .border(2.dp, if (isWhite) Color.Gray else Color.DarkGray, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (isKing) {
            Text(
                text = "K", 
                color = if (isWhite) Color.Black else Color.White,
                fontWeight = FontWeight.Black
            )
        }
        
        Canvas(modifier = Modifier.fillMaxSize(0.7f)) {
            drawCircle(
                color = if (isWhite) Color.Gray.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
