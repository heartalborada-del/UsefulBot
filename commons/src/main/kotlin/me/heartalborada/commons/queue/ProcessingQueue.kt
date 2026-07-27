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
    private val taskSubscribers = mutableMapOf<T, MutableList<Pair<K, E>>>()

    @Volatile
    private var currentQueueSize = 0

    init {
        require(globalCapacity > 0) { "Global queue capacity must be greater than zero." }
        require(userCapacity > 0) { "User queue capacity must be greater than zero." }
    }

    suspend fun put(userId: K, task: T, extra: E): PutStatus =
        putInternal(userId, task, extra, joinActiveTask = false)

    suspend fun putOrJoin(userId: K, task: T, extra: E): PutStatus =
        putInternal(userId, task, extra, joinActiveTask = true)

    private suspend fun putInternal(
        userId: K,
        task: T,
        extra: E,
        joinActiveTask: Boolean,
    ): PutStatus {
        val reservation = mutex.withLock {
            when {
                task in activeTasks && !joinActiveTask -> PutStatus.DUPLICATE_TASK
                task in activeTasks && taskSubscribers[task].orEmpty().any { it.first == userId } ->
                    PutStatus.DUPLICATE_TASK
                userTaskCounts.getOrDefault(userId, 0) >= userCapacity -> PutStatus.USER_QUEUE_FULL
                task in activeTasks -> {
                    taskSubscribers.getValue(task).add(userId to extra)
                    userTaskCounts[userId] = userTaskCounts.getOrDefault(userId, 0) + 1
                    PutStatus.JOINED_TASK
                }
                currentQueueSize >= globalCapacity -> PutStatus.QUEUE_FULL
                else -> {
                    activeTasks.add(task)
                    taskSubscribers[task] = mutableListOf(userId to extra)
                    userTaskCounts[userId] = userTaskCounts.getOrDefault(userId, 0) + 1
                    currentQueueSize++
                    PutStatus.SUCCESS
                }
            }
        }
        if (reservation != PutStatus.SUCCESS) {
            return reservation
        }

        if (channel.trySend(Triple(userId, task, extra)).isSuccess) {
            return PutStatus.SUCCESS
        }

        mutex.withLock {
            activeTasks.remove(task)
            val subscribers = taskSubscribers.remove(task).orEmpty()
            if (subscribers.isEmpty()) {
                decrementUserTaskCount(userId)
            } else {
                subscribers.forEach { (subscriber, _) ->
                    decrementUserTaskCount(subscriber)
                }
            }
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
                val subscribers = taskSubscribers.remove(task).orEmpty()
                if (subscribers.isEmpty()) {
                    decrementUserTaskCount(userId)
                } else {
                    subscribers.forEach { (subscriber, _) ->
                        decrementUserTaskCount(subscriber)
                    }
                }
            }
        }
    }

    suspend fun completeAndGetSubscribers(task: T): List<Pair<K, E>> =
        mutex.withLock {
            if (!activeTasks.remove(task)) {
                return@withLock emptyList()
            }
            taskSubscribers.remove(task)
                .orEmpty()
                .also { subscribers ->
                    subscribers.forEach { (subscriber, _) ->
                        decrementUserTaskCount(subscriber)
                    }
                }
        }

    suspend fun getSubscribers(task: T): List<Pair<K, E>> =
        mutex.withLock {
            taskSubscribers[task].orEmpty().toList()
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
        JOINED_TASK,
        SUCCESS,
        FAILURE
    }
}
