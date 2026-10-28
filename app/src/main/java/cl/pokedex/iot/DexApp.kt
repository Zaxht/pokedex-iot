package cl.pokedex.iot

import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    private lateinit var voice: Voice
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice = Voice(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = DexRed, secondary = DexInk,
                background = DexLcd, surface = DexLcd, onSurface = DexInk, onBackground = DexInk,
                surfaceVariant = Color(0xFFCAD9BA))) {
                val model: PokedexModel = viewModel()
                if (model.loggedIn) PokedexApp(model, voice) else LoginScreen(model)
            }
        }
    }
    override fun onStop() { voice.stop(); super.onStop() }
    override fun onDestroy() { voice.close(); super.onDestroy() }
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun Notice(text: String) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LoginScreen(model: PokedexModel) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(model.username) }
    var password by remember { mutableStateOf("") }
    DexShell(if (settings) "Conexión" else "Acceso de entrenador",
        back = if (settings) ({ settings = false }) else null,
        settings = if (!settings) ({ settings = true }) else null) {
        if (settings) Page { PcConfiguration(model) }
        else Page {
            Spacer(Modifier.height(12.dp))
            Image(painterResource(R.drawable.ic_pokedex), null, Modifier.size(100.dp).align(Alignment.CenterHorizontally))
            Text(if (model.hasAccount) "¡Hola, entrenador!" else "Nuevo entrenador", fontSize = 25.sp, fontWeight = FontWeight.Black)
            OutlinedButton(onClick = { settings = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp))
                Text(if (model.pc.token.isEmpty()) "Vincular PC" else "PC vinculado")
            }
            OutlinedTextField(name, { name = it }, label = { Text("Usuario") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            if (!model.hasAccount) Notice("Comparte tu colección aceptando solicitudes de otros entrenadores de este PC.")
            Notice(model.authError)
            Button(onClick = { model.login(name, password) }, enabled = !model.busy && !model.working,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (model.busy) "Conectando…" else if (model.hasAccount) "Ingresar" else "Crear cuenta")
            }
            TextButton(onClick = { model.registration(model.hasAccount) }, enabled = !model.busy) {
                Text(if (model.hasAccount) "Crear una cuenta" else "Ya tengo cuenta")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PokedexApp(model: PokedexModel, voice: Voice) {
    val nav = rememberNavController()
    var listView by rememberSaveable { mutableStateOf(false) }
    var devicePanel by rememberSaveable { mutableStateOf(false) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "collection"
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { model.refreshFriends(quiet = true); delay(5_000) }
        }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { model.checkDevice(); delay(1000) }
        }
    }
    LaunchedEffect(model.shared.volume, model.soundEnabled, model.shared.speech) {
        voice.volume(model.shared.volume)
        if (!model.soundEnabled || model.shared.speech == 0L) voice.stop()
    }
    LaunchedEffect(route) {
        if (route.startsWith("entry")) {
            (model.shared.pokemonId ?: entry?.arguments?.getString("id")?.toIntOrNull())?.let(model::showEntry)
        } else model.showView(route)
    }
    LaunchedEffect(model.deviceNavigation) {
        model.deviceNavigation?.let { target ->
            model.consumeDeviceNavigation()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                if (target == "scan" || target == "friends") devicePanel = false
                if (!(target.startsWith("entry/") && route.startsWith("entry"))) nav.navigate(target) { launchSingleTop = true }
                if (target == "scan" && route == "scan") model.startLive()
            }
        }
    }
    DisposableEffect(owner) {
        model.deviceForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.deviceForeground(true)
            if (event == Lifecycle.Event.ON_STOP) { model.stopLive(); model.deviceForeground(false); voice.stop() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.deviceForeground(false); voice.stop() }
    }
    LaunchedEffect(model.resultId) {
        model.resultId?.let { id ->
            model.consumeResult()
            if (route == "scan" && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) nav.navigate("entry/$id") { launchSingleTop = true }
            else model.consumeAnnouncement()
        }
    }
    val tabs = listOf(Triple("collection", "Pokédex", R.drawable.ic_nav_pokedex),
        Triple("scan", "Escanear", R.drawable.ic_nav_scan),
        Triple("friends", "Amigos", R.drawable.ic_nav_friends))
    DexShell(title = when (route) { "collection" -> "Kanto / ${model.seen.size} de 151"; "scan" -> "Sensor óptico"; "friends" -> "Entrenadores"; "settings" -> "Ajustes"; else -> "Archivo de especie" },
        notificationCount = model.shared.notifications, activityLed = model.shared.led,
        deviceStatus = if (!model.device.connected) "SIN ENLACE" else if (model.device.simulated) "SIM" else "ARDUINO",
        notifications = {
            voice.stop(); model.stopLive()
            nav.navigate("friends") { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
        },
        back = if (route.startsWith("entry") || route == "settings") ({ voice.stop(); nav.popBackStack() }) else null,
        settings = if (route != "settings") ({ voice.stop(); nav.navigate("settings") { launchSingleTop = true } }) else null,
        footer = {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                tabs.forEach { (path, title, icon) ->
                    val selected = route == path || (path == "collection" && route.startsWith("entry"))
                    Button(onClick = {
                        voice.stop(); model.stopLive()
                        nav.navigate(path) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, modifier = Modifier.weight(1f).height(58.dp).border(2.dp, DexInk, RoundedCornerShape(9.dp)),
                        shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (selected) DexInk else Color(0xFF65C3EA), contentColor = if (selected) Color.White else DexInk)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            BadgedBox(badge = { if (path == "friends" && model.incomingRequests.isNotEmpty()) Badge() }) {
                                Icon(painterResource(icon), title, Modifier.size(32.dp))
                            }
                            Box(Modifier.size(14.dp, 2.dp).background(if (selected) Color(0xFF8FE596) else Color.Transparent, RoundedCornerShape(1.dp)))
                        }
                    }
                }
            }
        }) {
        NavHost(nav, startDestination = "collection", enterTransition = { fadeIn(tween(250)) }, exitTransition = { fadeOut(tween(160)) }) {
            composable("collection") { CollectionScreen(model, listView, { listView = it }) { model.showEntry(it); nav.navigate("entry/$it") } }
            composable("scan") { ScanScreen(model) }
            composable("friends") { FriendsScreen(model) { model.showEntry(it); nav.navigate("entry/$it") } }
            composable("entry/{id}") { destination ->
                DetailScreen(model, voice, destination.arguments?.getString("id")?.toIntOrNull()) {
                    voice.stop(); listView = true
                    nav.navigate("collection") {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true; restoreState = true
                    }
                }
            }
            composable("settings") { SettingsScreen(model, voice) { devicePanel = true } }
        }
    }
    if (devicePanel) ModalBottomSheet(onDismissRequest = { devicePanel = false }, containerColor = DexLcd) {
        ArduinoSimulator(model) { devicePanel = false }
    }
}

@Composable
private fun CollectionScreen(model: PokedexModel, listView: Boolean, changeView: (Boolean) -> Unit, open: (Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlySeen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshCollection() }
    val filtered = Catalog.pokemon.filter { matchesVisiblePokemon(it, model.seen, query) && (!onlySeen || it.id in model.seen) }
    val header: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${model.seen.size} / 151", Modifier.weight(1f), fontSize = 29.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    IconButton(onClick = model::refreshCollection, enabled = !model.working) { Icon(Icons.Outlined.Refresh, "Actualizar colección") }
                }
                LinearProgressIndicator(progress = { model.seen.size / 151f }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!onlySeen, { onlySeen = false }, label = { Text("Todos") })
                    FilterChip(onlySeen, { onlySeen = true }, label = { Text("Vistos") })
                    Spacer(Modifier.weight(1f))
                    IconToggleButton(checked = listView, onCheckedChange = changeView) {
                        Icon(if (listView) Icons.Outlined.GridView else Icons.Outlined.ViewList,
                            if (listView) "Ver tarjetas" else "Ver lista")
                    }
                }
                if (model.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (model.socialStatus.isNotEmpty() && !model.socialStatus.endsWith("desde el PC.")) Notice(model.socialStatus)
                if (filtered.isEmpty()) Notice("Sin resultados.")
            }
    }
    if (listView) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item { header() }
            items(filtered, key = { it.id }) { pokemon ->
                val known = pokemon.id in model.seen
                OutlinedCard(onClick = { open(pokemon.id) }, colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFEAF0DF))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("#${pokemon.id.toString().padStart(3, '0')}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Image(painterResource(pokemon.image), null, Modifier.size(48.dp), colorFilter = if (known) null else ColorFilter.tint(Color.Black))
                        Text(if (known) pokemon.name else "???", Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1)
                        if (known) PokeBall(true, Modifier.size(22.dp))
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(filtered, key = { it.id }) { pokemon -> PokemonTile(pokemon, pokemon.id in model.seen) { open(pokemon.id) } }
        }
    }
}

@Composable
private fun PokemonTile(pokemon: Pokemon, known: Boolean, open: () -> Unit) {
    OutlinedCard(onClick = open, shape = RoundedCornerShape(12.dp), colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFEAF0DF))) {
        Column(Modifier.fillMaxWidth().padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("#${pokemon.id.toString().padStart(3,'0')}", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                if (known) PokeBall(true, Modifier.size(22.dp))
            }
            Image(painterResource(pokemon.image), if (known) pokemon.name else "Pokémon desconocido ${pokemon.id}",
                Modifier.size(100.dp), colorFilter = if (known) null else ColorFilter.tint(Color.Black))
            Text(if (known) pokemon.name else "???", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun DetailScreen(model: PokedexModel, voice: Voice, id: Int?, openList: () -> Unit) {
    if (id == null || Catalog.find(id) == null) { Page { Notice("Ficha no disponible.") }; return }
    val pager = rememberPagerState(initialPage = id - 1, pageCount = { Catalog.pokemon.size })
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val selected = Catalog.pokemon[pager.settledPage]
    var lastPage by remember { mutableIntStateOf(id - 1) }
    LaunchedEffect(model.shared.pokemonId) {
        model.shared.pokemonId?.let { target -> if (pager.settledPage != target - 1) pager.scrollToPage(target - 1) }
    }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        if (!pager.isScrollInProgress && lastPage != pager.settledPage) {
            lastPage = pager.settledPage; model.showEntry(lastPage + 1)
        }
    }
    DisposableEffect(Unit) { onDispose { voice.stop(); model.consumeAnnouncement() } }
    LaunchedEffect(pager.isScrollInProgress) {
        if (pager.isScrollInProgress) { voice.stop(); model.stopSpeech() }
    }
    // La entrada de navegación llega a RESUMED después de la transición.
    // Consumir antes el evento perdía la voz automática del reconocimiento.
    LaunchedEffect(lifecycle, pager.settledPage, model.announcementId, model.soundEnabled) {
        if (lifecycle != Lifecycle.State.RESUMED) voice.stop()
        else if (!pager.isScrollInProgress && selected.id in model.seen && model.announcementId == selected.id) {
            model.consumeAnnouncement()
            if (model.soundEnabled) voice.announce(selected)
        }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("species-pager"), key = { Catalog.pokemon[it].id }) { page ->
        val pokemon = Catalog.pokemon[page]
        DetailPage(pokemon, pokemon.id in model.seen, voice, model.soundEnabled, { model.requestSpeech(pokemon.id) }, openList) { next ->
            scope.launch { pager.animateScrollToPage(next - 1) }
        }
    }
}

@Composable
private fun DetailPage(pokemon: Pokemon, known: Boolean, voice: Voice, soundEnabled: Boolean, speak: () -> Unit,
                       openList: () -> Unit, open: (Int) -> Unit) {
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("N.º ${pokemon.id.toString().padStart(3,'0')}", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (known) PokeBall(true)
            IconButton(onClick = openList) { Icon(Icons.Outlined.ViewList, "Ver lista") }
        }
        Text("‹  Desliza para explorar  ›", Modifier.align(Alignment.CenterHorizontally), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Surface(color = Color(0xFFC8DABA), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(pokemon.image), if (known) pokemon.name else "Silueta desconocida", Modifier.size(200.dp),
                    colorFilter = if (known) null else ColorFilter.tint(Color.Black))
                Text(if (known) pokemon.name else "???", fontSize = 31.sp, fontWeight = FontWeight.Black)
                if (known) Text(pokemon.category, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (known) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                pokemon.type.split(" · ").forEach { type ->
                    Surface(color = DexInk, shape = RoundedCornerShape(20.dp)) { Text(type, Modifier.padding(horizontal = 13.dp, vertical = 6.dp), color = Color.White, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Metric("ALTURA", pokemon.height); Metric("PESO", pokemon.weight)
            }
            Text(pokemon.description, lineHeight = 22.sp)
            Text("HABILIDADES", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(pokemon.abilities, style = MaterialTheme.typography.bodyMedium)
            Text("ESTADÍSTICAS BASE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            listOf("PS", "Ataque", "Defensa", "At. especial", "Def. especial", "Velocidad").zip(pokemon.stats).forEach { (label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.width(83.dp), fontSize = 12.sp)
                    LinearProgressIndicator(progress = { value / 255f }, modifier = Modifier.weight(1f), color = DexRed)
                    Text("$value", Modifier.width(30.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = speak, enabled = soundEnabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("Escuchar ficha")
            }
            if (!voice.ready) Notice(voice.status)
        } else Notice("Aún no lo has visto.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { open(pokemon.id - 1) }, enabled = pokemon.id > 1) { Text("◀ Anterior") }
            TextButton(onClick = { open(pokemon.id + 1) }, enabled = pokemon.id < 151) { Text("Siguiente ▶") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScanScreen(model: PokedexModel) = Page {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
        snapshotFlow { model.working }.first { !it }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.startLive()
    }
    DisposableEffect(Unit) { onDispose { model.stopLive() } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null && model.loggedIn) model.loadPhoto(uri) }
    if (model.live) {
        LiveCamera(qr = false, ready = !model.working, onFrame = model::liveFrame, onClose = model::stopLive)
        Text(if (model.working) "Analizando…" else "Buscando Pokémon…", fontFamily = FontFamily.Monospace)
    } else {
        Box(Modifier.fillMaxWidth().height(200.dp).background(Color(0xFFC6D6B6), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CenterFocusStrong, null, Modifier.size(100.dp), tint = DexInk)
        }
        Text("¿Quién es ese Pokémon?", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Button(onClick = model::startLive, enabled = !model.working, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Abrir cámara") }
        OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !model.working, modifier = Modifier.fillMaxWidth()) { Text("Elegir imagen") }
        val preview = remember(model.photo) { model.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        if (preview != null) {
            Image(preview, "Imagen para identificar", Modifier.fillMaxWidth().height(160.dp))
            Button(onClick = model::identify, enabled = !model.working) { Text("Identificar") }
        }
    }
    if (model.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    Notice(model.scanStatus)
}

@Composable
private fun FriendsScreen(model: PokedexModel, open: (Int) -> Unit) = Page {
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { model.refreshFriends() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Entrenadores", Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Black)
        IconButton(onClick = { model.refreshFriends() }, enabled = !model.socialBusy) { Icon(Icons.Outlined.Refresh, "Actualizar amigos") }
    }
    OutlinedTextField(name, { name = it }, label = { Text("Usuario de tu amigo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(onClick = { model.refreshFriends(name.trim()) }, enabled = name.isNotBlank() && !model.socialBusy) { Text("Enviar solicitud") }
    Notice(model.socialError)
    if (!model.socialStatus.endsWith("desde el PC.")) Notice(model.socialStatus)
    if (model.incomingRequests.isNotEmpty()) {
        Text("Solicitudes · ${model.incomingRequests.size}", fontWeight = FontWeight.Bold)
        model.incomingRequests.forEach { sender ->
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFEAF0DF))) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(sender, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.respondToFriend(sender, true) }, enabled = !model.socialBusy) { Text("Aceptar") }
                        TextButton(onClick = { model.respondToFriend(sender, false) }, enabled = !model.socialBusy) { Text("Rechazar") }
                    }
                }
            }
        }
    }
    if (model.outgoingRequests.isNotEmpty()) {
        Text("Enviadas", fontWeight = FontWeight.Bold)
        model.outgoingRequests.forEach { Text("$it · pendiente", style = MaterialTheme.typography.bodySmall) }
    }
    if (model.friends.isEmpty()) Notice("Tu equipo empieza aquí.")
    model.friends.forEach { friend ->
        Text("${friend.username}  ·  ${friend.ids.size}/151", fontWeight = FontWeight.Bold)
        var page by remember(friend.username) { mutableIntStateOf(0) }
        val entries = Catalog.pokemon.filter { it.id in friend.ids }
        val pages = maxOf(1, (entries.size + 5) / 6)
        val current = page.coerceIn(0, pages - 1)
        if (entries.isEmpty()) Notice("Sin encuentros.")
        entries.drop(current * 6).take(6).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pokemon -> Box(Modifier.weight(1f)) { PokemonTile(pokemon, pokemon.id in model.seen) { open(pokemon.id) } } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page = current - 1 }, enabled = current > 0) { Text("◀") }
            Text("${current + 1}/$pages")
            TextButton(onClick = { page = current + 1 }, enabled = current + 1 < pages) { Text("▶") }
        }
    }
}

@Composable
private fun SettingsScreen(model: PokedexModel, voice: Voice, openDevice: () -> Unit) = Page {
    var connection by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf(false) }
    Text(model.username, fontSize = 24.sp, fontWeight = FontWeight.Black)
    OutlinedButton(onClick = { connection = !connection }, modifier = Modifier.fillMaxWidth()) { Text("Conexión al PC") }
    if (connection) PcConfiguration(model)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sonido y voz", Modifier.weight(1f))
        Switch(model.soundEnabled, { voice.stop(); model.toggleSound() })
    }
    Text("Volumen · ${model.shared.volume}%", style = MaterialTheme.typography.bodySmall)
    Slider(value = model.shared.volume.toFloat(), onValueChange = { model.volume(it.toInt()) }, valueRange = 0f..100f)
    OutlinedButton(onClick = { device = !device }, modifier = Modifier.fillMaxWidth()) { Text("Dispositivo") }
    if (device) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Simular Arduino", Modifier.weight(1f))
            Switch(model.device.simulated, { voice.stop(); model.mode(it) })
        }
        Notice(model.device.message)
        Button(onClick = openDevice, modifier = Modifier.fillMaxWidth()) { Text(if (model.device.simulated) "Ver Arduino simulado" else "Ver Arduino") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LED virtual", Modifier.weight(1f))
            Switch(model.shared.led, model::led)
        }
        model.device.confirmed?.pokemonId?.let { id ->
            if (id in model.seen) Catalog.find(id)?.let { pokemon ->
                Image(painterResource(pokemon.image), "Pantalla virtual: ${pokemon.name}", Modifier.size(120.dp).align(Alignment.CenterHorizontally))
            }
        }
        var test by remember { mutableStateOf(false) }
        TextButton(onClick = { test = !test }) { Text("Prueba manual") }
        if (test) {
            var number by remember { mutableStateOf("25") }
            OutlinedTextField(number, { number = it }, label = { Text("Número 1–151") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { number.toIntOrNull()?.let { model.select(it); model.recordSimulation() } },
                enabled = model.device.simulated && !model.working && number.toIntOrNull() in 1..151) { Text("Registrar prueba") }
            Notice(model.scanStatus)
        }
    }
    TextButton(onClick = { voice.stop(); model.logout() }) { Text("Cerrar sesión") }
    Notice("POKÉDEX IoT · 0.11")
}

@Composable
private fun PcConfiguration(model: PokedexModel) {
    var address by remember(model.pc) { mutableStateOf(model.pc.address) }
    var pin by remember(model.pc) { mutableStateOf(model.pc.fingerprint) }
    var token by remember(model.pc) { mutableStateOf(model.pc.token) }
    var manual by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    if (scanning) LiveCamera(qr = true, onCode = { if (scanning && model.importQr(it)) scanning = false }, onClose = { scanning = false })
    else Button(onClick = { scanning = true }, enabled = !model.working && !model.busy, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Leer QR del PC")
    }
    Notice("Abre el QR en tu PC. Usa la misma red Wi-Fi.")
    if (model.pc.token.isNotEmpty()) {
        Notice(model.pc.address)
        OutlinedButton(onClick = model::checkPc, enabled = !model.working && !model.busy) { Text("Comprobar conexión") }
        TextButton(onClick = model::forgetPc, enabled = !model.working && !model.busy) { Text("Desvincular") }
    }
    TextButton(onClick = { manual = !manual }, enabled = !model.busy) { Text("Configuración manual") }
    if (manual) {
        OutlinedTextField(address, { address = it }, label = { Text("Dirección HTTPS del PC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it }, label = { Text("Huella SHA-256 del PC") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Clave de conexión") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (model.configure(address, pin, token)) model.checkPc() }, enabled = !model.working && !model.busy) { Text("Guardar y comprobar") }
    }
    Notice(model.pcStatus)
}
