package cl.pokedex.server

import com.sun.net.httpserver.HttpExchange
import org.json.JSONObject

class AccountApi(private val db: Database) {
    private fun socialState(user: Int) = db.requests(user).put("friends", db.friends(user))
    private val attempts = mutableMapOf<String, Pair<Long, Int>>()
    @Synchronized private fun limit(address: String) {
        val now = System.currentTimeMillis()
        attempts.entries.removeIf { now - it.value.first >= 60_000 }
        val old = attempts[address] ?: (now to 0)
        if (old.second >= 10 || attempts.size >= 1000) throw ApiError(429, "Demasiados intentos. Espera un minuto.")
        attempts[address] = old.first to old.second + 1
    }
    private fun body(e: HttpExchange): JSONObject {
        val bytes = e.requestBody.readNBytes(4097)
        if (bytes.size > 4096) throw ApiError(413, "Solicitud demasiado grande.")
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { throw ApiError(400, "JSON no válido.") }
    }
    fun handle(e: HttpExchange): JSONObject? {
        val path = e.requestURI.path
        if (path !in setOf("/register", "/login", "/logout", "/me", "/friends", "/friend-requests", "/friend-requests/respond", "/encounters")) return null
        val method = e.requestMethod
        if (path in setOf("/register", "/login") && method == "POST") {
            limit(e.remoteAddress.address.hostAddress)
            val input = body(e)
            return db.authenticate(input.optString("username"), input.optString("password"), path == "/register")
        }
        val token = e.requestHeaders.getFirst("X-Session") ?: ""
        val user = db.user(token)
        return when {
            path == "/logout" && method == "POST" -> { db.logout(token); JSONObject().put("ok",true) }
            path == "/me" && method == "GET" -> JSONObject().put("seen", db.collection(user))
            path in setOf("/friends", "/friend-requests") && method == "GET" -> socialState(user)
            path in setOf("/friends", "/friend-requests") && method == "POST" -> {
                db.requestFriend(user,body(e).optString("username")); socialState(user)
            }
            path == "/friend-requests/respond" && method == "POST" -> {
                val input = body(e)
                if (input.opt("accept") !is Boolean) throw ApiError(400, "Indica aceptar o rechazar.")
                db.respondToRequest(user,input.optString("username"),input.getBoolean("accept")); socialState(user)
            }
            path == "/encounters" && method == "POST" -> {
                db.record(user,body(e).optInt("id",-1),"manual")
                JSONObject().put("seen",db.collection(user))
            }
            else -> throw ApiError(405, "Método no permitido.")
        }
    }
}
