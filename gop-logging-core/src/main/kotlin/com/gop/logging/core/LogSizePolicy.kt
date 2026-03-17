package com.gop.logging.core

class LogSizePolicy(
    private val defaultMaxBytes: Int = 8 * 1024,
    private val errorMaxBytes: Int = 12 * 1024
) {
    fun maxBytes(hasError: Boolean): Int {
        return if (hasError) errorMaxBytes else defaultMaxBytes
    }
}
