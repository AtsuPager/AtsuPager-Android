package com.nax.atsupager.data.di

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.nax.atsupager.data.network.EncryptedImageFetcher
import com.nax.atsupager.security.EncryptionManager
import com.nax.atsupager.security.KeyStorageManager
import com.nax.atsupager.data.network.UserRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        encryptionManager: EncryptionManager,
        keyStorageManager: KeyStorageManager,
        userRepository: UserRepository
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                // Поддержка расшифровки для File
                add(EncryptedImageFetcher.FileFactory(encryptionManager, keyStorageManager, userRepository))
                // Поддержка расшифровки напрямую для ChatMessage
                add(EncryptedImageFetcher.MessageFactory(encryptionManager, keyStorageManager, userRepository))
            }
            .build()
    }
}