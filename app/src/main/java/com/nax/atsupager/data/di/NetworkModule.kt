package com.nax.atsupager.data.di

import android.content.Context
import com.franmontiel.persistentcookiejar.PersistentCookieJar
import com.franmontiel.persistentcookiejar.cache.SetCookieCache
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor
import com.nax.atsupager.BuildConfig
import com.nax.atsupager.data.network.FileApiService
import com.nax.atsupager.security.ProxyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RegularOkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FileOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): CookieJar {
        return PersistentCookieJar(SetCookieCache(), SharedPrefsCookiePersistor(context))
    }

    @Provides
    @Singleton
    @RegularOkHttpClient
    fun provideOkHttpClient(cookieJar: CookieJar, proxyManager: ProxyManager): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .proxy(proxyManager.getProxy())
            .cache(null) // ПРИНУДИТЕЛЬНО: Отключаем дисковый кэш для сообщений
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor {
                val request = it.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:102.0) Gecko/102.0 Firefox/102.0")
                    .build()
                it.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }) // Уровень NONE в проде
            .build()
    }

    @Provides
    @Singleton
    @FileOkHttpClient
    fun provideFileOkHttpClient(cookieJar: CookieJar, proxyManager: ProxyManager): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .proxy(proxyManager.getProxy())
            .cache(null) // ПРИНУДИТЕЛЬНО: Отключаем кэш для файлов
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor {
                val request = it.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Android 10; Mobile; rv:102.0) Gecko/102.0 Firefox/102.0")
                    .header("Referer", BuildConfig.VPS_URL)
                    .build()
                it.proceed(request)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideFileApiService(@FileOkHttpClient okHttpClient: OkHttpClient): FileApiService {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.VPS_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FileApiService::class.java)
    }
}
