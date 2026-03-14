package com.gop.logging.core

object StepContext {
    private val current = ThreadLocal<String>()

    fun set(step: String) {
        current.set(step)
    }

    fun get(): String? = current.get()

    fun clear() {
        current.remove()
    }

    inline fun <T> scoped(step: String, block: () -> T): T {
        val previous = get()
        set(step)
        return try {
            block()
        } finally {
            if (previous == null) {
                clear()
            } else {
                set(previous)
            }
        }
    }
}
