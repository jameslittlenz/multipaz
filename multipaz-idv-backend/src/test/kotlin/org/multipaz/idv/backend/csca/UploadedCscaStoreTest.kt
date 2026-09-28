package org.multipaz.idv.backend.csca

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class StorageOnlyEnvironment(private val storage: Storage) : BackendEnvironment {
    override fun <T : Any> getInterface(clazz: KClass<T>): T? =
        if (clazz == Storage::class) clazz.cast(storage) else null
}

class UploadedCscaStoreTest {
    private suspend fun testCert(): X509Cert =
        SyntheticPassportFactory.createCsca(Crypto.createEcPrivateKey(EcCurve.P256), Algorithm.ES256)

    @Test
    fun emptyByDefault() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            assertTrue(UploadedCscaStore.list().isEmpty())
        }
    }

    @Test
    fun addAndListRoundTrips() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val cert = testCert()
            val added = UploadedCscaStore.add(cert.toPem())
            assertEquals(1, added.size)
            val listed = UploadedCscaStore.list()
            assertEquals(1, listed.size)
            assertEquals(cert.subject.name, listed[0].subject.name)
        }
    }

    @Test
    fun rejectsNonPemInput() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            var threw = false
            try {
                UploadedCscaStore.add("not a certificate")
            } catch (_: org.multipaz.rpc.handler.InvalidRequestException) {
                threw = true
            }
            assertTrue(threw)
        }
    }

    @Test
    fun deleteRemovesByFingerprint() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val cert = testCert()
            UploadedCscaStore.add(cert.toPem())
            val fingerprint = UploadedCscaStore.fingerprint(cert)
            assertTrue(UploadedCscaStore.delete(fingerprint))
            assertTrue(UploadedCscaStore.list().isEmpty())
        }
    }

    @Test
    fun uploadingConcatenatedPemAddsBoth() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val certA = testCert()
            val certB = testCert()
            val added = UploadedCscaStore.add(certA.toPem() + "\n" + certB.toPem())
            assertEquals(2, added.size)
            assertEquals(2, UploadedCscaStore.list().size)
        }
    }
}
