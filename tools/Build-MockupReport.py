"""Render the standalone mockup report from the local design compositions."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
folder = root / "docs/mockups"
output = folder / "Informe_Mockups_Pokedex_IoT.pdf"
pdfmetrics.registerFont(TTFont("UIReport", "C:/Windows/Fonts/arial.ttf"))
pdfmetrics.registerFont(TTFont("UIReportBold", "C:/Windows/Fonts/arialbd.ttf"))
pdfmetrics.registerFontFamily("UIReport", normal="UIReport", bold="UIReportBold")
c = canvas.Canvas(str(output), pagesize=A4)
c.setTitle("Pokédex IoT")
c.setAuthor("Proyecto Pokédex IoT")
W,H = A4

def text(value, x, top, width, size=10, bold=False, leading=None):
    style = ParagraphStyle("text", fontName="UIReportBold" if bold else "UIReport", fontSize=size,
                           leading=leading or size*1.45, textColor=colors.black)
    paragraph = Paragraph(value, style)
    _, height = paragraph.wrap(width, 800)
    paragraph.drawOn(c, x, top-height)
    return top-height

def footer(page):
    c.setFillColor(colors.HexColor("#555555")); c.setFont("UIReport", 8)
    c.drawString(42,25,"Pokédex IoT · Mockups de interfaz · Unidad 2")
    c.drawRightString(W-42,25,str(page))

def arrow(x1,y1,x2,y2):
    c.setStrokeColor(colors.HexColor("#53645A")); c.setLineWidth(1.1); c.line(x1,y1,x2,y2)
    import math
    a=math.atan2(y2-y1,x2-x1); d=5
    p=c.beginPath(); p.moveTo(x2,y2); p.lineTo(x2-d*math.cos(a-.5),y2-d*math.sin(a-.5)); p.moveTo(x2,y2);p.lineTo(x2-d*math.cos(a+.5),y2-d*math.sin(a+.5)); c.drawPath(p)

def node(label,x,y,w=146,h=42):
    c.setFillColor(colors.HexColor("#EAF0DF")); c.setStrokeColor(colors.HexColor("#879A7E"));c.roundRect(x,y,w,h,8,stroke=1,fill=1)
    text(label,x+9,y+h-11,w-18,10,True)

text("Pokédex IoT",42,H-45,510,26,True)
y=text("Criterios de diseño",42,H-111,510,15,True)
for line in [
    "Carcasa roja, lente azul y pantalla verde para mantener la identidad gráfica de una Pokédex.",
    "Navegación inferior mediante tres iconos: colección, escáner y amigos. El engranaje concentra los ajustes.",
    "Poké Ball rellena para encuentros registrados; silueta negra y datos ocultos para especies aún no vistas.",
    "Controles de monitoreo y activación reunidos en un panel de pantalla, LEDs, botones, sonido y volumen.",
]: y=text("• "+line,48,y-10,495,10)
y=text("Mapa de navegación",42,y-28,510,15,True)
base=y-68
node("Acceso",42,base);node("Colección y lista",224,base);node("Ficha del Pokémon",406,base,147)
arrow(188,base+21,224,base+21);arrow(370,base+21,406,base+21)
node("Vinculación por QR",42,base-78);node("Escaneo",224,base-78);node("Amigos",406,base-78,147)
arrow(115,base,115,base-36);arrow(297,base,297,base-36);arrow(479,base-36,479,base)
node("Ajustes",224,base-156);node("Control del dispositivo",406,base-156,147)
arrow(370,base-57,406,base+10)
c.line(224,base+10,210,base+10);c.line(210,base+10,210,base-135)
arrow(210,base-135,224,base-135);arrow(370,base-135,406,base-135)
text("Ilustraciones: PokéAPI y PokeAPI/sprites. Pokémon pertenece a sus respectivos titulares. Proyecto educativo no oficial.",42,76,510,8)
footer(1);c.showPage()

pairs = [
    ("Acceso y vinculación", "El primer recorrido permite conectar el teléfono con el PC e ingresar a la colección personal.", [
        ("01-login.png", "01 Acceso de entrenador", "Usuario, contraseña y acceso a la vinculación. La acción principal permite ingresar; crear una cuenta aparece como alternativa.", "Acción: ingresar o abrir la configuración del PC."),
        ("02-connection.png", "02 Vinculación al PC", "La lectura del QR concentra la configuración de la conexión. La comprobación informa si el servidor está disponible.", "Acción: leer el QR, comprobar o configurar manualmente.")]),
    ("Colección y navegación rápida", "Dos formas de recorrer los mismos encuentros, conservando la búsqueda y los filtros.", [
        ("03-collection.png", "03 Colección en tarjetas", "El contador resume el progreso. Cada tarjeta muestra número e ilustración; las especies conocidas llevan una Poké Ball rellena.", "Acción: tocar una tarjeta para abrir su ficha."),
        ("04-list.png", "04 Lista compacta", "La lista reduce el espacio de cada elemento y permite revisar más especies. Los Pokémon no vistos mantienen el nombre oculto.", "Acción: buscar, filtrar o volver a las tarjetas.")]),
    ("Reconocimiento y ficha", "La cámara conduce al resultado reconocido y a su información dentro de la misma carcasa.", [
        ("05-scan.png", "05 Visor de reconocimiento", "La cámara ocupa el área principal. El estado del análisis y la acción para detenerlo permanecen visibles. La imagen ilustra el encuadre.", "Acción: apuntar al Pokémon o detener el reconocimiento."),
        ("06-detail.png", "06 Ficha del encuentro", "Presenta imagen, nombre, tipo, medidas y habilidades. Al desplazarse hacia abajo aparecen las estadísticas y Escuchar ficha.", "Acción: deslizar entre especies, escuchar o volver a la lista.")]),
    ("Descubrimientos y amigos", "La interfaz distingue los datos disponibles y las solicitudes que requieren una respuesta.", [
        ("07-unknown.png", "07 Especie no descubierta", "La ficha conserva el número y una silueta negra. El nombre, tipos, estadísticas y audio permanecen ocultos hasta registrar el encuentro.", "Acción: continuar navegando hacia otra especie."),
        ("08-friends.png", "08 Entrenadores y solicitudes", "El usuario puede enviar solicitudes, aceptarlas o rechazarlas. El LED amarillo señala una pendiente. La colección del amigo aparece debajo.", "Acción: responder una solicitud o consultar una colección.")]),
    ("Ajustes y control del dispositivo", "Las opciones de configuración y los controles IoT se abren cuando el usuario los necesita.", [
        ("09-settings.png", "09 Configuración", "Agrupa la conexión al PC, sonido, volumen y selección de dispositivo. Los controles secundarios quedan fuera de la colección principal.", "Acción: ajustar preferencias o abrir el panel Arduino."),
        ("10-arduino.png", "10 Pantalla y actuadores", "El panel inferior reúne la imagen confirmada, LEDs, botones y perilla. Permite representar cambios desde la app y desde el dispositivo.", "Acción: navegar, controlar LED, silenciar o cambiar volumen.")]),
]
for page, (title, intro, screens) in enumerate(pairs, start=2):
    text(title,42,H-43,511,19,True)
    text(intro,42,H-76,511,10)
    for x, (file,title,description,action) in zip([45,320],screens):
        width=230;height=width*844/390
        c.drawImage(ImageReader(str(folder/file)),x,220,width,height,mask='auto')
        yy=text(title,x,202,width,11,True)
        yy=text(description,x,yy-9,width,9.5)
        text(action,x,yy-9,width,9,True)
    footer(page);c.showPage()
c.save()

# Overview for the repository. It is a labelled design board, not a device test screenshot.
board=Image.new('RGB',(1720,1680),'#f4f3ef'); draw=ImageDraw.Draw(board)
font=ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf',34)
small=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',19)
draw.text((35,25),'Pokédex IoT · Mockups de interfaz',font=font,fill='#172d2c')
draw.text((35,75),'Diseño basado en la versión 0.11 · Datos de ejemplo',font=small,fill='#526459')
screens=[item for _,_,items in pairs for item in items]
for index,(filename,title,_,_) in enumerate(screens):
    x=35+(index%5)*337;y=125+(index//5)*770
    image=Image.open(folder/filename).convert('RGB');image.thumbnail((303,656))
    board.paste(image,(x,y));draw.text((x,y+670),title,font=small,fill='#172d2c')
board.save(folder/'overview.png')
print(output)
