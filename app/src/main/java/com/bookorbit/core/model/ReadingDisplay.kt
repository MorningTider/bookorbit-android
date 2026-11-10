package com.bookorbit.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/** One precision for the reader, detail actions and shelf, including sub-percent positions. */
fun readingPercentage(value: Double): String =
    BigDecimal.valueOf(if (value.isFinite()) value.coerceIn(0.0, 100.0) else 0.0)
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()

fun remainingReadingSeconds(percentage: Double, progressDelta: Double, seconds: Long): Long? =
    if (percentage >= 100) 0L
    else if (progressDelta >= 0.1 && seconds >= 30)
        ((100 - percentage.coerceAtLeast(0.0)) * seconds / progressDelta).toLong()
    else null
