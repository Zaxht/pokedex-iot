"""Package only public project files and recorded evidence; never private PC configuration."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = root / "entrega"
    evidence = Path(args.evidence).resolve()
    summary = json.loads((evidence / "summary.json").read_text(encoding="utf-8"))
    if summary["totals"]["failures"] or summary["totals"]["errors"]:
        raise RuntimeError("Cannot package failed validation")
    apk = root / summary["apk"]["path"]
    if hashlib.sha256(apk.read_bytes()).hexdigest() != summary["apk"]["sha256"]:
        raise RuntimeError("APK has changed since validation")
    output.mkdir(exist_ok=True)
    shutil.copy2(apk, output / "Pokedex-IoT-0.11.0.apk")
    copied_evidence = output / "evidencia"
    copied_evidence.mkdir(exist_ok=True)
    for name in ("summary.json", "cases.csv", "sha256.txt", "lint-results-debug.xml", "gradle.log"):
        shutil.copy2(evidence / name, copied_evidence / name)
    for path in (evidence / "junit").rglob("*.xml"):
        destination = copied_evidence / path.relative_to(evidence)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)

    source = [root / name for name in ("README.md", ".gitignore", "build.gradle.kts", "settings.gradle.kts",
              "gradle.properties", "gradlew", "gradlew.bat", "Start-Backend.ps1", "app/build.gradle.kts", "server/build.gradle.kts")]
    for folder in ("app/src", "server/src", "gradle/wrapper", "hardware", "data", "tools"):
        source += [p for p in (root / folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts]
    forbidden_names = {"local.properties", "connection.properties", "pokedex.db", "server.p12"}
    def safe(path):
        rel = path.relative_to(root)
        if path.name in forbidden_names or path.suffix.lower() in (".db", ".p12", ".jks", ".keystore", ".pyc"):
            raise RuntimeError(f"Private or unexpected file refused: {rel}")
        if any(part in (".local", ".gradle", "build", ".idea", ".kotlin") for part in rel.parts):
            raise RuntimeError(f"Build/cache path refused: {rel}")
    code_zip = output / "Codigo_Pokedex_UA2.zip"
    with ZipFile(code_zip, "w", ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(set(source)):
            safe(path)
            archive.write(path, "PokedexIoT/" + path.relative_to(root).as_posix())
    with ZipFile(code_zip) as archive:
        assert archive.testzip() is None

    final_names = ("Pokedex-IoT-0.11.0.apk", "Codigo_Pokedex_UA2.zip", "Informe_Pokedex_UA2.pdf", "Informe_Pokedex_UA2.html", "LEEME.txt")
    manifest = {}
    for name in final_names:
        path = output / name
        manifest[name] = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    (output / "MANIFEST.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    complete = root / "Entrega_Pokedex_UA2.zip"
    items = [output / name for name in (*final_names, "MANIFEST.json")]
    items += [p for p in copied_evidence.rglob("*") if p.is_file()]
    with ZipFile(complete, "w", ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(items): archive.write(path, "Entrega_Pokedex_UA2/" + path.relative_to(output).as_posix())
    with ZipFile(complete) as archive: assert archive.testzip() is None
    print(json.dumps({"archive": str(complete), "bytes": complete.stat().st_size, "sourceFiles": len(set(source)), "tests": summary["totals"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
