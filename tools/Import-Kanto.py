"""Importa datos factuales de PokéAPI y sus 151 ilustraciones; no consulta Ollama."""
import concurrent.futures
import csv
import io
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.local' / 'kanto-import'
CACHE.mkdir(parents=True, exist_ok=True)

def get(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'PokedexIoT-educational-import'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()

def revision(repo):
    return json.loads(get(f'https://api.github.com/repos/PokeAPI/{repo}/commits/master'))['sha']

manifest = CACHE / 'revisions.json'
if not manifest.exists():
    manifest.write_text(json.dumps({name: revision(name) for name in ['pokeapi', 'sprites']}))
revisions = json.loads(manifest.read_text())

def rows(name):
    path = CACHE / (name + '.csv')
    if not path.exists():
        path.write_bytes(get(f'https://raw.githubusercontent.com/PokeAPI/pokeapi/{revisions["pokeapi"]}/data/v2/csv/{name}.csv'))
    return list(csv.DictReader(io.StringIO(path.read_text(encoding='utf-8'))))

data = {name: rows(name) for name in ['pokemon', 'pokemon_species_names', 'pokemon_types', 'type_names', 'pokemon_stats', 'pokemon_abilities', 'ability_names']}
species = {int(row['id']): row for row in data['pokemon'] if 1 <= int(row['id']) <= 151}
names = {int(row['pokemon_species_id']): row for row in data['pokemon_species_names'] if row['local_language_id'] == '7'}
types = {row['type_id']: row['name'] for row in data['type_names'] if row['local_language_id'] == '7'}
slots = {}
stats = {}
abilities = {}
ability_names = {r['ability_id']: r['name'] for r in data['ability_names'] if r['local_language_id'] == '7'}
for row in data['pokemon_stats']:
    stats.setdefault(int(row['pokemon_id']), []).append((int(row['stat_id']), int(row['base_stat'])))
for row in data['pokemon_abilities']:
    if int(row['pokemon_id']) not in range(1, 152):
        continue
    abilities.setdefault(int(row['pokemon_id']), []).append((int(row['slot']), ability_names[row['ability_id']] + (' (oculta)' if row['is_hidden'] == '1' else '')))
for row in data['pokemon_types']:
    slots.setdefault(int(row['pokemon_id']), []).append((int(row['slot']), types[row['type_id']]))

entries = []
for id in range(1, 152):
    row, local = species[id], names[id]
    height = f'{int(row["height"]) / 10:g}'.replace('.', ',')
    weight = f'{int(row["weight"]) / 10:g}'.replace('.', ',')
    entries.append(dict(id=id, slug=row['identifier'], name=local['name'],
        category=local['genus'], height=height + ' m', weight=weight + ' kg',
        abilities=' · '.join(a for _, a in sorted(abilities[id])), stats=[value for _, value in sorted(stats[id])],
        type=' · '.join(t for _, t in sorted(slots[id])),
        description=f'Se clasifica como {local["genus"]}. Mide {height} {"metro" if height == "1" else "metros"} y pesa {weight} {"kilogramo" if weight == "1" else "kilogramos"}.'))

def download(entry):
    path = ROOT / f'app/src/main/res/drawable-nodpi/pokemon_{entry["id"]}.png'
    if not path.exists():
        blob = get(f'https://raw.githubusercontent.com/PokeAPI/sprites/{revisions["sprites"]}/sprites/pokemon/other/official-artwork/{entry["id"]}.png')
        if not blob.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError(f'Imagen inválida: {entry["id"]}')
        path.write_bytes(blob)
    return entry['id']

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    for count, _ in enumerate(pool.map(download, entries), 1):
        if count % 25 == 0: print(f'Imágenes: {count}/151', flush=True)

def literal(value):
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')

lines = ['package cl.pokedex.iot', '', '// Generado por tools/Import-Kanto.py. Datos e imágenes incluidos en la APK.', 'object Catalog {', '    val pokemon = listOf(']
lines += [f'        Pokemon({e["id"]}, {literal(e["name"])}, {literal(e["type"])}, {literal(e["description"])}, R.drawable.pokemon_{e["id"]}, {literal(e["category"])}, {literal(e["height"])}, {literal(e["weight"])}, {literal(e["abilities"])}, listOf({", ".join(map(str, e["stats"]))})),' for e in entries]
lines += ['    )', '    fun find(id: Int) = pokemon.getOrNull(id - 1)', '}', '']
(ROOT / 'app/src/main/java/cl/pokedex/iot/Catalog.kt').write_text('\n'.join(lines), encoding='utf-8')

lines = ['package cl.pokedex.server', '', '// Generado por tools/Import-Kanto.py.', 'val kantoNames = mapOf(']
lines += [f'    {literal(e["slug"])} to {e["id"]},' for e in entries]
lines += [')', '']
(ROOT / 'server/src/main/kotlin/cl/pokedex/server/KantoNames.kt').write_text('\n'.join(lines), encoding='utf-8')
(ROOT / 'data').mkdir(exist_ok=True)
(ROOT / 'data/kanto.json').write_text(json.dumps(dict(sources=revisions, pokemon=entries), ensure_ascii=False, indent=2), encoding='utf-8')
print('Catálogo completo: 151 fichas e imágenes.', flush=True)
