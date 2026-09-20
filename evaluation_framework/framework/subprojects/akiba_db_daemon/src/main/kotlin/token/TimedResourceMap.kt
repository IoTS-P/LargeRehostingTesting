package org.iotsplab.akiba.dbDaemon.token

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

open class ResourceData<R> (
    val owner: R,
    val timer: AtomicReference<ScheduledFuture<*>?> = AtomicReference(null),
    @Volatile
    var expireAt: Instant
)

/**
 * A map that stores resources with a timeout.
 *
 * Generics:
 * - `K`: The key of the resource.
 * - `V`: The data of the resource.
 * - `R`: The owner of the resource.
 *
 * The data is stored in `resourceOwned`, which is a map of `K` to `Pair<ResourceData<R>, V?>`.
 */
open class TimedResourceMap<K, V, R: Comparable<R>>(
    private val timeout: Duration
) {

    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(1) { r ->
            Thread(r, "resource-timeout").apply { isDaemon = true }
        }
    protected val resourcesOwned: ConcurrentHashMap<K, Pair<ResourceData<R>, V?>> = ConcurrentHashMap()
    private val operationLock: ReentrantLock = ReentrantLock()

    fun hasLockedResource(): Boolean = resourcesOwned.isNotEmpty()

    fun hasLockedResource(owner: R): Boolean = resourcesOwned.values.any { it.first.owner == owner }

    fun isLocked(resourceKey: K, owner: R): Boolean = resourcesOwned[resourceKey]?.first?.owner == owner

    fun isLocked(resourceKey: K): Boolean = resourcesOwned[resourceKey] != null

    @Throws(NotOwnedException::class, NotLockedException::class)
    fun getResource(resourceKey: K, owner: R): V? {
        val res = resourcesOwned[resourceKey]
        if (res != null) {
            if (res.first.owner == owner)
                return res.second
            else
                throw NotOwnedException()
        } else
            throw NotLockedException()
    }

    fun expireAt(resourceKey: K): Instant? = resourcesOwned[resourceKey]?.first?.expireAt

    private fun calculateExpiry(): Instant = Instant.now().plus(timeout)

    @Throws(AlreadyLockedException::class, NotOwnedException::class)
    open fun lock(resourceKey: K, owner: R, otherData: V? = null) {
        operationLock.withLock {
            if (isLocked(resourceKey, owner)) throw AlreadyLockedException()
            else if (isLocked(resourceKey)) throw NotOwnedException()
            else {
                resourcesOwned[resourceKey] = ResourceData(
                    owner = owner,
                    timer = AtomicReference(
                        scheduler.schedule(
                            { onTimeout(resourceKey, owner) },
                            timeout.toMillis(),
                            TimeUnit.MILLISECONDS
                        )
                    ),
                    expireAt = calculateExpiry()
                ) to otherData
            }
        }
    }

    @Throws(NotLockedException::class, NotOwnedException::class)
    open fun unlock(resourceKey: K, owner: R) {
        val ownerInfo = resourcesOwned[resourceKey] ?: throw NotLockedException()

        if (ownerInfo.first.owner != owner) throw NotOwnedException()
        else {
            val removed = resourcesOwned.remove(resourceKey)
            removed?.first?.timer?.get()?.cancel(false)
            removed?.let { releaseHook(resourceKey, resource = it, owner) }
        }
    }

    open fun renew(resourceKey: K, owner: R) {
        val ownerInfo = resourcesOwned[resourceKey] ?: throw NotLockedException()
        if (ownerInfo.first.owner != owner)   throw NotOwnedException()
        else {
            ownerInfo.first.expireAt = calculateExpiry()
            ownerInfo.first.timer.get() ?.cancel(false)
            ownerInfo.first.timer.set(
                scheduler.schedule(
                    { onTimeout(resourceKey, owner) },
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            )
        }
    }

    open fun onTimeout(resourceKey: K, owner: R) {
        // Avoid race condition of `renew` and `onTimeout`
        val entry = resourcesOwned[resourceKey] ?: return
        if (entry.first.owner != owner) return
        if (Instant.now().isBefore(entry.first.expireAt)) return

        val removed = resourcesOwned.remove(resourceKey)
        removed?.let { releaseHook(resourceKey, resource = it, owner) }
    }

    open fun releaseHook(key: K, resource: Pair<ResourceData<R>, V?>, owner: R) {}

    open fun clear() {
        resourcesOwned.values.forEach { (resourceData, userData) ->
            resourceData.timer.get()?.cancel(false)
        }
        resourcesOwned.clear()
    }

    fun shutdown() {
        scheduler.shutdownNow()
        clear()
    }

    class AlreadyLockedException: Exception()
    class NotLockedException: Exception()
    class NotOwnedException: Exception()
}