import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainHealthTest {
    @Test
    fun `endpoint probes run concurrently and preserve their names`() = runBlocking {
        val bothStarted = CountDownLatch(2)
        val endpoints = linkedMapOf(
            "eh" to "https://e-hentai.org/",
            "jm" to "https://jm.example/",
        )

        val results = probeHealthEndpoints(endpoints) { endpoint ->
            bothStarted.countDown()
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS), "Endpoint probes ran serially")
            "ok:$endpoint"
        }

        assertEquals(
            linkedMapOf(
                "eh" to "ok:https://e-hentai.org/",
                "jm" to "ok:https://jm.example/",
            ),
            results,
        )
    }
}
