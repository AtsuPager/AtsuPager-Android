package com.nax.atsupager.ui.screens.games

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nax.atsupager.R
import com.nax.atsupager.domain.backgammon.BackgammonEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun BackgammonBoard(
    state: BackgammonEngine.GameState,
    myColor: String,
    isMyTurn: Boolean,
    onMove: (Int, Int, Int) -> Unit,
    onRollDice: () -> Unit
) {
    var selectedPoint by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    val possibleMoveOptions = remember(selectedPoint, state) {
        selectedPoint?.let { from ->
            BackgammonEngine.getPossibleMoveOptions(state, from)
        } ?: emptyList()
    }

    val isReversed = myColor == "white"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ОСНОВНАЯ КВАДРАТНАЯ ДОСКА
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.9f)
                .shadow(12.dp, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF5D4037), Color(0xFF3E2723))))
                .border(6.dp, Color(0xFF2D1B18), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                BoardHalf(
                    indicesTop = if (isReversed) (12 downTo 7) else (24 downTo 19),
                    indicesBottom = if (isReversed) (13..18) else (1..6),
                    state = state,
                    myColor = myColor,
                    selectedPoint = selectedPoint,
                    possibleMoveOptions = possibleMoveOptions,
                    onPointClick = { idx -> 
                        handlePointClick(idx, isMyTurn, state, selectedPoint, possibleMoveOptions, onMove, scope) { selectedPoint = it }
                    }
                )
                
                // Центральная планка
                Box(modifier = Modifier.fillMaxHeight().width(22.dp).background(Color(0xFF2D1B18)).shadow(4.dp))
                
                BoardHalf(
                    indicesTop = if (isReversed) (6 downTo 1) else (18 downTo 13),
                    indicesBottom = if (isReversed) (19..24) else (7..12),
                    state = state,
                    myColor = myColor,
                    selectedPoint = selectedPoint,
                    possibleMoveOptions = possibleMoveOptions,
                    onPointClick = { idx -> 
                        handlePointClick(idx, isMyTurn, state, selectedPoint, possibleMoveOptions, onMove, scope) { selectedPoint = it }
                    }
                )
            }
            
            if (isMyTurn && state.dice.isEmpty() && state.winner == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = onRollDice,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.9f), contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.Casino, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.roll_dice), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(0.dp))

        // ИНФОРМАЦИОННАЯ ПАНЕЛЬ С КУБИКАМИ
        val statusText = when {
            state.winner != null -> stringResource(R.string.bg_win, if (state.winner == "white") stringResource(R.string.bg_white) else stringResource(R.string.bg_black))
            isMyTurn -> stringResource(R.string.bg_your_turn)
            else -> stringResource(R.string.bg_his_turn)
        }

        val statusColor = when {
            state.winner != null -> Color(0xFFFFD700)
            isMyTurn -> Color(0xFF00FF00) // Ярко-зеленый для вашего хода
            else -> Color.Gray.copy(alpha = 0.8f)
        }

        GameInfoPanel(
            statusText = statusText,
            statusColor = statusColor,
            dice = state.dice,
            usedDice = state.usedDice,
            isPossibleExit = possibleMoveOptions.any { it.to == BackgammonEngine.OFF_BOARD },
            onExit = {
                selectedPoint?.let { from ->
                    val exitOption = possibleMoveOptions.find { it.to == BackgammonEngine.OFF_BOARD }
                    exitOption?.let { onMove(from, BackgammonEngine.OFF_BOARD, it.diceUsed.first()) }
                    selectedPoint = null
                }
            }
        )
    }
}

@Composable
fun GameInfoPanel(
    statusText: String,
    statusColor: Color,
    dice: List<Int>,
    usedDice: List<Boolean>,
    isPossibleExit: Boolean,
    onExit: () -> Unit
) {
    // Анимация свечения для текста "ВАШ ХОД"
    val yourTurnStr = stringResource(R.string.bg_your_turn)
    val isMyTurn = statusText == yourTurnStr
    val infiniteTransition = rememberInfiniteTransition(label = "textGlow")
    val glowBlur by infiniteTransition.animateFloat(
        initialValue = 2f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "glowBlur"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp), 
        color = Color(0xFF2D1B18),
        shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                style = LocalTextStyle.current.copy(
                    shadow = if (isMyTurn) Shadow(
                        color = Color.Green.copy(alpha = 0.7f),
                        offset = Offset(0f, 0f),
                        blurRadius = glowBlur
                    ) else null
                ),
                modifier = Modifier.widthIn(min = 80.dp)
            )

            // КУБИКИ ПО ЦЕНТРУ
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                dice.forEachIndexed { index, value ->
                    DiceView(
                        value = value, 
                        isUsed = usedDice.getOrNull(index) == true,
                        size = 32.dp
                    )
                    if (index < dice.size - 1) Spacer(Modifier.width(8.dp))
                }
            }

            // КНОПКА ВЫВОДА
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isPossibleExit) Color.Green.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(enabled = isPossibleExit, onClick = onExit)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .then(if (isPossibleExit) Modifier.border(BorderStroke(1.dp, Color.Green.copy(alpha = 0.4f)), RoundedCornerShape(8.dp)) else Modifier)
            ) {
                Text(
                    stringResource(R.string.bg_home),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isPossibleExit) Color.Green else Color.White.copy(alpha = 0.2f)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = null,
                    tint = if (isPossibleExit) Color.Green else Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun handlePointClick(
    idx: Int,
    isMyTurn: Boolean,
    state: BackgammonEngine.GameState,
    selectedPoint: Int?,
    possibleOptions: List<BackgammonEngine.MoveOption>,
    onMove: (Int, Int, Int) -> Unit,
    scope: CoroutineScope,
    updateSelected: (Int?) -> Unit
) {
    val option = possibleOptions.find { it.to == idx }
    if (selectedPoint != null && option != null) {
        if (option.isComposite) {
            scope.launch {
                var currentFrom: Int = selectedPoint
                var tempState = state
                option.diceUsed.forEachIndexed { index, die ->
                    val nextTo = BackgammonEngine.getTargetPosition(tempState, currentFrom, die)
                    onMove(currentFrom, nextTo, die)

                    if (index < option.diceUsed.size - 1) {
                        delay(350) 
                        currentFrom = nextTo
                        tempState = BackgammonEngine.applyMove(tempState, currentFrom, nextTo, die)
                    }
                }
            }
        } else {
            onMove(selectedPoint, idx, option.diceUsed.first())
        }
        updateSelected(null)
    } else {
        val isMyPiece = (state.currentPlayer == "white" && state.board[idx] > 0) || (state.currentPlayer == "black" && state.board[idx] < 0)
        updateSelected(if (isMyTurn && isMyPiece) idx else null)
    }
}

@Composable
fun RowScope.BoardHalf(
    indicesTop: Iterable<Int>,
    indicesBottom: Iterable<Int>,
    state: BackgammonEngine.GameState,
    myColor: String,
    selectedPoint: Int?,
    possibleMoveOptions: List<BackgammonEngine.MoveOption>,
    onPointClick: (Int) -> Unit
) {
    Column(modifier = Modifier.weight(1f)) {
        Row(modifier = Modifier.weight(1f)) {
            indicesTop.forEach { idx ->
                val option = possibleMoveOptions.find { it.to == idx }
                PointView(
                    index = idx,
                    count = state.board[idx],
                    isSelected = selectedPoint == idx,
                    isPossibleMove = option != null,
                    isCompositeMove = option?.isComposite ?: false,
                    isMyColor = (myColor == "white" && state.board[idx] > 0) || (myColor == "black" && state.board[idx] < 0),
                    isTop = true,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onPointClick(idx) }
                )
            }
        }
        Row(modifier = Modifier.weight(1f)) {
            indicesBottom.forEach { idx ->
                val option = possibleMoveOptions.find { it.to == idx }
                PointView(
                    index = idx,
                    count = state.board[idx],
                    isSelected = selectedPoint == idx,
                    isPossibleMove = option != null,
                    isCompositeMove = option?.isComposite ?: false,
                    isMyColor = (myColor == "white" && state.board[idx] > 0) || (myColor == "black" && state.board[idx] < 0),
                    isTop = false,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onPointClick(idx) }
                )
            }
        }
    }
}

@Composable
fun PointView(
    index: Int,
    count: Int,
    isSelected: Boolean,
    isPossibleMove: Boolean,
    isCompositeMove: Boolean,
    isMyColor: Boolean,
    isTop: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(modifier = modifier.clickable(onClick = onClick)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path()
            val w = size.width
            val h = size.height
            if (isTop) { path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w / 2, h * 0.92f) }
            else { path.moveTo(0f, h); path.lineTo(w, h); path.lineTo(w / 2, h * 0.08f) }
            path.close()
            val color = if (index % 2 == 0) Color(0xFFD7CCC8) else Color(0xFF8D6E63)
            drawPath(path = path, brush = Brush.verticalGradient(if (isTop) listOf(color, color.copy(alpha = 0.7f)) else listOf(color.copy(alpha = 0.7f), color)))
            if (isSelected) drawPath(path = path, color = Color.White.copy(alpha = 0.3f))
            
            if (isPossibleMove) {
                val dotColor = if (isCompositeMove) Color.Red else Color.Green
                val dotY = if (isTop) h * 0.85f else h * 0.15f
                drawCircle(color = dotColor, radius = 12f, center = Offset(w / 2, dotY), alpha = 0.9f)
            }
        }
        val absCount = abs(count)
        val checkerColor = if (count > 0) Color.White else Color.Black
        Column(modifier = Modifier.fillMaxSize().padding(vertical = 2.dp), verticalArrangement = if (isTop) Arrangement.Top else Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            val visibleCount = minOf(absCount, 5)
            repeat(visibleCount) { i ->
                CheckerPiece(
                    color = checkerColor,
                    isMine = isMyColor,
                    isTopLayer = i == visibleCount - 1 && isSelected
                )
            }
            if (absCount > 5) Text(text = "+${absCount - 5}", color = if (checkerColor == Color.White) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CheckerPiece(color: Color, isMine: Boolean, isTopLayer: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "checker")
    val glowAlpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 0.9f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "glow")

    Box(
        modifier = Modifier
            .padding(vertical = 0.5.dp)
            .size(31.dp)
            .shadow(if (isTopLayer) 8.dp else 2.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(colors = listOf(color.copy(alpha = 0.9f), color), center = Offset(15f, 15f)))
            .border(
                width = if (isTopLayer) 2.dp else 1.dp,
                color = if (isTopLayer) Color.Green.copy(alpha = glowAlpha) else Color.Gray.copy(alpha = 0.5f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isMine) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFD700).copy(alpha = 0.7f))
            )
        }
    }
}

@Composable
fun DiceView(value: Int, isUsed: Boolean, size: Dp = 32.dp) {
    val scale by animateFloatAsState(if (isUsed) 0.8f else 1.0f, label = "diceScale")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(4.dp, RoundedCornerShape(size / 6))
            .clip(RoundedCornerShape(size / 6))
            .background(if (isUsed) Color.Gray else Color.White)
            .border(1.5.dp, Color.Black.copy(alpha = 0.2f), RoundedCornerShape(size / 6)), 
        contentAlignment = Alignment.Center
    ) {
        DiceDots(value = value, isUsed = isUsed, dotSize = 6.dp) 
    }
}

@Composable
fun DiceDots(value: Int, isUsed: Boolean, dotSize: Dp = 6.dp) {
    val dotColor = if (isUsed) Color.DarkGray else Color.Black
    Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
        when (value) {
            1 -> Dot(Alignment.Center, dotColor, dotSize)
            2 -> { Dot(Alignment.TopEnd, dotColor, dotSize); Dot(Alignment.BottomStart, dotColor, dotSize) }
            3 -> { Dot(Alignment.TopEnd, dotColor, dotSize); Dot(Alignment.Center, dotColor, dotSize); Dot(Alignment.BottomStart, dotColor, dotSize) }
            4 -> { Dot(Alignment.TopStart, dotColor, dotSize); Dot(Alignment.TopEnd, dotColor, dotSize); Dot(Alignment.BottomStart, dotColor, dotSize); Dot(Alignment.BottomEnd, dotColor, dotSize) }
            5 -> { Dot(Alignment.TopStart, dotColor, dotSize); Dot(Alignment.TopEnd, dotColor, dotSize); Dot(Alignment.Center, dotColor, dotSize); Dot(Alignment.BottomStart, dotColor, dotSize); Dot(Alignment.BottomEnd, dotColor, dotSize) }
            6 -> { Dot(Alignment.TopStart, dotColor, dotSize); Dot(Alignment.TopEnd, dotColor, dotSize); Dot(Alignment.CenterStart, dotColor, dotSize); Dot(Alignment.CenterEnd, dotColor, dotSize); Dot(Alignment.BottomStart, dotColor, dotSize); Dot(Alignment.BottomEnd, dotColor, dotSize) }
        }
    }
}

@Composable
fun BoxScope.Dot(alignment: Alignment, color: Color, size: Dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(color).align(alignment))
}
