package cl.pokedex.iot

import android.content.Context

/** Solo conserva la vinculación cifrada. Las cuentas y colecciones están en el PC. */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("pokedex_local", Context.MODE_PRIVATE)
    var connectionLost = false; private set
    fun pcSettings(): PcSettings {
        val encrypted = prefs.getString("pc_encrypted", null)
        if (encrypted != null) {
            try { return Pairing.decode(ConnectionVault().decrypt(encrypted)) }
            catch (_: Exception) { connectionLost = true; runCatching { clearPc() } }
        }
        return PcSettings(prefs.getString("pc_address", "")!!, prefs.getString("pc_pin", "")!!, "")
    }
    fun savePc(settings: PcSettings) {
        val encrypted = ConnectionVault().encrypt(Pairing.encode(settings.validated()))
        check(prefs.edit().putString("pc_encrypted", encrypted).remove("pc_address").remove("pc_pin").commit())
    }
    fun clearPc() { check(prefs.edit().remove("pc_encrypted").remove("pc_address").remove("pc_pin").commit()) }
}
