package org.multipaz.idv.backend.persona

import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec

/**
 * Persists the admin-uploaded `personas.json` plus its portraits (`docs/validatopia/PLAN.md`'s
 * Component F "Personas" page: "upload/replace personas.json + portraits"), so an upload survives
 * a server restart and is picked up by every request rather than only the process that received
 * the upload.
 */
object PersonaStorePersistence {
    private val jsonTableSpec = StorageTableSpec(
        name = "PersonaJson",
        supportPartitions = false,
        supportExpiration = false
    )
    private val portraitTableSpec = StorageTableSpec(
        name = "PersonaPortraits",
        supportPartitions = false,
        supportExpiration = false
    )
    private const val JSON_KEY = "personas.json"

    /** Loads the persisted [PersonaStore], or `null` if nothing has been uploaded yet. */
    suspend fun load(): PersonaStore? {
        val json = BackendEnvironment.getTable(jsonTableSpec).get(JSON_KEY)?.toByteArray()?.decodeToString()
            ?: return null
        val portraitTable = BackendEnvironment.getTable(portraitTableSpec)
        val portraits = portraitTable.enumerateWithData().associate { (name, data) -> name to data.toByteArray() }
        return PersonaStore.fromJson(json) { filename ->
            portraits[filename] ?: throw PersonaStoreException("No portrait stored for '$filename'")
        }
    }

    /**
     * Validates and persists a new `personas.json` plus its portraits, replacing whatever was
     * stored before, and returns the resulting [PersonaStore].
     *
     * @throws PersonaStoreException if validation fails (see [PersonaStore.fromJson]).
     */
    suspend fun save(personasJson: String, portraits: Map<String, ByteArray>): PersonaStore {
        // Validate before persisting anything, so a bad upload can't leave a half-updated store.
        val store = PersonaStore.fromJson(personasJson) { filename ->
            portraits[filename] ?: throw PersonaStoreException("Missing portrait file '$filename'")
        }
        val jsonTable = BackendEnvironment.getTable(jsonTableSpec)
        val jsonData = ByteString(personasJson.encodeToByteArray())
        if (jsonTable.get(JSON_KEY) == null) {
            jsonTable.insert(key = JSON_KEY, data = jsonData)
        } else {
            jsonTable.update(key = JSON_KEY, data = jsonData)
        }
        val portraitTable = BackendEnvironment.getTable(portraitTableSpec)
        for ((filename, bytes) in portraits) {
            val data = ByteString(bytes)
            if (portraitTable.get(filename) == null) {
                portraitTable.insert(key = filename, data = data)
            } else {
                portraitTable.update(key = filename, data = data)
            }
        }
        return store
    }
}
