package com.gop.logging.core

import com.gop.logging.contract.LogPrefix
import java.util.Locale

class StepResolver {
    fun resolve(clazz: Class<*>, suffix: String): String {
        val prefix = clazz.getAnnotation(LogPrefix::class.java)?.value
            ?: clazz.simpleName.lowercase(Locale.ROOT)
        return "$prefix.$suffix"
    }
}
