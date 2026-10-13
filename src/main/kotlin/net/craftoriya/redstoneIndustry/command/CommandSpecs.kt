package net.craftoriya.redstoneIndustry.command

import net.craftoriya.adaptersLib.model.CommandSpec
import net.craftoriya.adaptersLib.model.commandSpec

object CommandSpecs {
    fun debug(): CommandSpec = commandSpec {
        literal("work") { xyz() }
        literal("villagers")
        literal("trace")
        literal("demolish") { xyz() }
        literal("region") {
            xyz {
                literal("demolish") { xyz { xyz() } }
                literal("safe") { xyz { xyz() } }
                literal("clear") {
                    literal("demolish")
                    literal("safe")
                }
            }
        }
    }

    fun research(techIds: () -> Collection<String>): CommandSpec = commandSpec {
        literal("unlock") { value("id", techIds) }
    }
}