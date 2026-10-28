package cl.pokedex.server

import org.junit.Assert.*
import org.junit.Test

/** Pruebas de protocolo sin fotos reales, GPU ni llamadas a Ollama. */
class ProtocolTest {
    @Test fun acceptsAll151NamesAndCommonPunctuation() {
        assertEquals((1..151).toSet(), kantoNames.values.toSet())
        kantoNames.forEach { (name, id) ->
            assertEquals(id, interpretAnswer(org.json.JSONObject().put("is_pokemon",true).put("pokemon",name).toString()).getInt("id"))
        }
        mapOf("Nidoran♀" to 29, "Nidoran♂" to 32, "Mr. Mime" to 122, "Farfetch’d" to 83, "Mewtwo" to 150, "Mew" to 151).forEach { (name, id) ->
            assertEquals(id, interpretAnswer(org.json.JSONObject().put("is_pokemon",true).put("pokemon",name).toString()).getInt("id"))
        }
        assertEquals("outside_catalog",interpretAnswer("""{"is_pokemon":true,"pokemon":"Nidoran"}""").getString("status"))
    }
    @Test fun mapsKnownSpecies() {
        assertEquals(25, interpretAnswer("""{"is_pokemon":true,"pokemon":"Pikachu"}""").getInt("id"))
    }
    @Test fun rejectsUnknownRatherThanInventingAnId() {
        assertEquals("unknown", interpretAnswer("""{"is_pokemon":false,"pokemon":null}""").getString("status"))
        val outside = interpretAnswer("""{"is_pokemon":true,"pokemon":"Chikorita"}""")
        assertEquals("outside_catalog", outside.getString("status"))
        assertFalse(outside.has("id"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongBooleanType() {
        interpretAnswer("""{"is_pokemon":"true","pokemon":"Pikachu"}""")
    }
    @Test fun rejectsNonImagePayload() { assertFalse(validImage("not an image".toByteArray())) }
}
