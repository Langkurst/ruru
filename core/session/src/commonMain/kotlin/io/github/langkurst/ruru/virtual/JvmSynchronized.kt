package io.github.langkurst.ruru.virtual

/**
 * `@Synchronized` on the JVM (desktop, Android), nothing elsewhere. The simulated headset is only
 * driven from one coroutine at a time outside the JVM tests, so iOS needs no lock here.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION)
expect annotation class JvmSynchronized()
