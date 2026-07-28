package me.heartalborada.commons.queue

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProcessingQueue<K, T, E>(
    private val globalCapacity: Int = 2,
    private val userCapacity: Int = 5,
    private val taskId: (T) -> String = { it.toString() },
) {
    private val channel = Channel<Triple<K, T, E>>(globalCapacity)
    private val mutex = Mutex()
    private val activeTasks = mutableSetOf<T>()
    private val sealedTasks = mutableSetOf<T>()
    private val userTaskCounts = mutableMapOf<K, Int>()
    private val taskSubscribers = mutableMapOf<T, MutableList<Pair<K, E>>>()
    private val sealedSubscribers = mutableMapOf<T, MutableList<Pair<K, E>>>()
    private val taskStates = mutableMapOf<T, TaskState>()
    private val taskProgress = mutableMapOf<T, TaskProgress>()
    private val queueOrder = mutableListOf<T>()

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
                task in sealedTasks -> PutStatus.DUPLICATE_TASK
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
                    taskStates[task] = TaskState.QUEUED
                    taskProgress[task] = TaskProgress("queued", 0)
                    queueOrder.add(task)
                    userTaskCounts[userId] = userTaskCounts.getOrDefault(userId, 0) + 1
                    currentQueueSize++
                    PutStatus.SUCCESS
                }
            }
        }
        if (reservation != PutStatus.SUCCESS) return reservation

        if (channel.trySend(Triple(userId, task, extra)).isSuccess) return PutStatus.SUCCESS

        mutex.withLock {
            activeTasks.remove(task)
            sealedTasks.remove(task)
            val subscribers = taskSubscribers.remove(task).orEmpty()
            subscribers.ifEmpty { listOf(userId to extra) }.forEach { (subscriber, _) ->
                decrementUserTaskCount(subscriber)
            }
            removeTaskMetadata(task)
            currentQueueSize--
        }
        return PutStatus.FAILURE
    }

    suspend fun take(): Triple<K, T, E> {
        while (true) {
            val item = channel.receive()
            val accepted = mutex.withLock {
                val task = item.second
                if (task !in activeTasks || taskSubscribers[task].isNullOrEmpty()) {
                    false
                } else {
                    currentQueueSize--
                    queueOrder.remove(task)
                    taskStates[task] = TaskState.PROCESSING
                    taskProgress[task] = TaskProgress("starting", 1)
                    true
                }
            }
            if (accepted) return item
        }
    }

    suspend fun complete(userId: K, task: T) {
        mutex.withLock {
            if (activeTasks.remove(task)) {
                sealedTasks.remove(task)
                val subscribers = taskSubscribers.remove(task).orEmpty()
                subscribers.ifEmpty { listOf(userId to null) }.forEach { (subscriber, _) ->
                    decrementUserTaskCount(subscriber)
                }
                removeTaskMetadata(task)
            }
        }
    }

    suspend fun completeAndGetSubscribers(task: T): List<Pair<K, E>> = mutex.withLock {
        if (!activeTasks.remove(task)) return@withLock emptyList()
        sealedTasks.remove(task)
        sealedSubscribers.remove(task)
        taskSubscribers.remove(task).orEmpty().also { subscribers ->
            subscribers.forEach { (subscriber, _) -> decrementUserTaskCount(subscriber) }
            removeTaskMetadata(task)
            sealedSubscribers.remove(task)
        }
    }

    suspend fun sealAndGetSubscribers(task: T): List<Pair<K, E>> = mutex.withLock {
        if (task !in activeTasks || !sealedTasks.add(task)) return@withLock emptyList()
        taskStates[task] = TaskState.DELIVERING
        taskProgress[task] = TaskProgress("delivering", 95)
        taskSubscribers.remove(task).orEmpty().also { subscribers ->
            subscribers.forEach { (subscriber, _) -> decrementUserTaskCount(subscriber) }
            sealedSubscribers[task] = subscribers.toMutableList()
        }
    }

    suspend fun completeSealed(task: T) {
        mutex.withLock {
            if (sealedTasks.remove(task)) {
                activeTasks.remove(task)
                sealedSubscribers.remove(task)
                removeTaskMetadata(task)
            }
        }
    }

    suspend fun getSubscribers(task: T): List<Pair<K, E>> = mutex.withLock {
        taskSubscribers[task].orEmpty().toList()
    }

    fun getCurrentQueueSize(): Int = currentQueueSize

    suspend fun updateProgress(task: T, stage: String, percent: Int? = null) {
        mutex.withLock {
            if (task in activeTasks) {
                taskProgress[task] = TaskProgress(stage, percent?.coerceIn(0, 100))
            }
        }
    }

    suspend fun snapshots(userId: K? = null): List<TaskSnapshot<T>> = mutex.withLock {
        activeTasks.mapNotNull { task ->
            val subscribers = taskSubscribers[task] ?: sealedSubscribers[task].orEmpty()
            if (userId != null && subscribers.none { it.first == userId }) return@mapNotNull null
            TaskSnapshot(
                id = taskId(task),
                task = task,
                state = taskStates[task] ?: TaskState.PROCESSING,
                position = queueOrder.indexOf(task).takeIf { it >= 0 }?.plus(1),
                subscriberCount = subscribers.size,
                progress = taskProgress[task] ?: TaskProgress("unknown", null),
            )
        }.sortedWith(
            compareBy<TaskSnapshot<T>> { it.state != TaskState.QUEUED }
                .thenBy { it.position ?: Int.MAX_VALUE }
        )
    }

    suspend fun cancel(userId: K, id: String): CancelResult<T> = mutex.withLock {
        val task = activeTasks.firstOrNull { taskId(it).equals(id, ignoreCase = true) }
            ?: return@withLock CancelResult(CancelStatus.NOT_FOUND)
        val isSealed = taskSubscribers[task] == null && sealedSubscribers.containsKey(task)
        val subscribers = taskSubscribers[task] ?: sealedSubscribers[task]
            ?: return@withLock CancelResult(CancelStatus.NOT_FOUND)
        val subscriberIndex = subscribers.indexOfFirst { it.first == userId }
        if (subscriberIndex < 0) return@withLock CancelResult(CancelStatus.NOT_SUBSCRIBED)

        subscribers.removeAt(subscriberIndex)
        if (!isSealed) decrementUserTaskCount(userId)
        if (subscribers.isNotEmpty()) return@withLock CancelResult(CancelStatus.UNSUBSCRIBED, task)

        if (taskStates[task] == TaskState.QUEUED) {
            activeTasks.remove(task)
            queueOrder.remove(task)
            currentQueueSize--
            removeTaskMetadata(task)
            CancelResult(CancelStatus.CANCELLED, task)
        } else {
            CancelResult(CancelStatus.UNSUBSCRIBED, task)
        }
    }

    suspend fun cancelTask(id: String): CancelResult<T> = mutex.withLock {
        val task = activeTasks.firstOrNull { taskId(it).equals(id, ignoreCase = true) }
            ?: return@withLock CancelResult(CancelStatus.NOT_FOUND)
        val queuedOrProcessingSubscribers = taskSubscribers.remove(task)
        val subscribers = queuedOrProcessingSubscribers ?: sealedSubscribers.remove(task).orEmpty()
        if (queuedOrProcessingSubscribers != null) {
            subscribers.forEach { (subscriber, _) -> decrementUserTaskCount(subscriber) }
        }
        if (taskStates[task] == TaskState.QUEUED) {
            activeTasks.remove(task)
            currentQueueSize--
            removeTaskMetadata(task)
            CancelResult(CancelStatus.CANCELLED, task)
        } else {
            CancelResult(CancelStatus.UNSUBSCRIBED, task)
        }
    }

    suspend fun isSubscribed(task: T, userId: K): Boolean = mutex.withLock {
        (taskSubscribers[task] ?: sealedSubscribers[task]).orEmpty().any { it.first == userId }
    }

    private fun decrementUserTaskCount(userId: K) {
        val remaining = userTaskCounts.getOrDefault(userId, 0) - 1
        if (remaining > 0) userTaskCounts[userId] = remaining else userTaskCounts.remove(userId)
    }

    private fun removeTaskMetadata(task: T) {
        taskStates.remove(task)
        taskProgress.remove(task)
        queueOrder.remove(task)
    }

    data class TaskProgress(val stage: String, val percent: Int?)

    data class TaskSnapshot<T>(
        val id: String,
        val task: T,
        val state: TaskState,
        val position: Int?,
        val subscriberCount: Int,
        val progress: TaskProgress,
    )

    data class CancelResult<T>(val status: CancelStatus, val task: T? = null)

    enum class TaskState { QUEUED, PROCESSING, DELIVERING }
    enum class CancelStatus { CANCELLED, UNSUBSCRIBED, NOT_FOUND, NOT_SUBSCRIBED }
    enum class PutStatus { QUEUE_FULL, USER_QUEUE_FULL, DUPLICATE_TASK, JOINED_TASK, SUCCESS, FAILURE }
}
