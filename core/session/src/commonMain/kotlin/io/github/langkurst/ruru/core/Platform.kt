package io.github.langkurst.ruru.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wall-clock milliseconds, in common code (replaces `System.currentTimeMillis()`). */
@OptIn(ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** A map that is safe to use from several coroutines at once (replaces `ConcurrentHashMap`). */
class ConcurrentMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()
    operator fun get(key: K): V? = synchronized(lock) { map[key] }
    operator fun set(key: K, value: V) { synchronized(lock) { map[key] = value } }
    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    fun clear() { synchronized(lock) { map.clear() } }
    fun snapshot(): Map<K, V> = synchronized(lock) { HashMap(map) }
}

/** A set that is safe to use from several coroutines at once (replaces `ConcurrentHashMap.newKeySet()`). */
class ConcurrentSet<T> {
    private val lock = SynchronizedObject()
    private val set = HashSet<T>()
    fun add(element: T): Boolean = synchronized(lock) { set.add(element) }
    operator fun plusAssign(element: T) { add(element) }
    fun addAll(elements: Collection<T>): Boolean = synchronized(lock) { set.addAll(elements) }
    operator fun plusAssign(elements: Collection<T>) { addAll(elements) }
    operator fun contains(element: T): Boolean = synchronized(lock) { element in set }
    fun clear() { synchronized(lock) { set.clear() } }
}

/** A first-in first-out queue that is safe to use from several coroutines (replaces `ConcurrentLinkedQueue`). */
class ConcurrentQueue<T> {
    private val lock = SynchronizedObject()
    private val items = ArrayDeque<T>()
    fun add(element: T) { synchronized(lock) { items.addLast(element) } }
    operator fun plusAssign(element: T) { add(element) }
    fun poll(): T? = synchronized(lock) { items.removeFirstOrNull() }
    fun isNotEmpty(): Boolean = synchronized(lock) { items.isNotEmpty() }
    fun clear() { synchronized(lock) { items.clear() } }
}
