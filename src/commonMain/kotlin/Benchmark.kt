package ru.spbstu

import kotlinx.benchmark.*

//TODO: sequence

fun Int.showDoubledString() = (this * this).toString()

inline fun <R> app(body: () -> R) = body()
inline fun <A, R> app(body: (A) -> R, a: A) = body(a)
inline fun <A, B, R> app(body: (A, B) -> R, a: A, b: B) = body(a, b)
inline fun <A, B, C, R> app(body: (A, B, C) -> R, a: A, b: B, c: C) = body(a, b, c)
inline fun <A, B, C, D, R> app(body: (A, B, C, D) -> R, a: A, b: B, c: C, d: D) = body(a, b, c, d)

@State(Scope.Benchmark)
@Warmup(20)
@Measurement(iterations = 20)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class TestBenchmark {

    lateinit var list: List<Int>
    lateinit var listList: List<List<Int>>
    lateinit var strList: List<String>
    lateinit var seq: Sequence<Int>
    lateinit var rangeList: List<Int>
    lateinit var conjLambda: Reducer<MutableList<Int>, Int>

    @Setup
    fun setup() {
        list = listOf(1, 2, 3)
        listList = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
        strList = listOf("123", "456", "78")
        seq = IntRange(0, 1000).asSequence()
        rangeList = IntRange(0, 1000).toList()
        conjLambda = { a: MutableList<Int>?, b: Int? -> a?.apply { b?.let(::add) } }
    }

    @Benchmark
    fun trivialTransducer(): List<String> {
        return list
            .transduce {
                map { it.showDoubledString() }
                    .filter { !it.startsWith("3") }
                    .take(2)
                    .toList()
            }
    }

    @Benchmark
    fun trivialSequence(): List<String> {

        return list
            .asSequence()
            .map { it.showDoubledString() }
            .filter { !it.startsWith("3") }
            .take(2)
            .toList()
    }

    @Benchmark
    fun trivialStandard(): List<String> {

        return list
            .map { it.showDoubledString() }
            .filter { !it.startsWith("3") }
            .take(2)
    }

    @Benchmark
    fun flatMapTransducerInlined(): List<Int> {
        val m = mutableListOf<Int>()
        var i = 0
        var accumulator: List<Int>? = null
        for (element in listList) {
            accumulator = when {
                else -> {
                    var acc: List<Int>? = accumulator
                    for (e in element) acc = run {
                        ++i
                        if (i <= 8) m.apply { add(e * 10) }
                        else null
                    }
                    acc
                }
            } ?: break
        }
        return accumulator ?: m
    }

    @Benchmark
    fun flatMapTransducer(): List<Int> {
        return listList.transduce {
            flatten()
                .map { it * 10 }
                .take(8)
                .toList()
        }
    }

    @Benchmark
    fun flatMapSequence(): List<Int> {

        return listList
            .asSequence()
            .flatten()
            .map { it * 10 }
            .take(8)
            .toList()
    }


    @Benchmark
    fun flatMapStandard(): List<Int> {

        return listList
            .flatten()
            .map { it * 10 }
            .take(8)
    }

    @Benchmark
    fun mapFlatSeq(): List<Int> {

        return strList
            .asSequence()
            .flatMap { it.toList() }
            .map { it.toInt() }
            .filter { it > 3 }
            .toList()
    }

    @Benchmark
    fun mapFlatting(): List<Int> {
        return strList.transduce {
            flatMap { it.toList() }
                .map { it.toInt() }
                .filter { it > 3 }
                .toList()
        }
    }

    @Benchmark
    fun mapFlatLambdaHandInlined(): List<Int> {
        val m = mutableListOf<Int>()

        val default = m
        var accumulator: List<Int>? = null
        for (element in strList) {
            accumulator = run {
                val a = accumulator
                val b = element
                var acc: List<Int>? = a
                for (e in b.toList()) {
                    val a = acc
                    val b = e
                    acc = run {
                        val b = b.toInt()
                        if (b > 3) {
                            m.apply { add(b) }
                        } else a
                    }
                }
                acc
            } ?: break
        }
        return accumulator ?: default
    }

    @Benchmark
    fun mapFlatHandInlined(): List<Int> {
        val exit = false
        val acc = mutableListOf<Int>()
        for (e in strList) { // -> transduce
            if (exit) break // -> mapFlatting
            for (ee in e.toList()) {
                if (exit) break
                if (ee.toInt() > 3)
                    acc.apply { this.add(ee.toInt()) }
            }
        }

        return acc
    }

    @Benchmark
    fun mapFlat(): List<Int> {

        return strList
            .flatMap { it.toList() }
            .map { it.toInt() }
            .filter { it > 3 }
    }

    /*@Benchmark
    fun empty() {
    }*/

    @Benchmark
    fun heavyTransduced(): List<Int> {
        return strList.transduce {
            flatMap { it.toList() }
                .map { it.toInt() }
                .flatMap { IntRange(0, it * 10) }
                .filter { it % 2 == 0 }
                .take(80)
                .toList()
        }
    }

    @Benchmark
    fun heavySequence(): List<Int> {
        return strList
            .asSequence()
            .flatMap { it.toList() }
            .map { it.toInt() }
            .flatMap { IntRange(0, it * 10) }
            .filter { it % 2 == 0 }
            .take(80)
            .toList()
    }

    @Benchmark
    fun heavyStd(): List<Int> {
        return strList
            .flatMap { it.toList() }
            .map { it.toInt() }
            .flatMap { IntRange(0, it * 10) }
            .filter { it % 2 == 0 }
            .take(80)
    }

    @Benchmark //avg 163.5 ops/ms
    fun simpleTrans(): List<Int> {
        return rangeList.transduce {
            map { it * 2 }
                .filter { it % 3 == 0 }
                .take(1000)
                .toList()
        }
    }

    @Benchmark // avg 134 ops/ms
    fun simpleSeq(): List<Int> {
        return seq
            .map { it * 2 }
            .filter { it % 3 == 0 }
            .take(1000)
            .toList()
    }
}