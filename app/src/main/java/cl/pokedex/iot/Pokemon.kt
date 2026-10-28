package cl.pokedex.iot

/** Ficha local de la Pokédex. El reconocimiento se realiza en el PC. */
data class Pokemon(val id: Int, val name: String, val type: String, val description: String, val image: Int,
                   val category: String, val height: String, val weight: String,
                   val abilities: String, val stats: List<Int>)

/** La búsqueda tampoco revela el nombre de una especie todavía desconocida. */
fun matchesVisiblePokemon(pokemon: Pokemon, seen: Set<Int>, query: String): Boolean {
    val search = query.trim().removePrefix("#")
    return search.isEmpty() || pokemon.id.toString().startsWith(search) || search.toIntOrNull() == pokemon.id ||
        (pokemon.id in seen && pokemon.name.contains(search, ignoreCase = true))
}

