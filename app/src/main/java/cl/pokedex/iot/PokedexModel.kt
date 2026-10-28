package cl.pokedex.iot

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray

data class FriendCollection(val username: String, val ids: Set<Int>)

class PokedexModel(application: Application) : AndroidViewModel(application) {
    var shared by mutableStateOf(DeviceSnapshot()); private set
    var device by mutableStateOf(DeviceState()); private set
    var deviceNavigation by mutableStateOf<String?>(null); private set
    private val simulator = SimulatedArduinoTransport()
    private val deviceSync = DeviceSynchronizer({ device = it }, ::receiveDeviceInput)
    private var speechCounter = 0L
    init { deviceSync.connect(simulator) }
    private fun synchronize(value: DeviceSnapshot) { shared = value; deviceSync.set(value) }
    fun consumeDeviceNavigation() { deviceNavigation = null }
    fun deviceForeground(active: Boolean) {
        synchronize(shared.copy(active = active, speech = 0))
        if (!active) { deviceNavigation = null; announcementId = null }
    }
    fun checkDevice() = deviceSync.checkTimeout()
    fun reconnectDevice() {
        deviceSync.connect(if (device.simulated) simulator else UnavailableArduinoTransport())
        shared = deviceSync.desired
    }
    fun showEntry(id: Int) {
        val pokemon = Catalog.find(id) ?: return
        val known = id in seen
        synchronize(shared.copy(pokemonId = id, revealed = known, name = if (known) pokemon.name else "",
            description = if (known) "${pokemon.name}. Tipo ${pokemon.type}. ${pokemon.description}" else "",
            view = "entry", speech = if (shared.pokemonId == id) shared.speech else 0))
    }
    fun showView(view: String) {
        if (view in setOf("collection", "scan", "friends", "settings") && shared.view != view) {
            consumeAnnouncement(); synchronize(shared.copy(view = view, speech = 0))
        }
    }
    fun stopSpeech() { consumeAnnouncement(); synchronize(shared.copy(speech = 0)) }
    fun requestSpeech(id: Int) {
        if (!shared.active || !soundEnabled || id !in seen) return
        showEntry(id)
        synchronize(shared.copy(speech = ++speechCounter))
        announcementId = id
    }
    fun volume(value: Int) {
        val normalized = value.coerceIn(0, 100)
        if (normalized != shared.volume) { consumeAnnouncement(); synchronize(shared.copy(volume = normalized, speech = 0)) }
    }
    fun simulateInput(action: DeviceAction, value: Int = 0) {
        if (device.simulated && device.connected) simulator.input(DeviceInput(action, value))
    }
    fun disconnectSimulator() { if (device.simulated) simulator.disconnect() }
    private fun receiveDeviceInput(input: DeviceInput) {
        if (!loggedIn || !shared.active) return
        when (input.action) {
            DeviceAction.NEXT, DeviceAction.PREVIOUS -> {
                val id = shared.pokemonId?.let { (it + if (input.action == DeviceAction.NEXT) 1 else -1).coerceIn(1, 151) } ?: 1
                stopLive(); stopSpeech(); showEntry(id); deviceNavigation = "entry/$id"
            }
            DeviceAction.SCAN -> { stopSpeech(); deviceNavigation = "scan" }
            DeviceAction.FRIENDS -> { stopLive(); stopSpeech(); deviceNavigation = "friends" }
            DeviceAction.SPEAK -> shared.pokemonId?.takeIf { it in seen }?.let { requestSpeech(it); deviceNavigation = "entry/$it" }
            DeviceAction.LED -> led(input.value == 1)
            DeviceAction.SOUND -> if (soundEnabled != (input.value == 1)) toggleSound()
            DeviceAction.VOLUME -> volume(input.value)
        }
    }
    private val store = LocalStore(application)
    var hasAccount by mutableStateOf(true); private set
    var loggedIn by mutableStateOf(false); private set
    var username by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var authError by mutableStateOf(""); private set
    var cameraSeen by mutableStateOf(emptySet<Int>()); private set
    var seen by mutableStateOf(emptySet<Int>()); private set
    var pc by mutableStateOf(store.pcSettings()); private set
    var pcStatus by mutableStateOf(if (store.connectionLost) "No se pudo recuperar la conexión guardada. Vuelve a escanear el QR." else "PC sin comprobar"); private set
    var scanStatus by mutableStateOf("Inicia la cámara en vivo o selecciona una imagen."); private set
    var photo by mutableStateOf<ByteArray?>(null); private set
    var recognizedId by mutableStateOf<Int?>(null); private set
    var resultId by mutableStateOf<Int?>(null); private set
    fun consumeResult() { resultId = null }
    var announcementId by mutableStateOf<Int?>(null); private set
    val soundEnabled get() = shared.sound
    fun consumeAnnouncement() { announcementId = null }
    fun toggleSound() { synchronize(shared.copy(sound = !shared.sound, speech = 0)); if (!soundEnabled) announcementId = null }
    var working by mutableStateOf(false); private set
    private var requestJob: Job? = null
    private var requestVersion = 0
    private val client = PcClient()
    private var session = ""
    var friends by mutableStateOf(emptyList<FriendCollection>()); private set
    var incomingRequests by mutableStateOf(emptyList<String>()); private set
    var outgoingRequests by mutableStateOf(emptyList<String>()); private set
    var socialBusy by mutableStateOf(false); private set
    var socialError by mutableStateOf(""); private set
    private var socialGeneration = 0
    var socialStatus by mutableStateOf(""); private set
    fun registration(value: Boolean) { hasAccount = !value; authError = "" }
    private fun applyCollection(rows: JSONArray) {
        seen = (0 until rows.length()).map { rows.getJSONObject(it).getInt("id") }.toSet()
        cameraSeen = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.getString("source") == "camera" }.map { it.getInt("id") }.toSet()
        if (shared.view == "entry") shared.pokemonId?.let(::showEntry)
    }
    private fun applyFriends(rows: JSONArray) {
        friends = (0 until rows.length()).map { i ->
            val friend = rows.getJSONObject(i)
            val rowsSeen = friend.getJSONArray("seen")
            FriendCollection(friend.getString("username"), (0 until rowsSeen.length()).map { rowsSeen.getJSONObject(it).getInt("id") }.toSet())
        }
    }
    fun refreshCollection() = performRequest {
        val result = withContext(Dispatchers.IO) { client.request(pc, "/me", session = session) }
        applyCollection(result.getJSONArray("seen")); socialStatus = "Colección actualizada desde el PC."
    }
    fun refreshFriends(name: String? = null, quiet: Boolean = false) = socialRequest(
        if (name == null) "/friends" else "/friend-requests",
        name?.let { JSONObject().put("username", it) },
        if (quiet) null else if (name == null) "Amigos actualizados desde el PC." else "Solicitud enviada."
    )
    fun respondToFriend(name: String, accept: Boolean) = socialRequest("/friend-requests/respond",
        JSONObject().put("username", name).put("accept", accept),
        if (accept) "Ahora son amigos." else "Solicitud rechazada.")

    /** Sin bloquear la cámara; una sola consulta social pendiente y sin respuestas de sesiones anteriores. */
    private fun socialRequest(path: String, data: JSONObject?, message: String?) {
        if (!loggedIn || socialBusy) return
        val settings = pc
        val accountSession = session
        val generation = socialGeneration
        socialBusy = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { client.request(settings, path, session = accountSession, data = data) }
                if (generation == socialGeneration && loggedIn && session == accountSession) {
                    applyFriends(result.getJSONArray("friends"))
                    fun names(key: String) = result.getJSONArray(key).let { rows -> (0 until rows.length()).map { rows.getString(it) } }
                    incomingRequests = names("incoming"); outgoingRequests = names("outgoing")
                    synchronize(shared.copy(notifications = incomingRequests.size))
                    socialError = ""
                    if (message != null) socialStatus = message
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == socialGeneration && loggedIn) {
                    socialError = if (e is PcApiException) e.message ?: "No se pudo actualizar." else "Sin conexión con el PC. Las solicitudes se actualizarán al reconectar."
                    if (e is PcApiException && e.status == 401) { val error = socialError; logout(); authError = error }
                }
            } finally { if (generation == socialGeneration) socialBusy = false }
        }
    }
    private fun clearSocial() {
        socialGeneration++; socialBusy = false
        friends = emptyList(); incomingRequests = emptyList(); outgoingRequests = emptyList()
        socialStatus = ""; socialError = ""
        synchronize(shared.copy(notifications = 0))
    }

    fun configure(address: String, pin: String, token: String): Boolean {
        val settings = try { PcSettings(address, pin, token).validated().also(store::savePc) }
        catch (_: Exception) { pcStatus = "No se pudo guardar. Revisa dirección HTTPS, huella y clave de 64 caracteres."; return false }
        if (loggedIn) logout()
        requestVersion++
        requestJob?.cancel()
        working = false
        pc = settings
        pcStatus = "Conexión guardada de forma cifrada."
        return true
    }
    fun forgetPc() {
        if (loggedIn) logout()
        stopLive()
        try { store.clearPc() }
        catch (_: Exception) { pcStatus = "No se pudo borrar la conexión. Inténtalo otra vez."; return }
        requestVersion++; requestJob?.cancel(); working = false
        pc = PcSettings("", "", ""); pcStatus = "PC desvinculado."
    }
    fun importQr(raw: String): Boolean {
        if (busy || working) return false
        val settings = try { Pairing.decode(raw) }
        catch (_: Exception) { pcStatus = "QR no compatible. Apunta al QR de tu servidor Pokédex."; return false }
        return configure(settings.address, settings.fingerprint, settings.token)
    }
    var live by mutableStateOf(false); private set
    private val liveSession = LiveSession()
    fun startLive() {
        if (working) return
        try { pc.validated() } catch (_: Exception) { scanStatus = "Primero vincula el PC desde Control."; return }
        announcementId = null
        showView("scan")
        liveSession.start(); live = true; recognizedId = null
        scanStatus = "Apunta a un Pokémon. Se enviarán fotogramas automáticamente al PC."
    }
    fun stopLive() {
        if (live) scanStatus = if (working) "Cámara detenida. El PC puede terminar y guardar el encuentro ya enviado; no se mostrarán nuevas respuestas en el visor." else "Reconocimiento en vivo detenido."
        liveSession.stop(); live = false
    }
    fun liveFrame(bytes: ByteArray) {
        if (!live || working || !loggedIn) return
        identifyImage(bytes, liveSession.ticket())
    }
    fun checkPc() = performRequest {
        val result = withContext(Dispatchers.IO) { client.request(pc, "/health") }
        pcStatus = if (result.getBoolean("installed")) "PC conectado · Qwen3-VL 4B instalado" else "PC conectado · falta instalar qwen3-vl:4b"
    }
    fun loadPhoto(uri: Uri) = performRequest {
        photo = null
        recognizedId = null
        scanStatus = "Preparando foto…"
        photo = withContext(Dispatchers.IO) {
            try { preparePhoto(getApplication(), uri) }
            finally { java.io.File(getApplication<Application>().cacheDir, "photos/capture.jpg").delete() }
        }
        scanStatus = "Foto lista. Pulsa Identificar para enviarla al PC."
    }
    fun identify() {
        val image = photo ?: return
        identifyImage(image)
    }
    private fun identifyImage(image: ByteArray, ticket: Long? = null) {
        val settings = pc
        val accountSession = session
        performRequest {
            if (ticket == null) recognizedId = null
            scanStatus = "Analizando en tu PC. La primera consulta puede tardar hasta 3 minutos."
            val result = withContext(Dispatchers.IO) { client.request(settings, "/recognize", image, session = accountSession) }
            if (ticket != null && !liveSession.accepts(ticket)) {
                scanStatus = "Reconocimiento en vivo detenido. Puedes iniciar otra sesión."
                return@performRequest
            }
            pcStatus = "Respuesta recibida del PC"
            when (result.getString("status")) {
                "matched" -> {
                    val pokemon = Catalog.find(result.getInt("id")) ?: error("Respuesta fuera del catálogo.")
                    selectedId = pokemon.id
                    recognizedId = pokemon.id
                    resultId = pokemon.id
                    cameraSeen = cameraSeen + pokemon.id
                    seen = seen + pokemon.id
                    showEntry(pokemon.id); led(true)
                    live = false
                    liveSession.stop()
                    photo = null
                    requestSpeech(pokemon.id)
                    scanStatus = "El modelo identificó ${pokemon.name}. Guardado en tu Pokédex. Resultado del último fotograma analizado." +
                        if (device.simulated) " Salida Arduino simulada." else " Arduino real sin conexión; resultado guardado en el PC."
                }
                "unknown" -> { recognizedId = null; scanStatus = "No identificado. Apunta a un solo Pokémon con buena iluminación." }
                "outside_catalog" -> { recognizedId = null; scanStatus = "El modelo propone ${result.getString("name")}, pero no pertenece a los 151 originales de nuestro catálogo. No se guardó." }
                else -> error("Respuesta del servidor no válida.")
            }
        }
    }
    private fun performRequest(action: suspend () -> Unit) {
        if (working) return
        working = true
        val version = ++requestVersion
        requestJob = viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is javax.net.ssl.SSLException -> "No se pudo verificar el certificado. Revisa la huella del PC y la fecha del celular."
                    is java.net.SocketTimeoutException -> "Se agotó el tiempo. Comprueba el PC y vuelve a intentar."
                    is java.net.ConnectException, is java.net.UnknownHostException -> "No se pudo conectar. Revisa dirección, Wi-Fi y servidor del PC."
                    else -> e.message ?: "No se pudo completar la operación."
                }
                if (live) stopLive()
                scanStatus = message; pcStatus = message; socialStatus = message
                if (e is PcApiException && e.status == 401) {
                    loggedIn = false; session = ""; seen = emptySet(); cameraSeen = emptySet(); clearSocial(); authError = message
                    synchronize(DeviceSnapshot()); deviceNavigation = null; photo = null; recognizedId = null; announcementId = null
                }
            } finally { if (version == requestVersion) working = false }
        }
    }
    var selectedId by mutableStateOf(25); private set

    fun login(name: String, password: String) {
        if (busy || working) return
        try { pc.validated() } catch (_: Exception) { authError = "Vincula primero el PC con su QR."; return }
        val register = !hasAccount
        val settings = pc
        busy = true; authError = ""
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.request(settings, if (register) "/register" else "/login",
                        data = JSONObject().put("username", name).put("password", password))
                }
                val token = result.getString("session")
                val collection = withContext(Dispatchers.IO) { client.request(settings, "/me", session = token) }
                session = token; username = result.getString("username")
                applyCollection(collection.getJSONArray("seen")); clearSocial()
                hasAccount = true; loggedIn = true
            } catch (e: Exception) { authError = e.message ?: "No se pudo acceder al PC." }
            finally { busy = false }
        }
    }
    fun select(id: Int) { if (Catalog.find(id) != null) selectedId = id }
    fun recordSimulation() {
        if (!device.simulated || !loggedIn) return
        val id = selectedId
        performRequest {
            val result = withContext(Dispatchers.IO) { client.request(pc, "/encounters", session = session, data = JSONObject().put("id", id)) }
            applyCollection(result.getJSONArray("seen")); showEntry(id); led(true)
            deviceNavigation = "entry/$id"
            scanStatus = "Encuentro de prueba guardado en el PC."
        }
    }
    fun mode(simulated: Boolean) {
        stopSpeech()
        deviceSync.connect(if (simulated) simulator else UnavailableArduinoTransport())
        shared = deviceSync.desired
    }
    fun led(enabled: Boolean) { synchronize(shared.copy(led = enabled)) }
    override fun onCleared() { deviceSync.close(); super.onCleared() }
    fun logout() {
        val oldSession = session
        val settings = pc
        session = ""
        if (oldSession.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.request(settings, "/logout", session = oldSession, data = JSONObject()) }
        }
        seen = emptySet(); cameraSeen = emptySet(); clearSocial()
        stopLive()
        requestVersion++
        requestJob?.cancel(); working = false
        loggedIn = false; authError = ""; synchronize(DeviceSnapshot()); deviceNavigation = null
        photo = null; recognizedId = null; announcementId = null; resultId = null
        pcStatus = "PC sin comprobar"
        scanStatus = "Inicia la cámara en vivo o selecciona una imagen."
    }
}
