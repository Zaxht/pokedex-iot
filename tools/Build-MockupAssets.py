"""Prepare local catalogue and vector assets for the documented UI mockups."""
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
destination = root / "docs/mockups"
assets = destination / "assets"
assets.mkdir(parents=True, exist_ok=True)
ids = {1, 2, 3, 4, 5, 6, 7, 8, 9, 25, 26}
catalogue = json.loads((root / "data/kanto.json").read_text(encoding="utf-8"))
entries = [entry for entry in catalogue["pokemon"] if entry["id"] in ids]
for entry in entries:
    shutil.copy2(root / f"app/src/main/res/drawable-nodpi/pokemon_{entry['id']}.png", assets)
(destination / "data.js").write_text("window.mockupCatalog = " + json.dumps(entries, ensure_ascii=False) + ";\n", encoding="utf-8")
ns = "{http://schemas.android.com/apk/res/android}"
for name in ("pokedex", "scan", "friends"):
    xml = ET.parse(root / f"app/src/main/res/drawable/ic_nav_{name}.xml").getroot()
    paths = []
    for path in xml.findall("path"):
        fill = "none" if path.get(ns + "fillColor") == "#00000000" else "currentColor"
        stroke = "currentColor" if path.get(ns + "strokeColor") else "none"
        paths.append(f'<path d="{path.get(ns + "pathData")}" fill="{fill}" stroke="{stroke}" stroke-width="{path.get(ns + "strokeWidth", "0")}" stroke-linecap="round" stroke-linejoin="round"/>')
    (assets / f"{name}.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' + "".join(paths) + '</svg>', encoding="utf-8")
print("Mockup assets ready")
