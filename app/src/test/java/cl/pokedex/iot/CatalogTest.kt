package cl.pokedex.iot

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    @Test fun containsEveryOriginalSpeciesWithOfflineData() {
        assertEquals((1..151).toList(), Catalog.pokemon.map { it.id })
        assertEquals(151, Catalog.pokemon.map { it.name }.distinct().size)
        assertEquals(151, Catalog.pokemon.map { it.image }.distinct().size)
        assertTrue(Catalog.pokemon.all { it.description.isNotBlank() && it.type.isNotBlank() && it.image != 0 })
        assertTrue(Catalog.pokemon.all { it.category.isNotBlank() && it.height.isNotBlank() && it.weight.isNotBlank() && it.abilities.isNotBlank() })
        assertTrue(Catalog.pokemon.all { it.stats.size == 6 && it.stats.all { stat -> stat in 1..255 } })
        assertEquals("Bulbasaur", Catalog.find(1)?.name)
        assertEquals("Mew", Catalog.find(151)?.name)
        assertNull(Catalog.find(0)); assertNull(Catalog.find(152))
    }
    @Test fun unseenNamesCannotBeFoundThroughSearch() {
        val pikachu = Catalog.find(25)!!
        assertFalse(matchesVisiblePokemon(pikachu, emptySet(), "Pika"))
        assertTrue(matchesVisiblePokemon(pikachu, setOf(25), "Pika"))
        assertTrue(matchesVisiblePokemon(pikachu, emptySet(), "#025"))
        assertTrue(matchesVisiblePokemon(pikachu, emptySet(), ""))
    }
}
