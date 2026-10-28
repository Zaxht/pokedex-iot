package cl.pokedex.server

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OllamaReplyTest {
    private fun response(content: String, reason: String = "stop") = JSONObject()
        .put("message",JSONObject().put("content",content)).put("done_reason",reason).put("eval_count",128).toString()
    @Test fun completeJsonWithWhitespaceIsAcceptedEvenAtTokenLimit() {
        val result = parseOllamaReply(200,response("""{"is_pokemon":true,"pokemon":"Pikachu"}""" + " ".repeat(400), "length"))
        assertEquals(25,result.getInt("id"))
    }
    @Test fun truncatedOutputIsExplicitAndNeverBecomesAnEncounter() {
        try { parseOllamaReply(200,response("""{"is_pokemon":true,"pokemon":"Pika""", "length")); fail() }
        catch (e: ApiError) { assertEquals(502,e.status); assertTrue(e.message!!.contains("límite")) }
    }
    @Test fun fencedJsonAndUnknownAreHandled() {
        assertEquals("unknown",parseOllamaReply(200,response("```json\n{\"is_pokemon\":false,\"pokemon\":null}\n```" )).getString("status"))
    }
    @Test fun memoryErrorIsNotReportedAsGenericOperationFailure() {
        try { parseOllamaReply(500,"""{"error":"model requires more system memory"}"""); fail() }
        catch (e: ApiError) { assertEquals(503,e.status); assertTrue(e.message!!.contains("memoria")) }
    }
}
