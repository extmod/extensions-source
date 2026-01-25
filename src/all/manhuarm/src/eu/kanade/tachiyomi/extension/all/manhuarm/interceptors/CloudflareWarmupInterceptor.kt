package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

class CloudflareWarmupInterceptor(
    private val baseUrl: String,
    private val headers: Headers,
) : Interceptor {

    private val isWarmedUp = AtomicBoolean(false)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // Skip warmup untuk JSON requests
        if (request.url.toString().contains("/ocr-data/") || 
            request.url.toString().endsWith(".json")) {
            return chain.proceed(request)
        }
        
        val response = chain.proceed(request)

        // Hanya warmup jika benar-benar error Cloudflare (503, 403)
        if (response.code in listOf(403, 503) && !isWarmedUp.getAndSet(true)) {
            // Jangan close response dulu, baca body dulu
            val responseBody = try {
                response.peekBody(Long.MAX_VALUE).string()
            } catch (e: Exception) {
                ""
            }
            
            // Cek apakah benar-benar Cloudflare challenge
            if (responseBody.contains("cloudflare", ignoreCase = true) || 
                responseBody.contains("challenge", ignoreCase = true)) {
                
                response.close()
                
                try {
                    val warmupRequest = GET(baseUrl, headers)
                    chain.proceed(warmupRequest).close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Retry original request
                return chain.proceed(request)
            }
        }

        return response
    }

    fun reset() {
        isWarmedUp.set(false)
    }
}