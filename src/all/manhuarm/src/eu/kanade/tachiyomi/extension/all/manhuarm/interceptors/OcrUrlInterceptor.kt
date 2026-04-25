package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OcrUrlInterceptor(private val headers: Headers, private val client: OkHttpClient) {

    private val context: Application by injectLazy()

    private val handler = Handler(Looper.getMainLooper())

    data class OcrRequest(val url: String, val body: String, val extraHeaders: Map<String, String>)

    private val bridgeName = ('a'..'z').shuffled().take(10).joinToString("")

    private fun syncCookiesToWebView(url: String) {
        try {
            val cookies = client.cookieJar.loadForRequest(url.toHttpUrl())
            if (cookies.isEmpty()) return
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookies.forEach {
                cookieManager.setCookie(url, "${it.name}=${it.value}")
            }
            cookieManager.flush()
        } catch (_: Exception) {}
    }

    fun getOcrRequest(url: String): OcrRequest? {
        val latch = CountDownLatch(1)
        var ocrRequest: OcrRequest? = null
        var webView: WebView? = null

        handler.post {
            syncCookiesToWebView(url)

            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = headers["User-Agent"]
            }

            webview.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onFetch(url: String, body: String, token: String, nonce: String, timestamp: String) {
                        if (ocrRequest == null && url.contains("fetch-ocr.php")) {
                            ocrRequest = OcrRequest(
                                url = url,
                                body = body,
                                extraHeaders = mapOf(
                                    "X-Gate-Token" to token,
                                    "X-Gate-Nonce" to nonce,
                                    "X-Gate-Timestamp" to timestamp,
                                ),
                            )
                            latch.countDown()
                        }
                    }
                },
                bridgeName,
            )

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val js = """
                        (function() {
                            const oldFetch = window.fetch;
                            window.fetch = function() {
                                const url = arguments[0];
                                const options = arguments[1] || {};
                                if (url.includes('fetch-ocr.php')) {
                                    const h = options.headers || {};
                                    $bridgeName.onFetch(
                                        url,
                                        options.body || '',
                                        h['X-Gate-Token'] || '',
                                        h['X-Gate-Nonce'] || '',
                                        String(h['X-Gate-Timestamp'] || '')
                                    );
                                }
                                return oldFetch.apply(this, arguments);
                            };
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            webview.loadUrl(url, headers.toMultimap().mapValues { it.value.first() })
        }

        val completed = latch.await(15, TimeUnit.SECONDS)

        handler.post {
            webView?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            webView = null
        }

        if (!completed) return null

        return ocrRequest
    }
}
