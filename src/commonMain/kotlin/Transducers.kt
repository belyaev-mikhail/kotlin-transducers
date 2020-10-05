package ru.spbstu

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import ru.spbstu.wheels.*

typealias Reducer<A, B> = (Option<A>, Option<B>) -> Option<A>

inline fun <A, B> Reducer(crossinline reducer: Reducer<A, B>): Reducer<A, B> = { a: Option<A>, b: Option<B> ->
    //check(a === null || b !== null)
    reducer(a, b)
}
typealias Transducer<A, B, C> = (Reducer<A, C>) -> Reducer<A, B>

inline fun <A, B> idT(): Transducer<A, B, B> = { r -> r }

inline fun <A, B> filterT(crossinline pred: (B) -> Boolean): Transducer<A, B, B> = { reducer ->
    Reducer { a: Option<A>, b: Option<B> ->
        if (b.map(pred).getOrElse { true }) reducer(a, b)
        else a
    }
}

inline fun <T> toSequenceR(): Reducer<Sequence<T>, T> = Reducer { a, b ->
    when {
        a.isEmpty() -> Option.just(emptySequence())
        b.isEmpty() -> a
        else -> a.zip(b, Sequence<T>::plusElement)
    }
}

inline fun <T, C : MutableCollection<T>> toR(collection: C): Reducer<C, T> = Reducer { a, b ->
    when {
        a.isEmpty() -> Option.just(collection)
        b.isEmpty() -> a
        else -> a.also { a.zip(b) { _, e -> collection.add(e) } }
    }
}

inline fun <T> sumByR(crossinline body: (T) -> Int): Reducer<Int, T> = Reducer { a, b ->
    when {
        a.isEmpty() -> Option.just(0)
        b.isEmpty() -> a
        else -> a.zip(b) { ax, bx -> ax + body(bx) }
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

    inline fun <D> combineWith(crossinline rhv: (Reducer<A, D>, a: Option<A>, c: Option<C>) -> Option<A>): TransducerBuilder<A, B, D> =
        combineWith { reducer -> { a: Option<A>, c: Option<C> -> rhv(reducer, a, c) } }

    inline fun <D> map(crossinline body: (C) -> D): TransducerBuilder<A, B, D> =
        combineWith { reducer, a, b ->
            when {
                a.isEmpty() || b.isEmpty() -> reducer(a, Option.empty())
                else -> reducer(a, b.map(body))
            }
        }

    inline fun filter(crossinline body: (C) -> Boolean): TransducerBuilder<A, B, C> =
        combineWith { reducer, a, b ->
            if (b.map(body).getOrElse { true }) reducer(a, b) else a
        }

    inline fun take(n: Int): TransducerBuilder<A, B, C> =
        combineWith { reducer ->
            var i = 0
            { a: Option<A>, b: Option<C> ->
                ++i
                if (i <= n) reducer(a, b)
                else Option.empty()
            }
        }

    inline fun <D> flatMap(crossinline body: (C) -> Iterable<D>): TransducerBuilder<A, B, D> =
        combineWith { reducer, a, b ->
            when {
                a.isEmpty() -> reducer(a, Option.empty())
                else -> {
                    var acc: Option<A> = Option.empty()
                    val bvalues = b.map(body)
                    for (iterable in bvalues)
                        for (e in iterable)
                            acc = reducer(a, Option.just(e))
                    acc
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
            a.isEmpty() -> Option.just(emptySequence())
            b.isEmpty() -> a
            else -> a.zip(b, Sequence<T>::plusElement)
        }
    }

// reducer examples
inline fun <B, T, C : MutableCollection<T>> TransducerBuilder<C, B, T>.into(collection: C): TransducerBuilderResult<C, B> =
    TransducerBuilderResult(t(toR(collection)))

inline fun <B> TransducerBuilder<Int, B, Int>.sum(): TransducerBuilderResult<Int, B> =
    terminateWith { a, b ->
        Option.just(a.getOrElse { 0 } + b.getOrElse { 0 })
    }

inline fun <A, B, C> transducer(body: TransducerBuilder<A, B, B>.() -> TransducerBuilderResult<A, C>): Reducer<A, C> {
    return TransducerBuilder<A, B, B>(idT()).body().result
}

inline fun <T, reified R> Sequence<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val reducer = transducer(body)
    var accumulator: Option<R> = Option.empty()
    for (element in this) {
        val interm = reducer(accumulator, Option.just(element))
        if (interm.isEmpty()) break
        accumulator = interm
    }
    return accumulator.getOrElse { reducer(Option.empty(), Option.empty()).get() }
}

inline fun <T, reified R> Iterable<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val reducer = transducer(body)
    var accumulator: Option<R> = Option.empty()
    for (element in this) {
        val interm = reducer(accumulator, Option.just(element))
        if (interm.isEmpty()) break
        accumulator = interm
    }
    return accumulator.getOrElse { reducer(Option.empty(), Option.empty()).get() }
}

@PublishedApi
internal class StopException(val value: Any?) : Throwable()

suspend inline fun <T, reified R> Flow<T>.transduce(body: TransducerBuilder<R, T, T>.() -> TransducerBuilderResult<R, T>): R {
    val reducer: Reducer<R, T> = transducer(body)

    return try {
        this.fold<T, Option<R>>(Option.empty()) { a, e ->
            reducer(a, Option.just(e)).orElse { throw StopException(a) }
        }.getOrElse { reducer(Option.empty(), Option.empty()).get() }
    } catch (ex: StopException) {
        ex.value as R
    }
}


