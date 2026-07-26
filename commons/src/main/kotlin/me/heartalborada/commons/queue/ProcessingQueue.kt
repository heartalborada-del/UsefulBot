package me.heartalborada.commons.queue

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProcessingQueue<K, T, E>(
    private val globalCapacity: Int = 2,
    private val userCapacity: Int = 5
) {
    private val channel = Channel<Triple<K, T, E>>(globalCapacity)
    private val mutex = Mutex()
    private val activeTasks = mutableSetOf<T>()
    private val userTaskCounts = mutableMapOf<K, Int>()

    @Volatile
    private var currentQueueSize = 0

    init {
        require(globalCapacity > 0) { "Global queue capacity must be greater than zero." }
        require(userCapacity > 0) { "User queue capacity must be greater than zero." }
    }

    suspend fun put(userId: K, task: T, extra: E): PutStatus {
        val rejection = mutex.withLock {
            when {
                task in activeTasks -> PutStatus.DUPLICATE_TASK
                userTaskCounts.getOrDefault(userId, 0) >= userCapacity -> PutStatus.USER_QUEUE_FULL
                currentQueueSize >= globalCapacity -> PutStatus.QUEUE_FULL
                else -> {
                    activeTasks.add(task)
                    userTaskCounts[userId] = userTaskCounts.getOrDefault(userId, 0) + 1
                    currentQueueSize++
                    null
                }
            }
        }
        if (rejection != null) {
            return rejection
        }

        if (channel.trySend(Triple(userId, task, extra)).isSuccess) {
            return PutStatus.SUCCESS
        }

        mutex.withLock {
            activeTasks.remove(task)
            decrementUserTaskCount(userId)
            currentQueueSize--
        }
        return PutStatus.FAILURE
    }

    suspend fun take(): Triple<K, T, E> {
        val item = channel.receive()
        mutex.withLock {
            currentQueueSize--
        }
        return item
    }

    suspend fun complete(userId: K, task: T) {
        mutex.withLock {
            if (activeTasks.remove(task)) {
                decrementUserTaskCount(userId)
            }
        }
    }

    fun getCurrentQueueSize(): Int = currentQueueSize

    private fun decrementUserTaskCount(userId: K) {
        val remaining = userTaskCounts.getOrDefault(userId, 0) - 1
        if (remaining > 0) {
            userTaskCounts[userId] = remaining
        } else {
            userTaskCounts.remove(userId)
        }
    }

    enum class PutStatus {
        QUEUE_FULL,
        USER_QUEUE_FULL,
        DUPLICATE_TASK,
        SUCCESS,
        FAILURE
    }
}
