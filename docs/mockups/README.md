# Mockups de Pokédex IoT

[Informe solo de mockups en PDF](Informe_Mockups_Pokedex_IoT.pdf)

![Diez vistas de la interfaz Pokédex](overview.png)

Diez composiciones visuales basadas en el diseño de la app v0.11. Documentan acceso, vinculación, colección, lista, cámara, ficha, especies desconocidas, amigos, ajustes y control del dispositivo. Usan datos de ejemplo y no representan una ejecución en Android.

Abre `index.html` en un navegador para recorrerlas. La navegación, el volumen y el aviso de solicitudes tienen interacción local de demostración; no hay conexión al backend, reconocimiento ni captura de cámara.

Las imágenes del catálogo proceden de los recursos incluidos en la app. Los colores, la carcasa y la navegación siguen `DexChrome.kt` y `DexApp.kt`. Los mockups documentan el diseño actual; no se presentan como bosquejos producidos antes de programar.

## Archivos

- `01-login.png` y `02-connection.png`: acceso y vinculación.
- `03-collection.png` y `04-list.png`: colección en tarjetas y lista.
- `05-scan.png` y `06-detail.png`: cámara y ficha.
- `07-unknown.png` y `08-friends.png`: especie oculta y amigos.
- `09-settings.png` y `10-arduino.png`: ajustes y panel de control.
- `index.html`, `mockups.css`, `mockups.js` y `data.js`: fuente editable.

## Regenerar

`tools/Build-MockupAssets.py` prepara recursos desde el catálogo local. `tools/Render-Mockups.mjs` usa Playwright y un navegador para exportar las diez vistas; recibe la ruta del navegador y de `node_modules`. `tools/Build-MockupReport.py` genera el PDF con ReportLab y la vista general con Pillow; usa las fuentes Arial de Windows. Ninguno de estos pasos ejecuta la APK ni un emulador.
