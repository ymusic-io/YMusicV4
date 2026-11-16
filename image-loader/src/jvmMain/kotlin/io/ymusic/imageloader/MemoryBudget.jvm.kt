package io.ymusic.imageloader

import java.lang.management.ManagementFactory
import javax.management.JMException
import javax.management.ObjectName

/**
 * Desktop memory LRU budget: ~1/128 of physical RAM, 16–30MB. Decoded bitmaps are Skia pixels in
 * native memory, not the JVM heap, so `-Xmx` is not the bound; one Now Playing square alone is ~2MB.
 */
fun defaultMemoryCacheBudget(): Long {
    val physical = physicalMemoryBytes() ?: return FALLBACK_BUDGET
    return (physical / 128).coerceIn(MIN_BUDGET, MAX_BUDGET)
}

/**
 * Through the MBean server, not `com.sun.management`: the packaged runtime is jlinked without
 * `jdk.management`, where that class is absent and the attribute below is not registered.
 */
private fun physicalMemoryBytes(): Long? = try {
    ManagementFactory.getPlatformMBeanServer()
        .getAttribute(ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME), "TotalMemorySize") as? Long
} catch (e: JMException) {
    null
}

private const val MIN_BUDGET = 16L * 1024 * 1024
private const val MAX_BUDGET = 30L * 1024 * 1024
private const val FALLBACK_BUDGET = MAX_BUDGET
