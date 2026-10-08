package net.craftoriya.redstoneIndustry.command

import net.craftoriya.adaptersLib.event.domainevents.DomainCommandEvent
import net.craftoriya.redstoneIndustry.menu.ResearchMenu
import net.craftoriya.redstoneIndustry.tech.TechStates
import net.craftoriya.redstoneIndustry.tech.TechTree

class ResearchCommand(
    private val tree: TechTree,
    private val states: TechStates,
    private val menu: ResearchMenu
) {
    fun handle(event: DomainCommandEvent) {
        val sender = event.sender
        val reply = event.response
        val id = sender.playerId
        if (!sender.isPlayer || id == null) {
            reply.sendError(sender, "Only players can use this.")
            return
        }

        when (event.args.firstOrNull()) {
            "unlock" -> {
                val tech = event.args.getOrNull(1)
                if (tech == null || tech !in tree.byId) reply.sendError(sender, "Usage: /researchmenu unlock <id>")
                else {
                    states.of(id).unlock(tech)
                    reply.send(sender, "Unlocked $tech")
                }
            }
            else -> if (tree.byId.isEmpty()) reply.sendError(sender, "No techs loaded") else menu.openRoot(id)
        }
    }
}