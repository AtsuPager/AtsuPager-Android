package com.nax.atsupager.ui.screens.main

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.nax.atsupager.R
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.data.db.MessageType
import com.nax.atsupager.data.network.DownloadProgress
import com.nax.atsupager.data.network.EncryptedDataSourceFactory
import com.nax.atsupager.ui.screens.main.bubbles.FileTransferProgress
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaPagerViewer(
    mediaMessages: List<ChatMessage>,
    initialIndex: Int,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onDownload: (ChatMessage) -> Unit,
    downloadingIds: Set<Long>,
    downloadProgressMap: Map<Long, DownloadProgress>,
    onDelete: (ChatMessage) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { mediaMessages.size }
    val zoomedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val isCurrentPageZoomed by remember {
        derivedStateOf { zoomedPages[pagerState.currentPage] ?: false }
    }

    // Глобальное состояние видимости интерфейса
    var controlsVisible by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp,
                beyondBoundsPageCount = 1,
                userScrollEnabled = !isCurrentPageZoomed,
                key = { page -> if (page < mediaMessages.size) mediaMessages[page].id else page }
            ) { page ->
                val message = mediaMessages.getOrNull(page) ?: return@HorizontalPager
                val displayPath = message.localFilePath
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (displayPath != null) {
                        if (message.type == MessageType.IMAGE) {
                            ZoomableImage(
                                message = message,
                                isCurrentPage = pagerState.currentPage == page,
                                onZoomChanged = { isZoomed -> zoomedPages[page] = isZoomed },
                                onTap = { controlsVisible = !controlsVisible }
                            )
                        } else {
                            VideoPlayerPage(
                                message = message,
                                viewModel = viewModel,
                                isCurrentPage = pagerState.currentPage == page,
                                isControlsVisible = controlsVisible,
                                onToggleControls = { controlsVisible = !controlsVisible }
                            )
                        }
                    } else {
                        DownloadPlaceholder(message, downloadingIds, downloadProgressMap, onDownload)
                    }
                }
            }

            // Синхронизированная верхняя панель
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                TopAppBar(
                    title = {
                        @Suppress("DEPRECATION")
                        Text(
                            text = stringResource(R.string.pager_count, pagerState.currentPage + 1, mediaMessages.size),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                        }
                    },
                    actions = {
                        val currentMessage = mediaMessages.getOrNull(pagerState.currentPage)
                        if (currentMessage != null) {
                            IconButton(onClick = { onDelete(currentMessage) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    ),
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
                )
            }
        }
    }
}

@Composable
fun DownloadPlaceholder(
    message: ChatMessage,
    downloadingIds: Set<Long>,
    downloadProgressMap: Map<Long, DownloadProgress>,
    onDownload: (ChatMessage) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        val progress = downloadProgressMap[message.id]
        if (progress != null) {
            FileTransferProgress(progress = progress.progress, status = progress.status)
        } else if (downloadingIds.contains(message.id)) {
            CircularProgressIndicator(color = Color.White)
            Text(stringResource(R.string.downloading), color = Color.White, modifier = Modifier.padding(top = 8.dp))
        } else {
            IconButton(onClick = { onDownload(message) }, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download), tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Text(stringResource(R.string.file_not_downloaded), color = Color.White)
        }
    }
}

@Composable
fun ZoomableImage(
    message: ChatMessage,
    isCurrentPage: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    val filePath = message.localFilePath ?: ""
    val coilModel = remember(filePath) { MainUiUtils.getCoilModel(message) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(isCurrentPage, filePath) {
        if (!isCurrentPage) {
            scale = 1f
            offset = Offset.Zero
            onZoomChanged(false)
        }
    }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        if (isCurrentPage) {
            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
            scale = newScale
            if (newScale > 1f) offset += offsetChange else offset = Offset.Zero
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (coilModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coilModel)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onLoading = { isLoading = true },
                onSuccess = { isLoading = false },
                onError = { isLoading = false },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .transformable(state = state, enabled = scale > 1.01f)
                    .pointerInput(filePath) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            }
                        )
                    }
            )
        }

        if (isLoading) {
            CircularProgressIndicator(color = Color.Red)
        }
    }
}

@Composable
fun VideoPlayerPage(
    message: ChatMessage,
    viewModel: MainViewModel,
    isCurrentPage: Boolean,
    isControlsVisible: Boolean,
    onToggleControls: () -> Unit
) {
    val context = LocalContext.current
    val finalPath = message.localFilePath ?: return

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }

    val exoPlayer = remember(message.id) {
        val encryptedFactory = EncryptedDataSourceFactory(viewModel.encryptionManager, viewModel.localKey)
        val dataSourceFactory = if (finalPath.endsWith(".enc")) encryptedFactory else DefaultDataSource.Factory(context)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                val uri = finalPath.toUri()
                val mimeType = message.mimeType ?: if (finalPath.endsWith(".mp4") || finalPath.endsWith(".mp4.enc")) MimeTypes.VIDEO_MP4 else null
                val mediaItem = MediaItem.Builder().setUri(uri).setMimeType(mimeType).build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = isCurrentPage
            }
    }

    // Автоскрытие управления
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(3500)
            onToggleControls()
        }
    }

    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) exoPlayer.pause() }

    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                isLoading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) duration = exoPlayer.duration
            }
        }
        exoPlayer.addListener(listener)
        while (true) {
            currentPosition = exoPlayer.currentPosition
            delay(200)
        }
    }

    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        val isRightSide = offset.x > size.width / 2
                        if (isRightSide) {
                            exoPlayer.seekTo(exoPlayer.currentPosition + 10000)
                        } else {
                            exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            CircularProgressIndicator(color = Color.Red)
        }

        // Центральная кнопка Play/Pause
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            IconButton(
                onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.White
                )
            }
        }

        // Нижняя панель управления
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 60.dp)
            ) {
                Slider(
                    value = currentPosition.toFloat(),
                    onValueChange = { exoPlayer.seekTo(it.toLong()) },
                    valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Red,
                        activeTrackColor = Color.Red,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(MainUiUtils.formatDurationMillis(currentPosition), color = Color.White, fontSize = 12.sp)
                    Text(MainUiUtils.formatDurationMillis(duration), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}
