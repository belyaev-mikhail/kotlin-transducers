package ru.spbstu

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold

typealias Reducer<A, B> = (A?, B?) -> A?

inline fun <A, B> Reducer(crossinline reducer: Reducer<A, B>): Reducer<A, B> = { a: A?, b: B? ->
    //check(a === null || b !== null)
    reducer(a, b)
}
typealias Transducer<A, B, C> = (Reducer<A, C>) -> Reducer<A, B>

inline fun <A, B> idT(): Transducer<A, B, B> = { r -> r }

inline fun <A, B> filterT(crossinline pred: (B) -> Boolean): Transducer<A, B, B> = { reducer ->
    Reducer { a: A?, b: B? ->
        when {
            a === null || b === null -> reducer(a, b)
            else -> if (pred(b)) reducer(a, b) else a
        }
    }
}

inline fun <T> toSequenceR(): Reducer<Sequence<T>, T> = Reducer { a, b ->
    when {
        a === null -> emptySequence()
        b === null -> a
        else -> a + b
    }
}

inline fun <T, C : MutableCollection<T>> toR(collection: C): Reducer<C, T> = Reducer { a, b ->
    when {
        a === null -> collection
        b === null -> a
        else -> a.also { it.add(b) }
    }
}

inline fun <T> sumByR(crossinline body: (T) -> Int): Reducer<Int, T> = Reducer { a, b ->
    when {
        a === null -> 0
        b === null -> a
        else -> a + body(b)
    }
}

inline class TransducerBuilderResult<A, B>(private val r: Reducer<A, B>) {
    @PublishedApi
    internal val result
        get() = r
}

inline class TransducerBuilder<A, B, C> constructor(inline val t: Transducer<A, B, C>) {
    companion object {
        inline fun <A, B, C, D> combine(
            crossinline lhv: Transducer<A, B, C>,
            crossinline rhv: Transducer<A, C, D>
        ): Transducer<A, B, D> = { r ->
            lhv(rhv(r))
        }
    }

    inline fun <D> combineWith(crossinline rhv: Transducer<A, C, D>): TransducerBuilder<A, B, D> =
        TransducerBuilder(combine(t, rhv))

    inline fun <D> combineWith(crossinline rhv: (Reducer<A, D>, a: A?, c: C?) -> A?): TransducerBuilder<A, B, D> =
        combineWith { reducer -> { a: A?, c: C? -> rhv(reducer, a, c) } }

    inline fun <D> map(crossinline body: (C) -> D): TransducerBuilder<A, B, D> =
        combineWith { reducer, a, b ->
            when {
                a === null || b === null -> reducer(a, null)
                else -> reducer(a, body(b))
            }
        }

    inline fun filter(crossinline body: (C) -> Boolean): TransducerBuilder<A, B, C> =
        combineWith { reducer, a, b ->
            when {
                a === null || b === null -> reducer(a, b)
                else -> if (body(b)) reducer(a, b) else a
            }
        }

    inline fun take(n: Int): TransducerBuilder<A, B, C> =
        combineWith { reducer ->
            var i = 0
            { a: A?, b: C? ->
                when {
                    a === null || b === null -> reducer(a, null)
                    else -> {
                        ++i
                        if (i <= n) reducer(a, b)
                        else null
                    }
                }
            }
        }

    inline fun <D> flatMap(crossinline body: (C) -> Iterable<D>): TransducerBuilder<A, B, D> =
        combineWith { reducer, a, b ->
            when {
                a === null || b === null -> reducer(a, null)
                else -> {
                    var acc: A? = null
                    for (e in body(b)) acc = reducer(a, e)
                    acc!!
                }
            }
        }

    inline fun terminateWith(noinline reducer: Reducer<A, C>): TransducerBuilderResult<A, B> =
        TransducerBuilderResult(t(reducer))
}

// reducer examples
inline fun <B, T> TransducerBuilder<Sequence<T>, B, T>.toSequence(): TransducerBuilderResult<Sequence<T>, B> =
    terminateWith { a, b ->
        when {
            a === null -> emptySequence()
            b === null -> a
            else -> a + b
        }
    }

// reducer examples
inline fun <B, T, C : MutableCollection<T>> TransducerBuilder<C, B, T>.into(collection: C): TransducerBuilderResult<C, B> =
    TransducerBuilderResult(t(toR(collection)))

inline fun <B> TransducerBuilder<Int, B, Int>.sum(): TransducerBuilderResult<Int, B> =
    terminateWith { a: Int?, b: Int? -> (a ?: 0) + (b ?: 0) }

inline fun <A, B, C> transducer(body: TransducerBuilder<A, B, B>.() -> TransducerBuilderResult<A, C>): Reducer<A, C> {
    return TransducerBuilder<A, B, B>(idT()).body().result
}

inline fun <T, reified R> Sequence<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val reducer = transducer(body)
    var accumulator: R? = null
    for (element in this) accumulator = reducer(accumulator, element) ?: break
    return accumulator ?: reducer(null, null) as R
}

inline fun <T, reified R> Iterable<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val reducer = transducer(body)
    var accumulator: R? = null
    for (element in this) accumulator = reducer(accumulator, element) ?: break
    return accumulator ?: reducer(null, null) as R
}

@PublishedApi
internal class StopException(val value: Any?) : Throwable()

suspend inline fun <T, reified R> Flow<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val reducer: Reducer<R, T> = transducer(body)

    return try {
        this.fold<T, R?>(null) { a, e ->
            reducer(a, e) ?: throw StopException(a)
        } ?: reducer(null, null) as R
    } catch (ex: StopException) {
        ex.value as R
    }
}
