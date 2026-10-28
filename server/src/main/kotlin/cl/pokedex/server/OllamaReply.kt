package cl.pokedex.server

import org.json.JSONObject

/** No registra fotos, prompts, credenciales ni el texto generado. */
fun parseOllamaReply(status: Int, body: String): JSONObject {
    val envelope = try { JSONObject(body) } catch (_: Exception) {
        throw ApiError(502, "Ollama devolvió una respuesta ilegible. Comprueba el servicio del PC.")
    }
    if (status != 200) {
        val error = envelope.optString("error").lowercase()
        if ("memory" in error || "out of" in error) throw ApiError(503, "Ollama no tiene memoria suficiente. Cierra otras aplicaciones del PC y vuelve a intentar.")
        throw ApiError(502, "Ollama rechazó el reconocimiento (HTTP $status). Revisa el modelo en el PC.")
    }
    val message = envelope.optJSONObject("message")
        ?: throw ApiError(502, "Ollama respondió sin un resultado de reconocimiento.")
    var content = message.optString("content").trim()
    if (content.startsWith("```json") && content.endsWith("```")) content = content.removePrefix("```json").removeSuffix("```").trim()
    else if (content.startsWith("```") && content.endsWith("```")) content = content.removePrefix("```").removeSuffix("```").trim()
    try { return interpretAnswer(content) }
    catch (_: Exception) {
        val reason = envelope.optString("done_reason", "unknown").take(24).replace(Regex("[^a-zA-Z_]"), "")
        System.err.println("Recognition format error: done=$reason tokens=${envelope.optInt("eval_count")} contentChars=${content.length} thinkingChars=${message.optString("thinking").length}")
        if (reason == "length") throw ApiError(502, "Qwen agotó el límite de respuesta antes de completar la identificación. Intenta de nuevo con un solo Pokémon bien encuadrado.")
        throw ApiError(502, "Qwen respondió con un formato no válido. No se guardó ningún encuentro; vuelve a intentar.")
    }
}
