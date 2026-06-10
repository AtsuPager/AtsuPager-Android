package com.nax.atsupager.data.network

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import com.nax.atsupager.data.db.ChatMessage
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlayer"

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isDecrypting: Boolean = false, // Remains for compatibility, but streaming is immediate
    val decryptionProgress: Float = 0f,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val messageId: Long? = null
) {
    val progress: Float
        get() = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
}

@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val keyStorageManager: KeyStorageManager,
    private val userRepository: UserRepository
) {
    private var player: ExoPlayer? = null
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressUpdates() else progressJob?.cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _playbackState.update { it.copy(isPlaying = false, currentPosition = 0) }
                player?.seekTo(0)
                player?.pause()
            } else if (playbackState == Player.STATE_READY) {
                player?.let { p ->
                    if (p.duration > 0) {
                        _playbackState.update { it.copy(duration = p.duration) }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            stop()
        }
    }

    private fun initPlayer(factory: DataSource.Factory) {
        if (player == null) {
            player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build().apply {
                    addListener(listener)
                }
        }
    }

    fun play(message: ChatMessage) {
        val currentState = _playbackState.value
        
        if (currentState.messageId == message.id) {
            player?.let {
                if (it.isPlaying) pause() else it.play()
                return
            }
        }

        stop()

        val path = message.localFilePath ?: message.fileUrl ?: return
        val isEncrypted = path.endsWith(".enc")
        
        _playbackState.update { 
            it.copy(
                messageId = message.id, 
                isPlaying = false,
                currentPosition = 0,
                duration = (message.audioDuration?.toLong() ?: 0L) * 1000L
            ) 
        }

        playerScope.launch {
            try {
                val dataSourceFactory: DataSource.Factory = if (isEncrypted) {
                    val userId = userRepository.getCurrentUserId() ?: ""
                    val key = keyStorageManager.getMediaEncryptionKey(userId)
                    EncryptedDataSourceFactory(encryptionManager, key)
                } else {
                    DefaultDataSource.Factory(context)
                }

                startPlaybackInternal(dataSourceFactory, message, path)
            } catch (e: Exception) {
                Log.e(TAG, "Prepare failed", e)
            }
        }
    }

    private fun startPlaybackInternal(factory: DataSource.Factory, message: ChatMessage, path: String) {
        player?.release()
        player = null
        initPlayer(factory)
        
        val currentPlayer = player ?: return
        
        val initialDuration = (message.audioDuration?.toLong() ?: 0L) * 1000L
        _playbackState.update { it.copy(messageId = message.id, duration = initialDuration) }

        val mimeType = message.mimeType ?: if (path.endsWith(".m4a") || path.endsWith(".m4a.enc")) MimeTypes.AUDIO_MP4 else MimeTypes.AUDIO_MPEG

        val mediaItem = MediaItem.Builder()
            .setUri(path.toUri())
            .setMimeType(mimeType)
            .build()

        currentPlayer.setMediaItem(mediaItem)
        currentPlayer.prepare()
        currentPlayer.play()
    }

    fun stop() {
        progressJob?.cancel()
        player?.stop()
        player?.release()
        player = null
        _playbackState.update { PlaybackState() }
    }

    fun stopAndReset() {
        player?.pause()
        player?.seekTo(0)
        _playbackState.update { it.copy(isPlaying = false, currentPosition = 0) }
    }

    fun pause() {
        player?.pause()
        _playbackState.update { it.copy(isPlaying = false) }
    }

    fun seekTo(progress: Float) {
        player?.let {
            val d = if (it.duration > 0) it.duration else _playbackState.value.duration
            if (d > 0) {
                val newPosition = (d * progress).toLong()
                it.seekTo(newPosition)
                _playbackState.update { state -> state.copy(currentPosition = newPosition) }
            }
        }
    }
	
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = playerScope.launch {
            while (isActive) {
                player?.let { p ->
                    if (p.isPlaying) {
                        _playbackState.update {
                            it.copy(
                                currentPosition = p.currentPosition,
                                duration = if (p.duration > 0) p.duration else it.duration
                            )
                        }
                    }
                }
                delay(100)
            }
        }
    }

    fun release() {
        stop()
        playerScope.cancel()
    }
}
