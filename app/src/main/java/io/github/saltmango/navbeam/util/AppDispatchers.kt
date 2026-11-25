package io.github.saltmango.navbeam.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dedicated coroutine dispatchers for the NorthStar projection pipeline.
 *
 * Each dispatcher is bounded to a single worker thread via
 * [Dispatchers.Default.limitedParallelism(1)], which:
 *   • Prevents cross-domain work from interleaving on the same thread.
 *   • Does NOT spawn permanent OS threads (unlike [newSingleThreadContext]).
 *   • Allows the runtime to schedule across shared Default pool threads
 *     while guaranteeing at-most-one-concurrent-task semantics per domain.
 *
 * Usage pattern:
 * ```kotlin
 * withContext(AppDispatchers.location) { … }
 * withContext(AppDispatchers.encoder)  { … }
 * ```
 */
object AppDispatchers {
    /**
     * GPS callback processing — single-threaded so fixes arrive in order
     * and the jitter filter sees a consistent view of [currentFix].
     */
    val location: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Off-screen Canvas map rendering — single-threaded so bitmap state is
     * never accessed from two coroutines simultaneously.
     */
    val rendering: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * H.264 hardware encoder interaction and RTP packetization.
     * Kept separate from rendering so a slow drain() call never blocks the
     * next frame draw.
     */
    val encoder: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * UDP socket sends/receives.  Uses [Dispatchers.IO] because the JVM
     * blocking socket calls park the thread during system calls; IO's
     * elastic pool handles this correctly without growing [Default]'s
     * bounded thread pool.
     */
    val networking: CoroutineDispatcher = Dispatchers.IO
}
