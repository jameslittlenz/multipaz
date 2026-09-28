package org.multipaz.idv.backend.settings

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StorageOnlyEnvironment(private val storage: Storage) : BackendEnvironment {
    override fun <T : Any> getInterface(clazz: KClass<T>): T? =
        if (clazz == Storage::class) clazz.cast(storage) else null
}

class IdvSettingsStoreTest {
    @Test
    fun defaultsWhenNeverSet() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val settings = IdvSettingsRecord.get()
            assertEquals(IdvSettingsRecord.DEFAULT_FACE_MATCH_THRESHOLD, settings.faceMatchThreshold)
            // idv_require_active_auth stays off by default (Active Auth verifier deferred in M1).
            assertFalse(settings.requireActiveAuth)
            assertTrue(settings.dummyIssuanceEnabled)
        }
    }

    @Test
    fun updatePersistsAndRoundTrips() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val updated = IdvSettingsRecord.update(
                IdvSettingsRecord(faceMatchThreshold = 0.8, dummyIssuanceEnabled = false)
            )
            assertEquals(0.8, updated.faceMatchThreshold)
            val reread = IdvSettingsRecord.get()
            assertEquals(0.8, reread.faceMatchThreshold)
            assertFalse(reread.dummyIssuanceEnabled)

            // A second update (not the first insert) must also round-trip correctly.
            IdvSettingsRecord.update(reread.copy(faceMatchThreshold = 0.5))
            assertEquals(0.5, IdvSettingsRecord.get().faceMatchThreshold)
        }
    }

    @Test
    fun dataConversionRoundTrips() {
        val record = IdvSettingsRecord(faceMatchThreshold = 0.7, offerTtlSeconds = 600)
        val data = record.toData()
        assertEquals(record.faceMatchThreshold, data.faceMatchThreshold)
        assertEquals(record, data.toRecord())
    }
}
