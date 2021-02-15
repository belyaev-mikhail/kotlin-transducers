package ru.spbstu

import kotlinx.coroutines.flow.*

typealias Reducer<Acc, E> = (Acc?, E) -> Acc?

inline fun <A, B> Reducer(crossinline reducer: Reducer<A, B>): Reducer<A, B> = { a: A?, b: B ->
    //check(a === null || b !== null)
    reducer(a, b)
}
typealias Transducer<Acc, E1, E2> = (Reducer<Acc, E2>) -> Reducer<Acc, E1>

inline fun <A, B> idT(): Transducer<A, B, B> = { r -> r }

inline fun <A, B> filterT(crossinline pred: (B) -> Boolean): Transducer<A, B, B> = { reducer ->
    Reducer { a: A?, b: B -> if (pred(b)) reducer(a, b) else a }
}

inline fun <T> toSequenceR(): Reducer<Sequence<T>, T> = Reducer { a, b ->
    when {
        a === null -> sequenceOf(b)
        else -> a + b
    }
}

inline fun <T, C : MutableCollection<T>> toR(collection: C): Reducer<C, T> = Reducer { a, b ->
    when {
        a === null -> collection.apply { add(b) }
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

data class TransducerBuilderResult<A, B>(
    @PublishedApi
    internal val default: A,
    @PublishedApi
    internal val result: Reducer<A, B>
)

inline class TransducerBuilder<Acc, E1, E2> constructor(inline val t: Transducer<Acc, E1, E2>) {
    companion object {
        inline fun <A, B, C, D> combine(
            crossinline lhv: Transducer<A, B, C>,
            crossinline rhv: Transducer<A, C, D>
        ): Transducer<A, B, D> = { r ->
            lhv(rhv(r))
        }
    }

    inline fun <E3> combineWith(crossinline rhv: Transducer<Acc, E2, E3>): TransducerBuilder<Acc, E1, E3> =
        TransducerBuilder(combine(t, rhv))

    inline fun <E3> combineWith(crossinline rhv: (Reducer<Acc, E3>, a: Acc?, c: E2) -> Acc?): TransducerBuilder<Acc, E1, E3> =
        combineWith { reducer -> { a: Acc?, c: E2 -> rhv(reducer, a, c) } }

    inline fun <E3> map(crossinline body: (E2) -> E3): TransducerBuilder<Acc, E1, E3> =
        combineWith { reducer, a, b -> reducer(a, body(b)) }

    inline fun filter(crossinline body: (E2) -> Boolean): TransducerBuilder<Acc, E1, E2> =
        combineWith { reducer, a, b -> if (body(b)) reducer(a, b) else a }

    inline fun take(n: Int): TransducerBuilder<Acc, E1, E2> =
        combineWith { reducer ->
            var i = 0
            { a: Acc?, b: E2 ->
                ++i
                if (i <= n) reducer(a, b)
                else null
            }
        }

    inline fun <E3> flatMap(crossinline body: (E2) -> Iterable<E3>): TransducerBuilder<Acc, E1, E3> =
        combineWith { reducer, a, b ->
            var acc: Acc? = a
            for (e in body(b)) acc = reducer(acc, e) ?: break
            acc
        }

    inline fun terminateWith(default: Acc, noinline reducer: Reducer<Acc, E2>): TransducerBuilderResult<Acc, E1> =
        TransducerBuilderResult(default, t(reducer))
}

inline fun <E, A, B> TransducerBuilder<A, B, List<E>>.flatten(): TransducerBuilder<A, B, E> =
    combineWith { reducer, a, b ->
        when {
            else -> {
                var acc: A? = a
                for (e in b) acc = reducer(acc, e) ?: break
                acc
            }
        }
    }

// reducer examples
inline fun <B, T> TransducerBuilder<Sequence<T>, B, T>.toSequence(): TransducerBuilderResult<Sequence<T>, B> =
    terminateWith(sequenceOf()) { a, b ->
        when {
            a === null -> sequenceOf(b)
            else -> a + b
        }
    }

// reducer examples
inline fun <B, T, C : MutableCollection<T>> TransducerBuilder<C, B, T>.into(collection: C): TransducerBuilderResult<C, B> =
    TransducerBuilderResult(collection, t(toR(collection)))

inline fun <B> TransducerBuilder<Int, B, Int>.sum(): TransducerBuilderResult<Int, B> =
    terminateWith(0) { a: Int?, b: Int -> (a ?: 0) + b }

inline fun <B, E> TransducerBuilder<List<E>, B, E>.toList(m: MutableList<E> = mutableListOf()): TransducerBuilderResult<List<E>, B> =
    terminateWith(m) { _, b -> m.apply { add(b) } }


inline fun <A, B, C> transducer(body: TransducerBuilder<A, B, B>.() -> TransducerBuilderResult<A, C>): TransducerBuilderResult<A, C> {
    return TransducerBuilder<A, B, B>(idT()).body()
}

inline fun <T, reified R> Iterator<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val res = transducer(body)
    val reducer = res.result
    val default = res.default
    var accumulator: R? = null
    for (element in this) {
        accumulator = reducer(accumulator, element) ?: break
    }
    return accumulator ?: default
}

inline fun <T, reified R> Sequence<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R =
    iterator().transduce(body)

inline fun <T, reified R> Iterable<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R =
    iterator().transduce(body)

@PublishedApi
internal class StopException(val value: Any?) : Throwable()

suspend inline fun <T, reified R> Flow<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val res = transducer(body)
    val reducer = res.result
    val default = res.default

    return try {
        this.fold<T, R?>(null) { a, e ->
            reducer(a, e) ?: throw StopException(a)
        }
    } catch (ex: StopException) {
        ex.value as R
    } ?: default
}
