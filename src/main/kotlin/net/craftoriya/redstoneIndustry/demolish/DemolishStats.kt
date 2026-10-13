package net.craftoriya.redstoneIndustry.demolish

import java.util.Locale

internal class DemolishStats(private val startTick: Long) {
    var broke = 0
    var dropped = 0L
    var rejected = 0
    var gone = 0
    var filtered = 0
    var unbreakable = 0
    var skipped = 0
    var unreachable = 0
    var bands = 0

    var moves = 0
    var moveTimeouts = 0
    var standLost = 0
    var walkRebuilds = 0
    var walkSkips = 0
    var areaResets = 0
    var rescans = 0
    var noStand = 0

    var steals = 0
    var balances = 0

    var climbs = 0
    var climbSteps = 0
    var climbAborts = 0
    var climbNoStart = 0
    var climbWaits = 0

    var toolTaken = 0
    var toolBroken = 0
    var noTool = 0
    var invFull = 0

    var itemsOut = 0L
    var deliverRetries = 0

    private val ticks = LongArray(5)

    fun count(phase: Phase) {
        ticks[phase.ordinal]++
    }

    fun tickWait() {
        ticks[WAIT]++
    }

    fun format(zone: DemolishZone, now: Long): String {
        val elapsed = (now - startTick).coerceAtLeast(1L)
        val rate = String.format(Locale.ROOT, "%.1f", broke * 1200.0 / elapsed)
        val total = ticks.sum().coerceAtLeast(1L)
        val share = ticks.joinToString("/") { (it * 100 / total).toString() }
        val crew = Phase.entries.joinToString("/") { p -> zone.workers.values.count { it.phase == p }.toString() }
        return "demolish zone ${zone.id.toString().take(8)} w=${zone.workers.size} t=${elapsed / 20}s " +
                "y=${zone.layerY}>${zone.box.min.y} q=${zone.queued()} ph[P/M/B/D]=$crew | " +
                "broke=$broke ($rate/min) items=$dropped rej=$rejected gone=$gone filt=$filtered unbrk=$unbreakable " +
                "skip=$skipped unr=$unreachable band=$bands | " +
                "move=$moves mvTO=$moveTimeouts lost=$standLost walk=$walkRebuilds/$walkSkips noStand=$noStand " +
                "reset=$areaResets rescan=$rescans | steal=$steals bal=$balances | " +
                "climb=$climbs steps=$climbSteps abort=$climbAborts noStart=$climbNoStart wait=$climbWaits | " +
                "tool take=$toolTaken broke=$toolBroken none=$noTool full=$invFull | " +
                "out=$itemsOut retry=$deliverRetries | time%[pick/move/break/deliver/wait]=$share"
    }

    private companion object {
        const val WAIT = 4
    }
}