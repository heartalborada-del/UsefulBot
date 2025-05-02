package me.heartalborada.commons.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

class RetryInterceptor(
    private val delay: Duration = 200.milliseconds,
    private val delay429: Duration = 500.milliseconds,
    private val maxRetries: Int = 3,
    private vararg val waitHosts: String
) : Interceptor {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response?
        var exception: Throwable? = null
        val retryCount = AtomicInteger(0)
        if (waitHosts.contains(request.url.host)) {
            Thread.sleep(200)
        }
        while (retryCount.get() < maxRetries) {
            try {
                response = chain.proceed(request)
                if (response.isSuccessful) {
                    return response
                }
                if (response.code == 429) {
                    Thread.sleep(delay429.toLong(DurationUnit.MILLISECONDS))
                } else {
                    Thread.sleep(delay.toLong(DurationUnit.MILLISECONDS))
                }
                retryCount.addAndGet(1)
                response.close()
            } catch (e: Throwable) {
                exception = e
                retryCount.addAndGet(1)
                Thread.sleep(delay.toLong(DurationUnit.MILLISECONDS))
            } finally {
                if (retryCount.get() != 0)
                    logger.debug("Retrying url: [{}]{}, Count: {}", request.method, request.url, retryCount)
            }
        }
        throw exception ?: IOException("Run out of retries")
    }
}