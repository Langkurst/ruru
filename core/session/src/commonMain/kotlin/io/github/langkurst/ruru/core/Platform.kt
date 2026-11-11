package io.github.langkurst.ruru.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Wall-clock milliseconds, in common code (replaces `System.currentTimeMillis()`). */
@OptIn(ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** A map that is safe to use from several coroutines at once (replaces `ConcurrentHashMap`). */
class ConcurrentMap<K, V> : SynchronizedObject() {
    private val map = HashMap<K, V>()
    operator fun get(key: K): V? = synchronized(this) { map[key] }
    operator fun set(key: K, value: V) { synchronized(this) { map[key] = value } }
    fun remove(key: K): V? = synchronized(this) { map.remove(key) }
    fun clear() { synchronized(this) { map.clear() } }
    fun snapshot(): Map<K, V> = synchronized(this) { HashMap(map) }
}

/** A set that is safe to use from several coroutines at once (replaces `ConcurrentHashMap.newKeySet()`). */
class ConcurrentSet<T> : SynchronizedObject() {
    private val set = HashSet<T>()
    fun add(element: T): Boolean = synchronized(this) { set.add(element) }
    operator fun plusAssign(element: T) { add(element) }
    fun addAll(elements: Collection<T>): Boolean = synchronized(this) { set.addAll(elements) }
    operator fun plusAssign(elements: Collection<T>) { addAll(elements) }
    operator fun contains(element: T): Boolean = synchronized(this) { element in set }
    fun clear() { synchronized(this) { set.clear() } }
}

/** A first-in first-out queue that is safe to use from several coroutines (replaces `ConcurrentLinkedQueue`). */
class ConcurrentQueue<T> : SynchronizedObject() {
    private val items = ArrayDeque<T>()
    fun add(element: T) { synchronized(this) { items.addLast(element) } }
    operator fun plusAssign(element: T) { add(element) }
    fun poll(): T? = synchronized(this) { items.removeFirstOrNull() }
    fun isNotEmpty(): Boolean = synchronized(this) { items.isNotEmpty() }
    fun clear() { synchronized(this) { items.clear() } }
}
