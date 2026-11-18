package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.v2ray.ang.contracts.IDialerService
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

internal object DialerEndpointPolicy {
    private val ALLOWED_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    fun parseLoopbackDialerUrl(dialerAddr: String): String? {
        val trimmed = dialerAddr.trim()
        if (trimmed.isEmpty() ||
            trimmed.contains("://") ||
            trimmed.contains('@') ||
            trimmed.contains('/') ||
            trimmed.contains('\\') ||
            trimmed.contains('?') ||
            trimmed.contains('#') ||
            trimmed.any { it.isWhitespace() }
        ) {
            return null
        }
        val uri = runCatching { URI("http://$trimmed/") }.getOrNull() ?: return null
        val rawHost = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedHost = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        if (normalizedHost !in ALLOWED_HOSTS) {
            return null
        }
        val port = uri.port
        if (port !in 1..65535) {
            return null
        }
        val formattedHost = if (normalizedHost == "::1") "[::1]" else normalizedHost
        return "http://$formattedHost:$port/"
    }

    fun isAllowedTopLevelNavigation(dialerUrl: String, targetUrl: String?): Boolean {
        val target = targetUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val baseUri = runCatching { URI(dialerUrl) }.getOrNull() ?: return false
        val targetUri = runCatching { URI(target) }.getOrNull() ?: return false
        val baseScheme = baseUri.scheme?.lowercase(Locale.ROOT) ?: return false
        val targetScheme = targetUri.scheme?.lowercase(Locale.ROOT) ?: return false
        val baseHost = baseUri.host?.lowercase(Locale.ROOT) ?: return false
        val targetHost = targetUri.host?.lowercase(Locale.ROOT) ?: return false
        return baseScheme == targetScheme && baseHost == targetHost && baseUri.port == targetUri.port
    }

    fun isBlockedSubresourceScheme(scheme: String?): Boolean {
        val normalized = scheme?.trim()?.lowercase(Locale.ROOT) ?: return true
        return normalized != "http" &&
            normalized != "https" &&
            normalized != "ws" &&
            normalized != "wss" &&
            normalized != "data" &&
            normalized != "blob"
    }
}

class DialerWebviewService : IDialerService {
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lifecycleVersion = AtomicLong()
    private val keepAliveInterval = 30_000L // 30 seconds

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val view = webView ?: return
            view.resumeTimers()
            view.onResume()
            handler.postDelayed(this, keepAliveInterval)
        }
    }


    /**
     * Starts the WebView.
     * @param context Service context
     */
    @SuppressLint("SetJavaScriptEnabled")
    override fun start(context: Context, dialerAddr: String) = updateOnMainThread {
        stopOnMainThread()
        if (dialerAddr.isEmpty()) return@updateOnMainThread
        val dialerUrl = DialerEndpointPolicy.parseLoopbackDialerUrl(dialerAddr)
            ?: throw IllegalArgumentException("Invalid dialer loopback address")

        try {
            val view = WebView(context.applicationContext)
            webView = view
            view.apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    // Allow JS to run even if not triggered by user
                    mediaPlaybackRequiresUserGesture = false
                    // Prevent aggressive caching issues
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return !DialerEndpointPolicy.isAllowedTopLevelNavigation(dialerUrl, request?.url?.toString())
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (DialerEndpointPolicy.isBlockedSubresourceScheme(request?.url?.scheme)) {
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                403,
                                "Forbidden",
                                emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (view === webView) {
                            view?.onResume()
                            view?.resumeTimers()
                        }
                    }
                }

                loadUrl(dialerUrl)
            }
            handler.post(keepAliveRunnable)
        } catch (e: Exception) {
            cleanupFailedStart(e)
            throw e
        } catch (e: LinkageError) {
            // Missing/corrupt WebView native libraries or class initialization failures
            // throw UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError.
            // Clean up any partial WebView state while letting VirtualMachineError (e.g. OOM)
            // and ThreadDeath propagate untouched.
            cleanupFailedStart(e)
            throw e
        }
    }

    private fun cleanupFailedStart(failure: Throwable) {
        try {
            stopOnMainThread()
        } catch (cleanupError: Exception) {
            failure.addSuppressed(cleanupError)
        } catch (cleanupError: LinkageError) {
            failure.addSuppressed(cleanupError)
        }
    }

    companion object {
        internal fun isRecoverableWebViewFailure(error: Throwable): Boolean {
            return error is Exception || error is LinkageError
        }
    }

    override fun stop() = updateOnMainThread { stopOnMainThread() }

    private fun stopOnMainThread() {
        handler.removeCallbacks(keepAliveRunnable)
        val view = webView ?: return
        webView = null
        view.apply {
            try {
                stopLoading()
                pauseTimers()
                // Important to call onPause to stop internal Chromium threads properly
                onPause()
            } finally {
                destroy()
            }
        }
    }

    private fun updateOnMainThread(action: () -> Unit) {
        val version = lifecycleVersion.incrementAndGet()
        val update = {
            // A stop or replacement can overtake a worker's queued start on the main thread.
            if (lifecycleVersion.get() == version) action()
        }
        if (Looper.myLooper() == handler.looper) {
            update()
            return
        }

        // WebView requires its creating looper on all Android versions. Keep the dialer's
        // synchronous contract so reload waits for cleanup/setup and receives any failure.
        val task = FutureTask(update)
        check(handler.post(task)) { "WebView looper is shutting down" }
        try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw e
        }
    }

}
