package net.craftoriya.redstoneIndustry.command

import net.craftoriya.adaptersLib.event.domainevents.DomainCommandEvent
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.VillagerSystem

class DebugCommand(
    private val villagers: VillagerSystem,
    private val log: (String) -> Unit
) {
    fun handle(event: DomainCommandEvent) {
        val sender = event.sender
        val reply = event.response
        if (!sender.isPlayer) {
            reply.sendError(sender, "Only players can use this.")
            return
        }

        when (event.args.firstOrNull()) {
            "work" -> {
                val xyz = event.args.drop(1).mapNotNull { it.toIntOrNull() }
                if (xyz.size != 3) reply.sendError(sender, "Usage: /debug work x y z")
                else villagers.fakeWork(LocationIContainer("world", Vec3I(xyz[0], xyz[1], xyz[2])))
            }
            "villagers" -> {
                val lines = villagers.diagnose()
                if (lines.isEmpty()) reply.send(sender, "No villagers loaded")
                lines.forEach { reply.send(sender, it) }
            }
            "trace" -> {
                val enable = Trace.sink == null
                Trace.sink = if (enable) { msg -> log("[trace] $msg") } else null
                reply.send(sender, "Trace: ${if (enable) "on" else "off"}")
            }
            else -> reply.send(sender, "Usage: /debug work x y z | villagers | trace")
        }
    }
}