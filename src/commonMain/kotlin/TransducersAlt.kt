package ru.spbstu.alt


typealias ReducerAlt<Acc, E> = (Acc?, E?) -> Acc?
typealias TransducerAlt<Acc, E1, E2> = (ReducerAlt<Acc, E2>) -> ReducerAlt<Acc, E1>

inline fun <Acc, E> Reducer(
    default: Acc,
    crossinline single: (E) -> Acc?,
    crossinline body: (Acc, E) -> Acc?
): ReducerAlt<Acc, E> = { acc, e ->
    when {
        e === null -> default
        acc === null -> single(e)
        else -> body(acc, e)
    }
}

object Transducers {
    inline fun <A, B, C, D> combine(
        crossinline lhv: TransducerAlt<A, B, C>,
        crossinline rhv: TransducerAlt<A, C, D>
    ): TransducerAlt<A, B, D> = { r -> lhv(rhv(r)) }
}

inline fun <Acc, E1, E2, E3> TransducerAlt<Acc, E1, E2>.map(crossinline body: (E2) -> E3): TransducerAlt<Acc, E1, E3> =
    Transducers.combine(this, { reducer -> { a, b -> reducer(a, b?.let(body)) } })

inline fun <Acc, E1, E2> TransducerAlt<Acc, E1, E2>.filter(crossinline body: (E2) -> Boolean): TransducerAlt<Acc, E1, E2> =
    Transducers.combine(this, { reducer -> { a, b -> if (b !== null && body(b)) reducer(a, b) else a } })

inline fun <Acc, E1, E2> TransducerAlt<Acc, E1, E2>.take(n: Int): TransducerAlt<Acc, E1, E2> =
    Transducers.combine(this, { reducer ->
        var i = 0
        { a: Acc?, b: E2? ->
            ++i
            if (i <= n) reducer(a, b)
            else null
        }
    })

inline fun <Acc, E1, E2, E3> TransducerAlt<Acc, E1, E2>.flatMap(crossinline body: (E2) -> Iterable<E3>): TransducerAlt<Acc, E1, E3> =
    Transducers.combine(this, { reducer ->
        reducer@{ a, b ->
            var acc: Acc? = a
            if (b === null) return@reducer acc
            for (e in body(b)) acc = reducer(acc, e) ?: break
            acc
        }
    })

inline fun <E, A, B> TransducerAlt<A, B, List<E>>.flatten(): TransducerAlt<A, B, E> =
    Transducers.combine(this) { reducer -> reducer@ { a, b ->
        when {
            else -> {
                var acc: A? = a
                if (b === null) return@reducer acc
                for (e in b) acc = reducer(acc, e) ?: break
                acc
            }
        }
    }}

inline fun <B, E> TransducerAlt<List<E>, B, E>.toList(): ReducerAlt<List<E>, B> {
    val result = mutableListOf<E>()
    return this { _, e ->
        if (e !== null) result.add(e)
        result
    }
}


inline fun <T, reified R> Iterator<T>.transduceAlt(body: TransducerAlt<R, T, T>.() -> ReducerAlt<R, T>): R {
    val base: TransducerAlt<R, T, T> = { x -> x }
    val reducer = base.body()
    var accumulator: R? = null
    for (element in this) {
        accumulator = reducer(accumulator, element) ?: break
    }
    return accumulator ?: reducer(null, null) as R
}

inline fun <T, reified R> Sequence<T>.transduceAlt(body: TransducerAlt<R, T, T>.() -> ReducerAlt<R, T>): R =
    iterator().transduceAlt(body)

inline fun <T, reified R> Iterable<T>.transduceAlt(body: TransducerAlt<R, T, T>.() -> ReducerAlt<R, T>): R =
    iterator().transduceAlt(body)
