package me.heartalborada.commons.queue

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingQueueTest {
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
}
