package cl.pokedex.iot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val DexInk = Color(0xFF172D2C)
val DexLcd = Color(0xFFDCE8CB)
val DexRed = Color(0xFFBB1238)

@Composable
fun DexShell(title: String, back: (() -> Unit)? = null, settings: (() -> Unit)? = null,
             notificationCount: Int = 0, notifications: (() -> Unit)? = null, activityLed: Boolean = false, deviceStatus: String? = null,
             footer: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE7234B), Color(0xFF970C2E))))
        .safeDrawingPadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).border(4.dp, Color(0xFFE7EDE5), CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFBBF5FF), Color(0xFF18B7ED), Color(0xFF075687))), CircleShape)) {
                Box(Modifier.padding(12.dp).size(10.dp).background(Color.White.copy(alpha = .9f), CircleShape))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).border(1.dp, DexInk, CircleShape).background(Color(0xFF741628), CircleShape))
                    NotificationLed(notificationCount, notifications)
                    Box(Modifier.size(9.dp).border(1.dp, DexInk, CircleShape)
                        .background(if (activityLed) Color(0xFF8FE596) else Color(0xFF304B36), CircleShape)
                        .semantics { contentDescription = if (activityLed) "LED verde encendido" else "LED verde apagado" })
                }
                Text("POKÉDEX", color = Color.White, letterSpacing = 3.sp, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Volver", tint = Color.White) }
            if (settings != null) IconButton(onClick = settings) { Icon(Icons.Outlined.Settings, "Ajustes", tint = Color.White) }
        }
        HorizontalDivider(color = Color(0xFF750C27), thickness = 3.dp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).fillMaxWidth().border(2.dp, Color(0xFF71132A), RoundedCornerShape(18.dp))
            .background(Color(0xFFD3D7CB), RoundedCornerShape(18.dp)).padding(9.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DexInk)
                Text(deviceStatus ?: "●  ●", color = DexRed, fontSize = 10.sp)
            }
            Box(Modifier.weight(1f).fillMaxWidth().border(3.dp, DexInk, RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp)).background(DexLcd).padding(3.dp)) { content() }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("●", color = DexRed, fontSize = 11.sp)
                Text("▰ ▰ ▰", color = DexInk, fontSize = 11.sp)
            }
        }
        footer()
        Spacer(Modifier.height(10.dp))
    }
}

/** LED virtual: refleja solicitudes del PC y no se apaga hasta responder a todas. */
@Composable
internal fun NotificationLed(count: Int, open: (() -> Unit)?) {
    val brightness = if (count > 0) {
        val transition = rememberInfiniteTransition(label = "notification-led")
        val pulse by transition.animateFloat(initialValue = .3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "notification-brightness")
        pulse
    } else 0f
    val action = if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier
    Canvas(Modifier.size(20.dp).then(action).semantics {
        contentDescription = if (count > 0) "LED de notificaciones: $count solicitudes pendientes" else "LED de notificaciones apagado"
    }) {
        val center = Offset(size.width / 2, size.height / 2)
        if (count > 0) drawCircle(Color(0xFFFFE367).copy(alpha = brightness * .35f), size.minDimension / 2, center)
        drawCircle(DexInk, size.minDimension * .32f, center)
        drawCircle(if (count > 0) Color(0xFFFFE367).copy(alpha = .4f + .6f * brightness) else Color(0xFF66532E), size.minDimension * .25f, center)
    }
}

@Composable
fun PokeBall(filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp).semantics { contentDescription = if (filled) "Pokémon visto" else "Sin registrar" }) {
        val diameter = size.minDimension * .88f
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(if (filled) Color(0xFFF3F1DD) else DexLcd, diameter / 2, center)
        drawArc(if (filled) DexRed else Color(0xFF94A38B), 180f, 180f, true, topLeft, Size(diameter, diameter))
        drawCircle(DexInk, diameter / 2, center, style = Stroke(diameter * .08f))
        drawLine(DexInk, Offset(topLeft.x, center.y), Offset(topLeft.x + diameter, center.y), diameter * .09f)
        drawCircle(DexInk, diameter * .17f, center)
        drawCircle(Color(0xFFF3F1DD), diameter * .095f, center)
    }
}
