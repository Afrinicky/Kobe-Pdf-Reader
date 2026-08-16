package com.kobe.reader.core.common

import com.kobe.reader.core.error.KobeError

/**
 * Result type used everywhere below the UI layer.
 *
 * Kotlin's own [Result] is deliberately avoided: it carries a [Throwable], and
 * the whole point of [KobeError] is that exceptions stop at the boundary.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: KobeError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Success) action(value)
}

inline fun <T> Outcome<T>.onFailure(action: (KobeError) -> Unit): Outcome<T> = apply {
    if (this is Outcome.Failure) action(error)
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> Outcome<T>.errorOrNull(): KobeError? = (this as? Outcome.Failure)?.error

/**
 * Runs [block], converting any throw into [Outcome.Failure].
 *
 * [kotlinx.coroutines.CancellationException] is rethrown so that structured
 * concurrency keeps working - swallowing it is the classic way to leak a
 * coroutine that refuses to die.
 */
inline fun <T> runCatchingKobe(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Outcome.Failure(KobeError.from(throwable))
}
