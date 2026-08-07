package net.craftoriya.redstoneIndustry

import net.craftoriya.adaptersLib.containers.RecipeContainer
import net.craftoriya.adaptersLib.containers.RecipesConfig
import net.craftoriya.adaptersLib.tools.RecipeExpander

class TradeRegistry(recipes: RecipesConfig) {
    private val masterTrades: MutableMap<RecipeContainer.TradeProfession, MutableMap<Int, MutableList<RecipeContainer.Trades>>> =
        RecipeExpander.expandTrades(recipes)

    fun get(profession: RecipeContainer.TradeProfession, level: Int): List<RecipeContainer.Trades> =
        masterTrades[profession]?.get(level) ?: emptyList()

    fun put(profession: RecipeContainer.TradeProfession, level: Int, trade: RecipeContainer.Trades) {
        masterTrades.getOrPut(profession) { mutableMapOf() }
            .getOrPut(level) { mutableListOf() }
            .add(trade)
    }

    fun putAll(profession: RecipeContainer.TradeProfession, level: Int, trades: List<RecipeContainer.Trades>) {
        masterTrades.getOrPut(profession) { mutableMapOf() }
            .getOrPut(level) { mutableListOf() }
            .addAll(trades)
    }
}