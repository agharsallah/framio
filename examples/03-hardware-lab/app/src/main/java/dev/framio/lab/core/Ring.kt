package dev.framio.lab.core

/** Fixed-size history of floats for live charts. Oldest values fall off the front. */
class Ring(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var start = 0

    var size = 0
        private set

    fun add(v: Float) {
        if (size < capacity) {
            data[(start + size) % capacity] = v
            size++
        } else {
            data[start] = v
            start = (start + 1) % capacity
        }
    }

    operator fun get(i: Int): Float = data[(start + i) % capacity]

    fun clear() {
        start = 0
        size = 0
    }

    fun toList(): List<Float> = List(size) { get(it) }
}
