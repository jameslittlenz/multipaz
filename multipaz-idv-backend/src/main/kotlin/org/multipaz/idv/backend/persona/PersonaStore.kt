package org.multipaz.idv.backend.persona

import kotlinx.serialization.json.Json

/** Thrown when a `personas.json` document fails validation. */
class PersonaStoreException(message: String) : Exception(message)

/**
 * Holds the dummy test identities used for persona-based issuance (`/idv/personas`,
 * `/idv/persona`), each with its portrait image bytes.
 *
 * Loading `personas.json` from `/app/data/personas/` or an admin upload (the container profile,
 * Component G) is deferred to M3 alongside the rest of the admin website; [fromJson] is the
 * reusable parsing/validation entry point that milestone will call into.
 */
class PersonaStore(private val personas: List<Persona>, private val portraits: Map<String, ByteArray>) {
    /** All available personas. */
    fun list(): List<Persona> = personas

    /** The persona with the given [id], or `null` if unknown. */
    fun find(id: String): Persona? = personas.find { it.id == id }

    /** The portrait image bytes for [persona], as loaded by [fromJson]'s `portraitLoader`. */
    fun portraitFor(persona: Persona): ByteArray =
        portraits[persona.portrait]
            ?: throw PersonaStoreException("No portrait loaded for '${persona.portrait}'")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** An empty store, for profiles that don't ship personas. */
        val EMPTY = PersonaStore(emptyList(), emptyMap())

        /**
         * Parses and validates a `personas.json` document, loading each referenced portrait via
         * [portraitLoader].
         *
         * @throws PersonaStoreException if ids aren't unique or a field fails validation.
         */
        fun fromJson(personasJson: String, portraitLoader: (String) -> ByteArray): PersonaStore {
            val personas = try {
                json.decodeFromString<List<Persona>>(personasJson)
            } catch (e: Exception) {
                throw PersonaStoreException("Malformed personas.json: ${e.message}")
            }
            val ids = mutableSetOf<String>()
            for (persona in personas) {
                if (persona.id.isBlank()) {
                    throw PersonaStoreException("Persona has a blank id")
                }
                if (!ids.add(persona.id)) {
                    throw PersonaStoreException("Duplicate persona id '${persona.id}'")
                }
                if (persona.sex !in 0..2) {
                    throw PersonaStoreException("Persona '${persona.id}' has invalid sex '${persona.sex}'")
                }
                if (persona.givenName.isBlank() || persona.familyName.isBlank()) {
                    throw PersonaStoreException("Persona '${persona.id}' is missing a name")
                }
                if (persona.documentNumber.isBlank()) {
                    throw PersonaStoreException("Persona '${persona.id}' is missing a document number")
                }
                // The synthetic passport's MRZ document number field is a fixed 9 characters
                // (ICAO 9303 Part 4 TD3); reject anything that won't fit rather than let
                // SyntheticPassportFactory.createPassport() throw MrzException on issuance.
                if (persona.documentNumber.length > 9) {
                    throw PersonaStoreException(
                        "Persona '${persona.id}' has a document number longer than 9 characters"
                    )
                }
            }
            val portraits = personas.associate { it.portrait to portraitLoader(it.portrait) }
            return PersonaStore(personas, portraits)
        }
    }
}
