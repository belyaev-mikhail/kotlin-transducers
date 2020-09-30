package ru.spbstu

import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

inline fun <T, U> Sequence<T>.altMap(crossinline body: (T) -> U) = sequence {
    for (e in this@altMap) yield(body(e))
}

inline fun <T> Sequence<T>.altFilter(crossinline body: (T) -> Boolean) = sequence {
    for (e in this@altFilter) if(body(e)) yield(e)
}

inline fun <T> Sequence<T>.altTake(n: Int) = sequence {
    var i = 0
    for (e in this@altTake) {
        ++i
        if(i > n) break
        yield(e)
    }
}

class TransducersTest {
    @OptIn(ExperimentalTime::class)
    @Test
    fun tst() {

        repeat(200) {

//            measureTime {
//                val ss: MutableSet<Int> = (1..20000000)
//                    .map { it + 2 }
//                    .filter { it % 3 == 1 }
//                    .map { it * 4 }
//                    .take(800)
//                    .toMutableSet()
//            }.also { println("Direct: $it") }

            measureTime {
                val ss: Int = (1..20000000).asSequence()
                    .altMap { it + 2 }
                    .altFilter { it % 3 == 1 }
                    .altMap { it * 4 }
                    .altTake(80000)
                    .sum()
            }.also { println("asSequenceAlt(): $it")  }

            measureTime {
                val ss: Int = (1..20000000).asSequence()
                    .map { it + 2 }
                    .filter { it % 3 == 1 }
                    .map { it * 4 }
                    .take(80000)
                    .sum()
            }.also { println("asSequence(): $it") }

            measureTime {
                val ss: Int = (1..20000000).transduce {
                    map { it + 2 }
                        .filter { it % 3 == 1 }
                        .map { it * 4 }
                        .take(80000)
                        .sum()
                }
            }.also { println("transduce(): $it") }
        }



    }
}

