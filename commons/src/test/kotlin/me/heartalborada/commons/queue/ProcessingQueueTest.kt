package me.heartalborada.commons.queue

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessingQueueTest {
    @Test
    fun `snapshots expose stable ids positions and progress`() = runBlocking {
        val queue = ProcessingQueue<Long, String, Unit>(
            globalCapacity = 2,
            taskId = { "task-$it" },
        )
        queue.putOrJoin(1L, "first", Unit)
        queue.putOrJoin(1L, "second", Unit)

        val queued = queue.snapshots(1L)
        assertEquals(listOf("task-first", "task-second"), queued.map { it.id })
        assertEquals(listOf(1, 2), queued.map { it.position })

        queue.take()
        queue.updateProgress("first", "downloading", 42)
        val processing = queue.snapshots(1L).first { it.id == "task-first" }
        assertEquals(ProcessingQueue.TaskState.PROCESSING, processing.state)
        assertEquals("downloading", processing.progress.stage)
        assertEquals(42, processing.progress.percent)
        assertNull(processing.position)
    }

    @Test
    fun `cancelling a shared task only removes that subscriber`() = runBlocking {
        val queue = ProcessingQueue<Long, String, String>(globalCapacity = 2, taskId = { it })
        queue.putOrJoin(1L, "shared", "one")
        queue.putOrJoin(2L, "shared", "two")

        assertEquals(ProcessingQueue.CancelStatus.UNSUBSCRIBED, queue.cancel(1L, "shared").status)
        assertEquals(listOf(2L), queue.getSubscribers("shared").map { it.first })
    }

    @Test
    fun `cancelled queued entries are skipped by consumers`() = runBlocking {
        val queue = ProcessingQueue<Long, String, Unit>(globalCapacity = 2, taskId = { it })
        queue.putOrJoin(1L, "cancelled", Unit)
        queue.putOrJoin(2L, "kept", Unit)

        assertEquals(ProcessingQueue.CancelStatus.CANCELLED, queue.cancel(1L, "cancelled").status)
        assertEquals("kept", queue.take().second)
    }

    @Test
    fun `delivering tasks remain visible and can drop a pending subscriber`() = runBlocking {
        val queue = ProcessingQueue<Long, String, String>(globalCapacity = 2, taskId = { it })
        queue.putOrJoin(1L, "shared", "one")
        queue.putOrJoin(2L, "shared", "two")
        queue.take()
        queue.sealAndGetSubscribers("shared")

        assertEquals(ProcessingQueue.TaskState.DELIVERING, queue.snapshots(1L).single().state)
        assertEquals(ProcessingQueue.CancelStatus.UNSUBSCRIBED, queue.cancel(1L, "shared").status)
        assertEquals(false, queue.isSubscribed("shared", 1L))
        assertEquals(true, queue.isSubscribed("shared", 2L))
    }

    @Test
    fun `duplicate task stays reserved until processing completes`() = runBlocking {
        val queue = ProcessingQueue<Long, String, Unit>(globalCapacity = 2)

        assertEquals(ProcessingQueue.PutStatus.SUCCESS, queue.put(1L, "gallery", Unit))
        assertEquals(ProcessingQueue.PutStatus.DUPLICATE_TASK, queue.put(2L, "gallery", Unit))

        val (userId, task) = queue.take()
        assertEquals(0, queue.getCurrentQueueSize())
        assertEquals(ProcessingQueue.PutStatus.DUPLICATE_TASK, queue.put(2L, "gallery", Unit))

        queue.complete(userId, task)
        assertEquals(ProcessingQueue.PutStatus.SUCCESS, queue.put(2L, "gallery", Unit))
    }

    @Test
    fun `full queues and per-user limits reject tasks without adding them`() = runBlocking {
        val fullQueue = ProcessingQueue<Long, String, Unit>(globalCapacity = 1)
        assertEquals(ProcessingQueue.PutStatus.SUCCESS, fullQueue.put(1L, "first", Unit))
        assertEquals(ProcessingQueue.PutStatus.QUEUE_FULL, fullQueue.put(2L, "second", Unit))
        assertEquals(1, fullQueue.getCurrentQueueSize())

        val userLimitedQueue = ProcessingQueue<Long, String, Unit>(globalCapacity = 2, userCapacity = 1)
        assertEquals(ProcessingQueue.PutStatus.SUCCESS, userLimitedQueue.put(1L, "first", Unit))
        assertEquals(ProcessingQueue.PutStatus.USER_QUEUE_FULL, userLimitedQueue.put(1L, "second", Unit))
        assertEquals(1, userLimitedQueue.getCurrentQueueSize())
    }

    @Test
    fun `concurrent producers reserve a shared task exactly once`() = runBlocking {
        val queue = ProcessingQueue<Long, String, Unit>(globalCapacity = 100)

        val results = coroutineScope {
            (1L..100L).map { userID ->
                async {
                    queue.put(userID, "shared-gallery", Unit)
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == ProcessingQueue.PutStatus.SUCCESS })
        assertEquals(99, results.count { it == ProcessingQueue.PutStatus.DUPLICATE_TASK })
        assertEquals(1, queue.getCurrentQueueSize())
    }

    @Test
    fun `different users can join one active task concurrently`() = runBlocking {
        val queue = ProcessingQueue<Long, String, String>(globalCapacity = 100)

        val results = coroutineScope {
            (1L..100L).map { userID ->
                async {
                    queue.putOrJoin(userID, "shared-gallery", "subscriber-$userID")
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == ProcessingQueue.PutStatus.SUCCESS })
        assertEquals(99, results.count { it == ProcessingQueue.PutStatus.JOINED_TASK })
        assertEquals(1, queue.getCurrentQueueSize())
        assertEquals(100, queue.getSubscribers("shared-gallery").size)

        val (_, task) = queue.take()
        val subscribers = queue.completeAndGetSubscribers(task)
        assertEquals(100, subscribers.size)
        assertEquals((1L..100L).toSet(), subscribers.map { it.first }.toSet())
        assertEquals(
            (1L..100L).map { "subscriber-$it" }.toSet(),
            subscribers.map { it.second }.toSet(),
        )
    }

    @Test
    fun `same user cannot subscribe to one task twice`() = runBlocking {
        val queue = ProcessingQueue<Long, String, Unit>(globalCapacity = 2)

        assertEquals(ProcessingQueue.PutStatus.SUCCESS, queue.putOrJoin(1L, "gallery", Unit))
        assertEquals(ProcessingQueue.PutStatus.DUPLICATE_TASK, queue.putOrJoin(1L, "gallery", Unit))
        assertEquals(ProcessingQueue.PutStatus.JOINED_TASK, queue.putOrJoin(2L, "gallery", Unit))
    }

    @Test
    fun `sealed task remains reserved while subscribers are being delivered`() = runBlocking {
        val queue = ProcessingQueue<Long, String, String>(globalCapacity = 2)
        assertEquals(ProcessingQueue.PutStatus.SUCCESS, queue.putOrJoin(1L, "gallery", "first"))
        assertEquals(ProcessingQueue.PutStatus.JOINED_TASK, queue.putOrJoin(2L, "gallery", "second"))
        queue.take()

        val subscribers = queue.sealAndGetSubscribers("gallery")

        assertEquals(setOf("first", "second"), subscribers.map { it.second }.toSet())
        assertEquals(
            ProcessingQueue.PutStatus.DUPLICATE_TASK,
            queue.putOrJoin(3L, "gallery", "late"),
        )
        queue.completeSealed("gallery")
        assertEquals(ProcessingQueue.PutStatus.SUCCESS, queue.putOrJoin(3L, "gallery", "retry"))
    }
}
