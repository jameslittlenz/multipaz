package org.multipaz.idv.backend.settings

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            // The passport path stays closed until an admin opens it.
            assertFalse(settings.toData().passportIssuanceEnabled)
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
    fun rowSavedBeforePassportSettingExistedDecodesAsOff() {
        // Settings rows on servers deployed before passportIssuanceEnabled was added lack the key.
        val map = Cbor.decode(IdvSettingsRecord(passportIssuanceEnabled = true).toCbor()) as CborMap
        map.items.remove(Tstr("passportIssuanceEnabled"))
        val legacy = IdvSettingsRecord.fromCbor(Cbor.encode(map))
        assertNull(legacy.passportIssuanceEnabled)
        assertFalse(legacy.toData().passportIssuanceEnabled)
    }

    @Test
    fun dataConversionRoundTrips() {
        val record = IdvSettingsRecord(faceMatchThreshold = 0.7, offerTtlSeconds = 600)
        val data = record.toData()
        assertEquals(record.faceMatchThreshold, data.faceMatchThreshold)
        assertEquals(record.copy(passportIssuanceEnabled = false), data.toRecord())
    }
}
