package org.multipaz.idv.backend.persona

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonaStoreTest {
    private val validJson = """
        [
          { "id": "p1", "given_name": "Ana", "family_name": "Smith", "birth_date": "1990-01-02",
            "sex": 2, "nationality": "XVA", "document_number": "VPT000001",
            "expiry_date": "2032-01-01", "portrait": "p1.jpg" },
          { "id": "p2", "given_name": "Bo", "family_name": "Lee", "birth_date": "1985-05-06",
            "sex": 1, "nationality": "XVA", "document_number": "VPT000002",
            "expiry_date": "2031-01-01", "portrait": "p2.jpg" }
        ]
    """.trimIndent()

    @Test
    fun loadsAndFindsPersonas() {
        val store = PersonaStore.fromJson(validJson) { name -> name.encodeToByteArray() }
        assertEquals(2, store.list().size)
        val p1 = store.find("p1")!!
        assertEquals("Ana", p1.givenName)
        assertEquals("p1.jpg".encodeToByteArray().toList(), store.portraitFor(p1).toList())
        assertEquals(null, store.find("unknown"))
    }

    @Test
    fun rejectsDuplicateIds() {
        val duplicate = validJson.replace("\"p2\"", "\"p1\"")
        assertFailsWith<PersonaStoreException> { PersonaStore.fromJson(duplicate) { ByteArray(0) } }
    }

    @Test
    fun rejectsInvalidSex() {
        val badSex = validJson.replaceFirst("\"sex\": 2", "\"sex\": 7")
        assertFailsWith<PersonaStoreException> { PersonaStore.fromJson(badSex) { ByteArray(0) } }
    }

    @Test
    fun rejectsDocumentNumberTooLongForMrz() {
        val tooLong = validJson.replaceFirst("\"VPT000001\"", "\"VPT0000001\"")
        assertFailsWith<PersonaStoreException> { PersonaStore.fromJson(tooLong) { ByteArray(0) } }
    }

    @Test
    fun rejectsBlankName() {
        val blankName = validJson.replaceFirst("\"Ana\"", "\"\"")
        assertFailsWith<PersonaStoreException> { PersonaStore.fromJson(blankName) { ByteArray(0) } }
    }

    @Test
    fun emptyStoreHasNoPersonas() {
        assertEquals(0, PersonaStore.EMPTY.list().size)
    }
}
