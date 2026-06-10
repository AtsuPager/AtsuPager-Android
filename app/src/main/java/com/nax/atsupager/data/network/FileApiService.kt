package com.nax.atsupager.data.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class FileUploadResponse(val url: String)

interface FileApiService {
    @Multipart
    @POST("upload")
    suspend fun uploadFile(
        @Part("folder") folder: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<FileUploadResponse>

    // Получение настроек ICE (STUN/TURN) с нового VPS
    @GET("ice-servers")
    suspend fun getIceServers(): Response<IceServerResponse>
}
