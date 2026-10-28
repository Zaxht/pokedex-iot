package cl.pokedex.iot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Solo en un celular dedicado y con servidor de pruebas. No usa cámara ni Qwen. */
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun unknownSpeciesStayHiddenAndSeenSpeciesOpenDetails() {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("pcAddress")
        val pin = args.getString("pcPin")
        val token = args.getString("pcToken")
        assumeTrue(address != null && pin != null && token != null)
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Configuración manual").performScrollTo().performClick()
        compose.onNodeWithText("Dirección HTTPS del PC").performScrollTo().performTextReplacement(address!!)
        compose.onNodeWithText("Huella SHA-256 del PC").performScrollTo().performTextReplacement(pin!!)
        compose.onNodeWithText("Clave de conexión").performScrollTo().performTextReplacement(token!!)
        compose.onNodeWithText("Guardar y comprobar").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("PC conectado", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Crear una cuenta").performScrollTo().performClick()
        compose.onNodeWithText("Usuario").performScrollTo().performTextReplacement("test_" + System.currentTimeMillis())
        compose.onNodeWithText("Contraseña").performScrollTo().performTextInput("Prueba-123")
        compose.onNodeWithText("Crear cuenta", substring = false).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("0 / 151").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Bulbasaur").assertDoesNotExist()
        compose.onNodeWithText("#001").performScrollTo().performClick()
        compose.onNodeWithText("Aún no lo has visto.").performScrollTo().assertExists()
        compose.onNodeWithText("Bulbasaur").assertDoesNotExist()
        compose.onNodeWithText("HABILIDADES").assertDoesNotExist()
        compose.onNodeWithTag("species-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("N.º 002").assertIsDisplayed()
        compose.onNodeWithText("Ivysaur").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ver lista").performClick()
        compose.onNodeWithContentDescription("Ver tarjetas").assertExists()
        compose.onNodeWithText("Bulbasaur").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Dispositivo").performScrollTo().performClick()
        compose.onNodeWithText("Prueba manual").performScrollTo().performClick()
        compose.onNodeWithText("Registrar prueba").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("N.º 025").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Ajustes").performClick()
        compose.onNodeWithText("Dispositivo").performScrollTo().performClick()
        compose.onNodeWithText("Ver Arduino simulado").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Pantalla Arduino: Pikachu").assertExists()
        compose.onNodeWithContentDescription("Arduino: siguiente").performClick()
        compose.onNodeWithContentDescription("Pantalla Arduino: silueta").assertExists()
        compose.onNodeWithContentDescription("Arduino: anterior").performClick()
        compose.onNodeWithContentDescription("Pantalla Arduino: Pikachu").assertExists()
        compose.onNodeWithContentDescription("Cerrar Arduino simulado").performClick()
        compose.onNodeWithText("N.º 025").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pokédex").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("1 / 151").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Buscar…").performTextInput("25")
        compose.onNodeWithText("Pikachu").performScrollTo().performClick()
        compose.onNodeWithText("HABILIDADES").performScrollTo().assertExists()
        compose.onNodeWithText("ESTADÍSTICAS BASE").performScrollTo().assertExists()
    }
}
