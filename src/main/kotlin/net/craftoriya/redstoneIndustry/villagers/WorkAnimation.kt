package net.craftoriya.redstoneIndustry.villagers


import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3D
import net.craftoriya.adaptersLib.port.ISchedulerPort
import net.craftoriya.adaptersLib.port.IVillagerControlPort
import java.util.UUID

class WorkAnimation(
    private val villagers: IVillagerControlPort,
    private val scheduler: ISchedulerPort,
    private val swingEvery: Int = 10
) {
    fun play(villager: UUID, jobSite: LocationIContainer, ticks: Int) {
        val v = jobSite.vec3I
        val target = Vec3D(v.x + 0.5, v.y + 0.5, v.z + 0.5)
        var elapsed = 0
        scheduler.runRepeating(0L, 1L) {
            villagers.faceTo(villager, target)
            if (elapsed % swingEvery == 0) villagers.swingMainHand(villager)
            ++elapsed < ticks
        }
    }
}