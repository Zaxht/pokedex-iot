package cl.pokedex.iot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** La pantalla muestra solo lo confirmado por el transporte, nunca una orden todavía pendiente. */
@Composable
fun ArduinoSimulator(model: PokedexModel, close: () -> Unit) {
    val connection = model.device
    val state = connection.confirmed
    val enabled = connection.simulated && connection.connected && !connection.pending
    Column(Modifier.fillMaxWidth().heightIn(max = 550.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (connection.simulated) "Arduino simulado" else "Arduino", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Cerrar Arduino simulado") }
        }
        Text(connection.message, style = MaterialTheme.typography.bodySmall)
        if (state != null) {
            Row(Modifier.fillMaxWidth().border(2.dp, DexInk, RoundedCornerShape(12.dp))
                .background(Color(0xFFC8DABA), RoundedCornerShape(12.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pokemon = state.pokemonId?.let(Catalog::find)
                val known = state.revealed && state.pokemonId in model.seen
                if (pokemon != null) Image(painterResource(pokemon.image), if (known) "Pantalla Arduino: ${pokemon.name}" else "Pantalla Arduino: silueta",
                    Modifier.size(96.dp), colorFilter = if (known) null else ColorFilter.tint(Color.Black))
                else Icon(Icons.Outlined.DeveloperBoard, null, Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(pokemon?.let { "#${it.id.toString().padStart(3, '0')}" } ?: "—", fontFamily = FontFamily.Monospace)
                    Text(if (known) state.name else if (pokemon != null) "???" else "Lista para explorar", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(12.dp).background(if (state.led) Color(0xFF44B85C) else DexInk, CircleShape)
                            .semantics { contentDescription = if (state.led) "LED Arduino encendido" else "LED Arduino apagado" })
                        NotificationLed(state.notifications) { model.simulateInput(DeviceAction.FRIENDS) }
                        Icon(if (state.sound) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                            if (state.sound) "Parlante habilitado" else "Parlante silenciado", Modifier.size(20.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedIconButton(onClick = { model.simulateInput(DeviceAction.PREVIOUS) }, enabled = enabled) { Icon(Icons.Outlined.ChevronLeft, "Arduino: anterior") }
            OutlinedIconButton(onClick = { model.simulateInput(DeviceAction.SCAN) }, enabled = enabled && !model.working) { Icon(Icons.Outlined.CenterFocusStrong, "Arduino: escanear") }
            OutlinedIconButton(onClick = { model.simulateInput(DeviceAction.NEXT) }, enabled = enabled) { Icon(Icons.Outlined.ChevronRight, "Arduino: siguiente") }
            OutlinedIconButton(onClick = { model.simulateInput(DeviceAction.SPEAK) }, enabled = enabled && state?.revealed == true && state.sound) { Icon(Icons.Outlined.VolumeUp, "Arduino: hablar") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.simulateInput(DeviceAction.LED, if (state?.led == true) 0 else 1) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("LED") }
            OutlinedButton(onClick = { model.simulateInput(DeviceAction.SOUND, if (state?.sound == true) 0 else 1) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Silencio") }
            OutlinedIconButton(onClick = { model.simulateInput(DeviceAction.FRIENDS) }, enabled = enabled) { Icon(Icons.Outlined.PeopleOutline, "Arduino: solicitudes") }
        }
        Text("Perilla · ${state?.volume ?: 0}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = (state?.volume ?: 0).toFloat(), onValueChange = { model.simulateInput(DeviceAction.VOLUME, it.toInt()) },
            valueRange = 0f..100f, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Perilla del Arduino" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = model::disconnectSimulator, enabled = enabled) { Text("Desconectar") }
            TextButton(onClick = model::reconnectDevice) { Text("Reconectar") }
        }
        Text("Botones y perilla → app. Imagen, LEDs y sonido ← app.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
    }
}
