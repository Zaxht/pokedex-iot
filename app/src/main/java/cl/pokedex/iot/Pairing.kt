package cl.pokedex.iot

import org.json.JSONObject

object Pairing {
    fun encode(settings: PcSettings): String = JSONObject().put("type", "pokedex-pairing")
        .put("version", 1).put("address", settings.address)
        .put("fingerprint", settings.fingerprint).put("token", settings.token).toString()

    fun decode(raw: String): PcSettings {
        require(raw.length <= 2048) { "QR demasiado largo." }
        val data = JSONObject(raw)
        require(data.getString("type") == "pokedex-pairing" && data.get("version") == 1) {
            "Este QR no es una conexión compatible de Pokédex."
        }
        return PcSettings(data.getString("address"), data.getString("fingerprint"), data.getString("token")).validated()
    }

}
