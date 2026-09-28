package org.multipaz.idv.backend.persona

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class StorageOnlyEnvironment(private val storage: Storage) : BackendEnvironment {
    override fun <T : Any> getInterface(clazz: KClass<T>): T? =
        if (clazz == Storage::class) clazz.cast(storage) else null
}

class PersonaStorePersistenceTest {
    private val validJson = """
        [
          { "id": "p1", "given_name": "Ana", "family_name": "Smith", "birth_date": "1990-01-02",
            "sex": 2, "nationality": "XVA", "document_number": "VPT000001",
            "expiry_date": "2032-01-01", "portrait": "p1.jpg" }
        ]
    """.trimIndent()

    @Test
    fun loadReturnsNullBeforeAnyUpload() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            assertNull(PersonaStorePersistence.load())
        }
    }

    @Test
    fun savePersistsPersonasAndPortraitsAcrossReloads() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val portraitBytes = byteArrayOf(1, 2, 3, 4)
            PersonaStorePersistence.save(validJson, mapOf("p1.jpg" to portraitBytes))

            val reloaded = PersonaStorePersistence.load()!!
            val persona = reloaded.find("p1")!!
            assertEquals("Ana", persona.givenName)
            assertEquals(portraitBytes.toList(), reloaded.portraitFor(persona).toList())
        }
    }

    @Test
    fun saveValidatesBeforePersistingAnything() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            val duplicateIds = validJson.replace("\"id\": \"p1\"", "\"id\": \"p1\"") // still valid
            // Sanity: valid input alone doesn't throw.
            PersonaStorePersistence.save(duplicateIds, mapOf("p1.jpg" to byteArrayOf(1)))

            // A second, invalid upload must not clobber the first valid one.
            assertFailsWith<PersonaStoreException> {
                PersonaStorePersistence.save("not json", emptyMap())
            }
            assertEquals("Ana", PersonaStorePersistence.load()!!.find("p1")!!.givenName)
        }
    }

    @Test
    fun saveRejectsAMissingPortraitFile() = runTest {
        withContext(StorageOnlyEnvironment(EphemeralStorage())) {
            assertFailsWith<PersonaStoreException> {
                PersonaStorePersistence.save(validJson, emptyMap())
            }
        }
    }
}
