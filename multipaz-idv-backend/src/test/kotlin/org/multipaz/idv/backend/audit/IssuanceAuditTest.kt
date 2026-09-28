package org.multipaz.idv.backend.audit

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertEquals
import kotlin.time.Clock

private class StorageOnlyEnvironment(private val storage: Storage) : BackendEnvironment {
    override fun <T : Any> getInterface(clazz: KClass<T>): T? =
        if (clazz == Storage::class) clazz.cast(storage) else null
}

class IssuanceAuditTest {
    @Test
    fun maskKeepsOnlyLastFourCharacters() {
        assertEquals("*****1234", IssuanceAuditRecord.mask("PA1231234"))
        assertEquals("***", IssuanceAuditRecord.mask("abc"))
        assertEquals("", IssuanceAuditRecord.mask(""))
    }

    @Test
    fun recordsAreListedInInsertionOrder() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            IssuanceAuditRecord.record(
                IssuanceAuditRecord(
                    timestamp = Clock.System.now(),
                    method = IssuanceMethod.PASSPORT.name,
                    accepted = true,
                    nationality = "NZ",
                    maskedDocumentNumber = "*****1234",
                    faceScore = 0.9,
                    flags = emptyList(),
                    sessionId = "s1",
                )
            )
            IssuanceAuditRecord.record(
                IssuanceAuditRecord(
                    timestamp = Clock.System.now(),
                    method = IssuanceMethod.PERSONA.name,
                    accepted = false,
                    nationality = null,
                    maskedDocumentNumber = null,
                    faceScore = null,
                    flags = listOf("FACE_MATCH_BELOW_THRESHOLD"),
                    sessionId = "p1",
                )
            )
            val entries = IssuanceAuditRecord.list().map { (id, record) -> record.toEntry(id) }
            assertEquals(2, entries.size)
            assertEquals("PASSPORT", entries[0].method)
            assertEquals("PERSONA", entries[1].method)
            assertEquals(listOf("FACE_MATCH_BELOW_THRESHOLD"), entries[1].flags)
        }
    }
}
