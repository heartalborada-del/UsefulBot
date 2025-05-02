package me.heartalborada.commons.queue

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

class ProcessingQueue<K, T, E>(
    private val globalCapacity: Int = 2, // 全局最大队列容量
    private val userCapacity: Int = 5 // 每个用户的最大队列容量
) {
    private val channel = Channel<Triple<K, T, E>>(100) // 队列用于存储任务 (用户ID, 任务)
    private val userSemaphores = mutableMapOf<K, Semaphore>() // 每个用户的并发限制
    private val mutex = Mutex() // 用于保护共享数据的并发操作
    private val processingTasks = mutableSetOf<T>() // 当前正在处理的任务
    private var currentQueueSize = 0 // 手动维护的队列大小

    // 获取或创建用户的 Semaphore
    private suspend fun getSemaphoreForUser(userId: K, userCapacity: Int): Semaphore {
        return mutex.withLock {
            userSemaphores.getOrPut(userId) { Semaphore(userCapacity) }
        }
    }

    suspend fun put(userId: K, task: T, extra: E): PutStatus {
        val userSemaphore = getSemaphoreForUser(userId, userCapacity)

        // 检查用户队列是否已满
        if (!userSemaphore.tryAcquire()) {
            return PutStatus.USER_QUEUE_FULL
        }

        // 检查是否任务已在处理中
        var status = PutStatus.SUCCESS
        mutex.withLock {
            if (currentQueueSize >= globalCapacity) {
                status = PutStatus.QUEUE_FULL
            }
            if (processingTasks.contains(task)) {
                status = PutStatus.DUPLICATE_TASK
            }
            if (status != PutStatus.DUPLICATE_TASK) {
                processingTasks.add(task)
            }
            currentQueueSize++ // 增加队列大小计数器
        }

        // 将任务放入队列
        val res = channel.trySend(Triple(userId, task, extra))

        return if (res.isSuccess) status else PutStatus.FAILURE
    }

    suspend fun take(): Triple<K, T, E> {
        val (userId, task, extra) = channel.receive() // 从队列中取出任务
        val userSemaphore = userSemaphores[userId]

        // 释放用户的占用权限
        userSemaphore?.release()

        // 从处理中集合中移除任务并更新队列大小计数器
        mutex.withLock {
            processingTasks.remove(task)
            currentQueueSize-- // 减少队列大小计数器
        }

        return Triple(userId, task, extra)
    }

    /**
     * get queue size
     */
    fun getCurrentQueueSize(): Int {
        return currentQueueSize
    }

    enum class PutStatus {
        QUEUE_FULL,            // 全局队列已满
        USER_QUEUE_FULL,       // 用户队列已满
        DUPLICATE_TASK,        // 相同任务正在处理中
        SUCCESS,               // 成功添加任务
        FAILURE
    }
}