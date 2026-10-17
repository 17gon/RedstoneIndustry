package net.craftoriya.redstoneIndustry.command

import net.craftoriya.adaptersLib.event.domainevents.player.DomainCommandEvent
import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.demolish.area.AreaKind
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
            "region" -> region(event, event.args.drop(1))
            "demolish" -> {
                val xyz = event.args.drop(1).mapNotNull { it.toIntOrNull() }
                if (xyz.size != 3) { reply.sendError(sender, "Usage: /debug demolish x y z"); return }
                when (villagers.toggleDemolish(LocationIContainer("world", Vec3I(xyz[0], xyz[1], xyz[2])))) {
                    null -> reply.sendError(sender, "Not a workstation")
                    true -> reply.send(sender, "Demolish mode: on")
                    false -> reply.send(sender, "Demolish mode: off")
                }
            }
            else -> reply.send(sender, "Usage: /debug work x y z | villagers | trace | region | demolish x y z")
        }
    }

    private fun region(event: DomainCommandEvent, args: List<String>) {
        val sender = event.sender
        val reply = event.response
        val usage = "Usage: /debug region sx sy sz demolish|safe x1 y1 z1 x2 y2 z2 | sx sy sz clear [demolish|safe] | sx sy sz"
        val s = args.take(3).mapNotNull { it.toIntOrNull() }
        if (s.size != 3) { reply.sendError(sender, usage); return }
        val station = LocationIContainer("world", Vec3I(s[0], s[1], s[2]))
        val rest = args.drop(3)

        when (rest.firstOrNull()) {
            null -> reply.send(sender, villagers.describeAreas(station))
            "demolish", "safe" -> {
                val n = rest.drop(1).mapNotNull { it.toIntOrNull() }
                if (n.size != 6) { reply.sendError(sender, usage); return }
                val kind = AreaKind.valueOf(rest[0].uppercase())
                runCatching { villagers.setArea(station, kind, Vec3I(n[0], n[1], n[2]), Vec3I(n[3], n[4], n[5])) }
                    .onSuccess { reply.send(sender, "$kind area set, $it blocks") }
                    .onFailure { reply.sendError(sender, it.message ?: "Failed") }
            }
            "clear" -> {
                val kind = rest.getOrNull(1)?.let { runCatching { AreaKind.valueOf(it.uppercase()) }.getOrNull() }
                villagers.clearArea(station, kind)
                reply.send(sender, "Cleared ${kind ?: "all"}")
            }
            else -> reply.sendError(sender, usage)
        }
    }
}