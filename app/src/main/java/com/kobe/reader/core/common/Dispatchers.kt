package com.kobe.reader.core.common

import javax.inject.Qualifier

/**
 * Dispatchers are injected rather than referenced statically so unit tests can
 * substitute a [kotlinx.coroutines.test.TestDispatcher].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Dispatcher(val kobe: KobeDispatcher)

enum class KobeDispatcher {
    /** Disk and content-resolver traffic. */
    IO,

    /** PDF parsing, rendering and image encoding - CPU bound. */
    Default,
}
