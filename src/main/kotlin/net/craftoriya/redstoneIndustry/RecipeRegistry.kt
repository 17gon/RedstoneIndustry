package net.craftoriya.redstoneIndustry

import net.craftoriya.adaptersLib.containers.CraftingGridContainer
import net.craftoriya.adaptersLib.containers.InventoryTypeDomain
import net.craftoriya.adaptersLib.containers.ItemContainer
import net.craftoriya.adaptersLib.containers.RecipeContainer

class RecipeRegistry {
    private val recipes = mutableListOf<RecipeContainer>()

    fun register(recipe: RecipeContainer) = recipes.add(recipe)

    fun findMatch(grid: CraftingGridContainer): RecipeContainer? {
        val craftSlots = grid.items.drop(1)
        val normalized = when (grid.type) {
            InventoryTypeDomain.CRAFTING -> gridMatrixUpscaler(craftSlots)
            InventoryTypeDomain.WORKBENCH -> craftSlots
            else -> return null
        }
        return recipes.firstOrNull { match(normalized, it) }
    }

    fun allKeys(): List<String> = recipes.indices.map { "recipe_$it" }



    private fun gridMatrixUpscaler(grid2: List<ItemContainer?>): List<ItemContainer?> {
        val grid3 = MutableList<ItemContainer?>(9) { null }
        grid3[0] = grid2[0]; grid3[1] = grid2[1]
        grid3[3] = grid2[2]; grid3[4] = grid2[3]
        return grid3
    }

    fun claimsOutput(result: ItemContainer?): Boolean =
        recipes.any { it.output.material == result?.material }

    private fun match(items: List<ItemContainer?>, recipe: RecipeContainer) = when (recipe) {
        is RecipeContainer.Shaped -> matchShaped(items, recipe.pattern)
        is RecipeContainer.Shapeless -> matchShapeless(items, recipe.ingredients)
    }

    private fun matchShaped(grid: List<ItemContainer?>, pattern: List<ItemContainer?>): Boolean {
        if (grid.size != pattern.size) return false
        return grid.zip(pattern).all { (item, expected) ->
            expected == null && item == null || expected?.material == item?.material
        }
    }

    private fun matchShapeless(grid: List<ItemContainer?>, ingredients: List<ItemContainer>): Boolean {
        val actual = grid.filterNotNull().map { it.material }.sorted()
        val expected = ingredients.map { it.material }.sorted()
        return actual == expected
    }
}