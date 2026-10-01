package com.imgink.uploader.data

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

data class ApiResponse<T>(
    val code: Int,
    val msg: String? = null,
    val time: Long? = null,
    val data: T? = null
)

data class UploadData(
    val id: Long? = null,
    val name: String? = null,
    val url: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val quota: String? = null,
    val use_quota: String? = null
)

data class ImagesData(
    val total: Int? = null,
    val per_page: String? = null,
    val current_page: Int? = null,
    val last_page: Int? = null,
    val data: List<ImgItem>? = null
)

data class ImgItem(
    val id: Long? = null,
    val name: String? = null,
    val pathname: String? = null,
    val size: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val upload_date: String? = null
)

interface ImgInkService {

    @Multipart
    @POST("api/upload")
    suspend fun upload(
        @Header("token") token: String,
        @Part image: MultipartBody.Part
    ): ApiResponse<UploadData>

    @FormUrlEncoded
    @POST("api/images")
    suspend fun images(
        @Header("token") token: String,
        @Field("page") page: Int,
        @Field("rows") rows: Int
    ): ApiResponse<ImagesData>

    @FormUrlEncoded
    @POST("api/delete")
    suspend fun delete(
        @Header("token") token: String,
        @Field("id") id: String
    ): ApiResponse<Any>
}

object ApiClient {
    const val DEFAULT_BASE = "https://img.ink/"

    @Volatile private var cachedBase: String? = null
    @Volatile private var cached: ImgInkService? = null

    fun service(baseUrl: String): ImgInkService {
        val norm = when {
            baseUrl.isBlank() -> DEFAULT_BASE
            baseUrl.endsWith("/") -> baseUrl
            else -> "$baseUrl/"
        }
        val cur = cached
        if (cur != null && cachedBase == norm) return cur
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        val s = Retrofit.Builder()
            .baseUrl(norm)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ImgInkService::class.java)
        cached = s
        cachedBase = norm
        return s
    }
}
