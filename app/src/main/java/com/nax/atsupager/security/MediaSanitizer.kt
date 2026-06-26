package com.nax.atsupager.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaSanitizer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Декодирует изображение, применяет поворот и сжимает его заново в поток шифрования.
     * Это полностью удаляет EXIF-метаданные.
     */
    fun sanitizeImage(input: InputStream, output: OutputStream, rotation: Int = 0, quality: Int = 90) {
        try {
            val bitmap = BitmapFactory.decodeStream(input)
            if (bitmap != null) {
                val processedBitmap = if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    if (rotated != bitmap) bitmap.recycle()
                    rotated
                } else {
                    bitmap
                }

                // compress() создает новый поток байтов, игнорируя исходные метаданные
                processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                processedBitmap.recycle()
            } else {
                Log.e("MediaSanitizer", "Failed to decode bitmap for sanitization")
            }
        } catch (e: Exception) {
            Log.e("MediaSanitizer", "Image sanitization error", e)
            throw e
        }
    }

    /**
     * Выполняет ремуксинг видео (копирование потоков в новый контейнер).
     * Это удаляет метаданные контейнера и сохраняет ориентацию.
     */
    fun sanitizeVideo(input: InputStream, output: OutputStream) {
        val tempInputFile = File(context.cacheDir, "sanitizer_in_${UUID.randomUUID()}.tmp")
        val tempOutputFile = File(context.cacheDir, "sanitizer_out_${UUID.randomUUID()}.tmp")

        try {
            // Сохраняем InputStream во временный файл, так как MediaExtractor работает с файлами
            tempInputFile.outputStream().use { input.copyTo(it) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempInputFile.absolutePath)

            val muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Читаем ориентацию из метаданных
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempInputFile.absolutePath)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotation != null) {
                muxer.setOrientationHint(rotation.toInt())
            }
            retriever.release()

            val trackCount = extractor.trackCount
            val trackMap = HashMap<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                // Копируем только видео и аудио дорожки
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val newTrackIndex = muxer.addTrack(format)
                    trackMap[i] = newTrackIndex
                }
            }

            muxer.start()

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val newTrackIndex = trackMap[trackIndex]
                if (newTrackIndex != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(newTrackIndex, buffer, bufferInfo)
                }
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            // Записываем результат в исходящий поток (который обычно ведет к шифрованию)
            tempOutputFile.inputStream().use { it.copyTo(output) }

        } catch (e: Exception) {
            Log.e("MediaSanitizer", "Video sanitization error", e)
            throw e
        } finally {
            tempInputFile.delete()
            tempOutputFile.delete()
        }
    }
}
