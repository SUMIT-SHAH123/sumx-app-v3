package com.sumx.app.data

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.sumx.app.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/** One shared Api (one token-refresh lock) and one image loader that authenticates requests to our own server. */
object Graph {
    @Volatile private var instance: Api? = null
    @Volatile private var loader: ImageLoader? = null

    fun api(ctx: Context): Api = instance ?: synchronized(this) {
        instance ?: Api(TokenStore(ctx.applicationContext)).also { instance = it }
    }

    fun imageLoader(ctx: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: build(ctx.applicationContext).also { loader = it }
    }

    private fun build(ctx: Context): ImageLoader {
        val api = api(ctx)
        fun Request.auth(t: String?) = if (t == null) this else newBuilder().header("Authorization", "Bearer $t").build()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (!req.url.toString().startsWith(BuildConfig.API_BASE_URL)) return@Interceptor chain.proceed(req) // never send our token to other hosts
            val resp = chain.proceed(req.auth(api.currentAccess()))
            if (resp.code == 401) { resp.close(); chain.proceed(req.auth(runBlocking { api.accessToken() })) } else resp
        }).build()
        return ImageLoader.Builder(ctx).okHttpClient(http)
            .components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }
            .crossfade(true).build()
    }
}
