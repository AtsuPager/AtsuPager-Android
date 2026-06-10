package com.nax.atsupager.data.network

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nax.atsupager.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "AudioRecorder"

@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager
) {
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var writeSide: ParcelFileDescriptor? = null

    fun getBaseDir(): File = context.filesDir

    /**
     * Запускает запись аудио напрямую в зашифрованный файл.
     * Данные шифруются в RAM перед записью на диск.
     * 
     * @param outputFile Целевой файл (обычно .enc)
     * @param localKey Ключ шифрования пользователя
     * @param bitRate Битрейт аудио (например, 64000 для 64kbps)
     */
    fun start(outputFile: File, localKey: ByteArray, bitRate: Int = 64000): Boolean {
        if (recorder != null) {
            stop()
        }

        audioFile = outputFile

        try {
            // Создаем канал (Pipe) для передачи данных от рекордера к шифратору
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            writeSide = pipe[1]

            // Фоновый поток для дешифровки "на лету"
            thread(name = "AudioEncryptionThread", isDaemon = true) {
                try {
                    // Используем AutoCloseInputStream для автоматического закрытия дескриптора
                    val inputStream = ParcelFileDescriptor.AutoCloseInputStream(readSide)
                    val fileOutputStream = FileOutputStream(outputFile)
                    
                    // Получаем шифрующий поток из нашего менеджера безопасности
                    encryptionManager.getEncryptingStreamForStorage(fileOutputStream, localKey)?.use { encryptedOut ->
                        inputStream.copyTo(encryptedOut)
                        encryptedOut.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Zero-disk encryption failed", e)
                }
            }

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // AAC_ADTS идеален для стриминга, так как не требует перемещения по файлу для записи метаданных
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(bitRate)
                setAudioSamplingRate(44100)
                setOutputFile(writeSide!!.fileDescriptor)
                prepare()
                start()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encrypted recording", e)
            cleanup()
            return false
        }
    }

    fun stop(): File? {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop error (possibly too short record)")
        }
        recorder = null
        
        // Закрытие передающей стороны канала приведет к завершению читающего потока и закрытию файла
        try {
            writeSide?.close()
        } catch (_: Exception) {}
        writeSide = null

        val result = audioFile
        audioFile = null
        return result
    }

    private fun cleanup() {
        audioFile?.delete()
        audioFile = null
        try { writeSide?.close() } catch (_: Exception) {}
        writeSide = null
        recorder = null
    }

    fun getMaxAmplitude(): Int {
        return recorder?.maxAmplitude ?: 0
    }
}
