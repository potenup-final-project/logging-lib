package com.gop.logging.contract

enum class ProcessResult {
    SUCCESS,
    FAIL,
    SKIP,
    RETRY,
    DLQ
}
