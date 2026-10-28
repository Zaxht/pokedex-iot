# Enlace Pokédex ↔ Arduino, versión 1

La app coordina el estado. El Arduino aplica salidas y comunica acciones de sus entradas. El simulador ejecuta este mismo intercambio. El reconocimiento y la base de datos siguen en el PC; el Arduino no recibe credenciales de usuarios ni ejecuta el modelo.

## Código preparado

- `DeviceSnapshot`: ficha actual, datos visibles, LEDs, sonido, volumen, vista, petición de voz y actividad de la app.
- `DeviceSynchronizer`: sesión del enlace, secuencia, confirmaciones, eventos y comprobación de disponibilidad.
- `SimulatedArduinoTransport`: dispositivo virtual que aplica snapshots y devuelve ACK.
- `FramedArduinoTransport`: JSON UTF-8 delimitado por salto de línea, con reensamblado de paquetes, límite de 4096 bytes y rechazo de UTF-8 inválido.
- `ArduinoChannel`: interfaz para el canal Wi-Fi/BLE/USB que se elija. `write` debe preservar el orden y fragmentar según el límite del transporte; `open` entrega fragmentos arbitrarios y avisa al perder la conexión; `close` es idempotente. La apertura y escritura deben hacer su trabajo de E/S fuera del hilo de la interfaz, sin bloquearlo. Al reiniciarse la placa, el adaptador debe cerrar/reabrir el enlace para obtener una nueva sesión y reiniciar sus contadores.

Al disponer del hardware, sustituir `UnavailableArduinoTransport()` en `PokedexModel` por `FramedArduinoTransport(canal)` con un `dispatch` que publique en el hilo principal de Android, por ejemplo mediante `Handler(Looper.getMainLooper()).post(action)`. El canal debe comprobar la identidad del dispositivo antes de entregar datos y solicitar los permisos de Android que correspondan. No abrir sockets sin autenticar ni reutilizar la clave de los usuarios del PC. El identificador `session` evita respuestas viejas: **no es una credencial de seguridad**.

## Mensajes

Cada objeto ocupa una línea terminada en LF; se admite CRLF. El límite de 4096 bytes es por mensaje, no por fragmento BLE. No se incluyen imágenes ni audio en base64. Un mensaje demasiado grande o UTF-8 inválido cierra el canal; un JSON, versión o evento no válido no ejecuta acciones.

1. El dispositivo anuncia sus capacidades al conectar:

```json
{"v":1,"type":"hello","capabilities":{"display":true,"audio":false,"leds":true,"buttons":true,"knob":true}}
```

Declarar solo componentes funcionales. `display` indica pantalla utilizable, no garantiza resolución o color; `audio` requiere poder reproducir la narración, no solamente emitir un pitido. Una capacidad ausente conserva la función equivalente en el celular. La app actual usa el parlante del celular; la reproducción física se implementará según el módulo elegido.

2. La app responde con un estado completo y una secuencia creciente:

```json
{"v":1,"type":"state","session":"identificador-del-enlace","seq":1,"state":{"pokemon":25,"revealed":true,"name":"Pikachu","description":"Pikachu. Tipo Eléctrico.","led":true,"sound":true,"volume":70,"notifications":1,"view":"entry","speech":0,"active":true}}
```

- `pokemon`: número 1–151 o `null`. `revealed=false` exige silueta/???; `name` y `description` van vacíos. Nunca revelar datos mediante la pantalla o la voz del Arduino.
- `led`: LED verde de actividad/manual. `notifications>0`: LED amarillo intermitente; cero: apagado.
- `sound`: habilitación del parlante. `volume`: entero 0–100; la perilla física debe normalizar su lectura a ese rango.
- `view`: `collection`, `entry`, `scan`, `friends` o `settings`; una pantalla limitada puede mostrar solo el estado y la especie seleccionada.
- `speech`: cero detiene/limpia la narración. Un valor positivo distinto del último ejecutado solicita una narración, únicamente con `revealed`, `sound` y `active` verdaderos. Recordar el último valor: reenviar un estado o un latido no vuelve a hablar. Cada reconexión empieza sin voz pendiente.
- `active=false`: detener audio y no aceptar acciones de botones. Al cerrar sesión, la app envía una ficha vacía y LEDs apagados. Si se pierde el enlace, aplicar también un límite local de inactividad para detener audio y marcar la pantalla sin conexión.

El firmware debe validar el mensaje, aplicar solo las funciones soportadas y confirmar después, no antes. Mantener el orden de las secuencias y no aplicar snapshots anteriores al último aceptado.

3. Confirmación del estado aplicado:

```json
{"v":1,"type":"ack","session":"identificador-del-enlace","seq":1}
```

Un ACK confirma la aplicación del snapshot a las capacidades anunciadas; no demuestra que un parlante esté produciendo sonido ni que un LED averiado ilumine. La app ignora ACK de otra sesión o de una secuencia anterior. Comprueba el enlace aproximadamente cada dos segundos en primer plano y deja de presentar salidas como confirmadas tras cinco segundos sin ACK. El firmware debe impedir que los latidos reproduzcan de nuevo audio o generen eventos de botones.

4. Una pulsación o lectura física produce un evento:

```json
{"v":1,"type":"input","session":"identificador-del-enlace","seq":1,"event":1,"action":"NEXT","value":0}
```

`seq` identifica el último estado aplicado; `event` es un contador creciente de acciones dentro de la conexión. La app ignora eventos repetidos, antiguos, sin estado confirmado o mientras esté inactiva. Tras procesar el evento, envía otro snapshot que el dispositivo aplica y confirma. Recibir un estado no debe generar otro evento de entrada: así se evita un bucle de eco.

| Acción | Valor | Efecto |
| --- | --- | --- |
| `NEXT` / `PREVIOUS` | 0 | Cambia la ficha dentro de 1–151, sin registrar encuentros. |
| `SCAN` | 0 | Abre el visor del celular en primer plano. |
| `FRIENDS` | 0 | Abre solicitudes; no acepta ninguna automáticamente. |
| `SPEAK` | 0 | Solicita narrar la especie visible si fue descubierta y el sonido está habilitado. |
| `LED` | 0 o 1 | Cambia el LED verde y su control en la app. |
| `SOUND` | 0 o 1 | Silencia o habilita ambas salidas. |
| `VOLUME` | 0–100 | Lectura normalizada de la perilla; actualiza el volumen de la app. |

Filtrar rebotes de botones en el firmware y enviar la perilla solo cuando cambie su valor normalizado, con una frecuencia limitada. La interfaz de la app utiliza el mismo formato.

## Pendiente al recibir los componentes

1. Confirmar placa, pantalla, módulo de audio, botones/perilla y alimentación. No se asignaron pines ni se copiaron contraseñas del sketch anterior.
2. Elegir el enlace y completar `ArduinoChannel`, sus permisos y su autenticación.
3. Implementar el receptor de estos mensajes en el firmware. Añadir controladores de pantalla, LEDs y audio; anunciar sus capacidades reales. No hay todavía un firmware físico compilado o probado.
4. Preparar imágenes locales para la resolución/memoria de la pantalla, referidas por número; una pantalla de texto mostrará nombre/número. Elegir reproducción pregrabada, síntesis u otro mecanismo según el módulo de voz.
5. Validar ida y vuelta, rebotes, eventos simultáneos, límites de volumen, pérdida de enlace, recuperación, privacidad de fichas ocultas y ausencia de repeticiones de voz. El contrato de mensajes permite hacer esto sin cambiar el modelo de datos ni las pantallas principales de la app.
