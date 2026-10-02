package org.multipaz.idv.backend.audit

import org.multipaz.crypto.Crypto
import org.multipaz.util.toBase64Url
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant

/**
 * A storage key that sorts lexicographically in timestamp order, so [StorageTable.enumerate]'s
 * lexicographic-by-key ordering (see `multipaz/storage/StorageTable.kt`) doubles as chronological
 * ordering for the audit logs — a random key (the default when `insert(key = null, ...)` is used)
 * would not do that.
 */
internal fun sortableKey(timestamp: Instant): String {
    val millis = timestamp.toEpochMilliseconds().coerceAtLeast(0)
    // Keeps records written in the same millisecond in the order they were written.
    val sequence = keySequence.incrementAndGet()
    val randomSuffix = Crypto.secureRandom.nextBytes(4).toBase64Url()
    return "${millis.toString().padStart(20, '0')}-${sequence.toString().padStart(19, '0')}-$randomSuffix"
}

private val keySequence = AtomicLong()
