package cl.pokedex.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ApiError(val status: Int, message: String) : RuntimeException(message)

/** Una conexión por operación, consultas parametrizadas y transacciones breves. */
class Database(private val file: File) {
    private fun connect() = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also {
        it.createStatement().use { s -> s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000") }
    }
    init {
        file.parentFile?.mkdirs()
        connect().use { db -> db.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE, salt TEXT NOT NULL, hash TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id), expires INTEGER NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS seen (user_id INTEGER NOT NULL REFERENCES users(id), pokemon_id INTEGER NOT NULL, source TEXT NOT NULL CHECK(source IN ('camera','manual')), PRIMARY KEY(user_id,pokemon_id))")
            s.execute("CREATE TABLE IF NOT EXISTS friends (user_id INTEGER NOT NULL REFERENCES users(id), friend_id INTEGER NOT NULL REFERENCES users(id), PRIMARY KEY(user_id,friend_id), CHECK(user_id<>friend_id))")
            s.execute("CREATE TABLE IF NOT EXISTS friend_requests (sender_id INTEGER NOT NULL REFERENCES users(id), recipient_id INTEGER NOT NULL REFERENCES users(id), PRIMARY KEY(sender_id,recipient_id), CHECK(sender_id<>recipient_id))")
            s.execute("CREATE INDEX IF NOT EXISTS requests_recipient ON friend_requests(recipient_id)")
        } }
    }
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun passwordHash(password: String, salt: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), Base64.getUrlDecoder().decode(salt), 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun username(value: String): String {
        val name = value.trim().lowercase(java.util.Locale.ROOT)
        if (!name.matches(Regex("[a-z0-9_]{3,30}"))) throw ApiError(400, "Usuario: 3–30 letras sin acentos, números o guion bajo.")
        return name
    }
    fun authenticate(name: String, password: String, register: Boolean): JSONObject {
        val normalized = username(name)
        if (password.length !in 8..128) throw ApiError(400, "Contraseña: entre 8 y 128 caracteres.")
        connect().use { db ->
            if (register) {
                val salt = random()
                db.prepareStatement("INSERT OR IGNORE INTO users(username,salt,hash) VALUES(?,?,?)").use { s ->
                    s.setString(1, normalized); s.setString(2, salt)
                    s.setString(3, Base64.getEncoder().encodeToString(passwordHash(password, salt)))
                    if (s.executeUpdate() != 1) throw ApiError(409, "Ese usuario ya existe.")
                }
            }
            val id = db.prepareStatement("SELECT id,salt,hash FROM users WHERE username=?").use { s ->
                s.setString(1, normalized)
                s.executeQuery().use { r ->
                    if (!r.next()) { passwordHash(password, random()); throw ApiError(401, "Usuario o contraseña incorrectos.") }
                    if (!MessageDigest.isEqual(passwordHash(password, r.getString("salt")), Base64.getDecoder().decode(r.getString("hash"))))
                        throw ApiError(401, "Usuario o contraseña incorrectos.")
                    r.getInt("id")
                }
            }
            val token = random()
            db.prepareStatement("DELETE FROM sessions WHERE expires<=?").use { it.setLong(1, System.currentTimeMillis()); it.executeUpdate() }
            db.prepareStatement("INSERT INTO sessions VALUES(?,?,?)").use {
                it.setString(1, digest(token)); it.setInt(2, id); it.setLong(3, System.currentTimeMillis() + 7 * 86_400_000L); it.executeUpdate()
            }
            return JSONObject().put("session", token).put("username", normalized)
        }
    }
    fun user(token: String): Int {
        if (token.length !in 40..64) throw ApiError(401, "Inicia sesión de nuevo.")
        return connect().use { db -> db.prepareStatement("SELECT user_id FROM sessions WHERE token_hash=? AND expires>?").use {
            it.setString(1, digest(token)); it.setLong(2, System.currentTimeMillis())
            it.executeQuery().use { r -> if (!r.next()) throw ApiError(401, "La sesión venció. Ingresa de nuevo."); r.getInt(1) }
        } }
    }
    fun logout(token: String) { connect().use { db -> db.prepareStatement("DELETE FROM sessions WHERE token_hash=?").use { it.setString(1, digest(token)); it.executeUpdate() } } }
    fun record(user: Int, pokemon: Int, source: String) {
        if (pokemon !in kantoNames.values || source !in setOf("camera", "manual")) throw ApiError(400, "Encuentro no válido.")
        connect().use { db -> db.prepareStatement("INSERT INTO seen VALUES(?,?,?) ON CONFLICT(user_id,pokemon_id) DO UPDATE SET source=CASE WHEN seen.source='camera' THEN 'camera' ELSE excluded.source END").use {
            it.setInt(1, user); it.setInt(2, pokemon); it.setString(3, source); it.executeUpdate()
        } }
    }
    fun collection(user: Int): JSONArray = connect().use { db -> db.prepareStatement("SELECT pokemon_id,source FROM seen WHERE user_id=? ORDER BY pokemon_id").use {
        it.setInt(1, user); it.executeQuery().use { r -> JSONArray().apply { while (r.next()) put(JSONObject().put("id",r.getInt(1)).put("source",r.getString(2))) } }
    } }
    @Synchronized fun requestFriend(user: Int, name: String) {
        val normalized = username(name)
        connect().use { db ->
            val friend = db.prepareStatement("SELECT id FROM users WHERE username=?").use { it.setString(1, normalized); it.executeQuery().use { r ->
                if (!r.next()) throw ApiError(404, "No existe ese usuario en este PC."); r.getInt(1)
            } }
            if (friend == user) throw ApiError(400, "Escribe el usuario de otra persona.")
            db.prepareStatement("SELECT 1 FROM friends WHERE user_id=? AND friend_id=?").use {
                it.setInt(1,user); it.setInt(2,friend)
                it.executeQuery().use { r -> if (r.next()) throw ApiError(409, "Ya está en tus amigos.") }
            }
            db.prepareStatement("SELECT 1 FROM friend_requests WHERE sender_id=? AND recipient_id=?").use {
                it.setInt(1,friend); it.setInt(2,user)
                it.executeQuery().use { r -> if (r.next()) throw ApiError(409, "Ya te envió una solicitud. Puedes aceptarla abajo.") }
            }
            db.prepareStatement("INSERT OR IGNORE INTO friend_requests VALUES(?,?)").use { it.setInt(1,user); it.setInt(2,friend); it.executeUpdate() }
        }
    }
    fun requests(user: Int): JSONObject = connect().use { db ->
        fun names(incoming: Boolean): JSONArray {
            val query = if (incoming) "SELECT u.username FROM friend_requests r JOIN users u ON u.id=r.sender_id WHERE r.recipient_id=? ORDER BY u.username"
                else "SELECT u.username FROM friend_requests r JOIN users u ON u.id=r.recipient_id WHERE r.sender_id=? ORDER BY u.username"
            return db.prepareStatement(query).use { s ->
                s.setInt(1,user)
                s.executeQuery().use { r -> JSONArray().apply { while (r.next()) put(r.getString(1)) } }
            }
        }
        JSONObject().put("incoming", names(true)).put("outgoing", names(false))
    }
    /** Solo el destinatario autenticado puede responder. Aceptar crea ambos vínculos juntos. */
    @Synchronized fun respondToRequest(user: Int, name: String, accept: Boolean) {
        val normalized = username(name)
        connect().use { db ->
            db.autoCommit = false
            try {
                val sender = db.prepareStatement("SELECT r.sender_id FROM friend_requests r JOIN users u ON u.id=r.sender_id WHERE r.recipient_id=? AND u.username=?").use {
                    it.setInt(1,user); it.setString(2,normalized)
                    it.executeQuery().use { r -> if (!r.next()) throw ApiError(404, "La solicitud ya no está pendiente."); r.getInt(1) }
                }
                db.prepareStatement("DELETE FROM friend_requests WHERE sender_id=? AND recipient_id=?").use {
                    it.setInt(1,sender); it.setInt(2,user); it.executeUpdate()
                }
                if (accept) db.prepareStatement("INSERT OR IGNORE INTO friends VALUES(?,?),(?,?)").use {
                    it.setInt(1,user); it.setInt(2,sender); it.setInt(3,sender); it.setInt(4,user); it.executeUpdate()
                }
                db.commit()
            } catch (e: Exception) { db.rollback(); throw e }
        }
    }
    fun friends(user: Int): JSONArray = connect().use { db -> db.prepareStatement("SELECT u.id,u.username FROM friends f JOIN users u ON u.id=f.friend_id WHERE f.user_id=? ORDER BY u.username").use {
        it.setInt(1,user); it.executeQuery().use { r -> JSONArray().apply { while (r.next()) put(JSONObject().put("username",r.getString(2)).put("seen",collection(r.getInt(1)))) } }
    } }
}
