package com.gop.logging.contract

object LogStep {
    const val UNKNOWN = "unknown"
    const val LOGGING_SERIALIZE = "logging.serialize"
    const val LOGGING_CONTRACT_VIOLATION = "logging.contract.violation"
    const val LOGGING_CONTEXT_MISSING = "logging.context.missing"
    const val HTTP_REQUEST_START = "http.request.start"
    const val HTTP_REQUEST_END = "http.request.end"
}
