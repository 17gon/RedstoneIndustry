package net.craftoriya.redstoneIndustry.tech

import java.util.BitSet

class TeamUnlockState(private val bits: BitSet) {
    fun has(recipeIdx: Int) = bits.get(recipeIdx)
    fun grant(recipeIdx: Int) = bits.set(recipeIdx)
}