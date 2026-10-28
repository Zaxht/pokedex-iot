package cl.pokedex.iot

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.os.Bundle
import kotlinx.coroutines.*

class Voice(context: Context) {
    var ready by mutableStateOf(false); private set
    var status by mutableStateOf("Preparando voz…"); private set
    private var engine: TextToSpeech? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var announcement: Job? = null
    private var pending: Pokemon? = null
    private var volumeLevel = .7f
    fun volume(percent: Int) {
        volumeLevel = percent.coerceIn(0, 100) / 100f
        chime?.setVolume(.5f * volumeLevel, .5f * volumeLevel)
    }
    private val chime = runCatching { MediaPlayer.create(context.applicationContext, R.raw.pokedex_scan)?.apply { setVolume(.5f, .5f) } }.getOrNull()
    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val language = engine?.setLanguage(Locale("es", "ES"))
                ready = language != null && language >= TextToSpeech.LANG_AVAILABLE
                // Voz local cuando esté instalada; tono grave sin perder claridad.
                engine?.voices?.filter { it.locale.language == "es" && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality + if (it.locale.country == "ES") 1000 else 0 }
                    ?.let { engine?.voice = it }
                engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine?.setPitch(.82f)
                engine?.setSpeechRate(.94f)
                status = if (ready) "Voz en español disponible" else "Instala una voz en español en los ajustes del celular."
                if (ready) pending?.let { pending = null; speak(it) }
            } else status = "Este dispositivo no tiene un motor de voz disponible."
        }
    }
    fun speak(pokemon: Pokemon) {
        if (!ready) return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeLevel) }
        val result = engine?.speak("${pokemon.name}. Tipo ${pokemon.type}. ${pokemon.description}", TextToSpeech.QUEUE_FLUSH, params, "pokemon-${pokemon.id}")
        status = if (result == TextToSpeech.SUCCESS) "Descripción enviada al parlante del celular." else "No se pudo reproducir la descripción."
    }
    /** Arranque robótico original seguido de voz, sin grabaciones de la serie. */
    fun announce(pokemon: Pokemon) {
        stop()
        announcement = scope.launch {
            val duration = runCatching { chime?.let { it.start(); it.duration.toLong() } ?: 0L }.getOrDefault(0L)
            delay(duration + 60)
            if (ready) speak(pokemon) else pending = pokemon
        }
    }
    fun stop() {
        announcement?.cancel(); pending = null
        chime?.let { if (it.isPlaying) it.pause(); it.seekTo(0) }
        engine?.stop()
    }
    fun close() { stop(); scope.cancel(); chime?.release(); engine?.shutdown(); engine = null }
}
