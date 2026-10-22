package phonon.nodes.supply

import org.bukkit.ChatColor
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town

/**
 * A town's territory is supplied if it can reach the town's home through
 * territories owned by the town, its nation or its allies. Territories held
 * by an enemy break the chain. With wasteland routes on, unclaimed territories
 * are passable and wasteland links every territory that borders it.
 */
object SupplyLines {

    fun isFriendly(town: Town, other: Town?): Boolean {
        if (other == null) return false
        if (other === town) return true
        val nation = town.nation
        if (nation != null && other.nation === nation) return true
        return town.allies.contains(other)
    }

    fun isBlocked(town: Town, territory: Territory): Boolean {
        val occupier = territory.occupier
        if (occupier != null && !isFriendly(town, occupier)) return true

        val threshold = Config.supplyLinesChunkBlockFraction
        if (threshold <= 0.0 || threshold > 1.0 || territory.chunks.isEmpty()) return false

        var hostile = 0
        for (coord in territory.chunks) {
            val chunkOccupier = Nodes.getTerritoryChunkFromCoord(coord)?.occupier ?: continue
            if (!isFriendly(town, chunkOccupier)) hostile++
        }
        return hostile.toDouble() / territory.chunks.size >= threshold
    }

    private fun isWasteland(territory: Territory): Boolean = territory.bordersWilderness || territory.town == null

    private fun canPass(town: Town, territory: Territory): Boolean {
        if (territory.town == null) return Config.supplyLinesWastelandRoutes
        return isFriendly(town, territory.town) && !isBlocked(town, territory)
    }

    fun connected(town: Town): Set<TerritoryId> {
        val home = Nodes.getTerritoryFromId(town.home) ?: return emptySet()
        if (isBlocked(town, home)) return emptySet()

        val visited = hashSetOf(home.id)
        val queue = ArrayDeque<Territory>()
        queue.add(home)
        var wastelandReached = false
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighborId in current.neighbors) {
                if (neighborId in visited) continue
                val neighbor = Nodes.getTerritoryFromId(neighborId) ?: continue
                if (!canPass(town, neighbor)) continue
                visited.add(neighborId)
                queue.add(neighbor)
            }
            if (!wastelandReached && Config.supplyLinesWastelandRoutes && isWasteland(current)) {
                wastelandReached = true
                for ((id, territory) in Nodes.territories) {
                    if (id in visited || !isWasteland(territory) || !canPass(town, territory)) continue
                    visited.add(id)
                    queue.add(territory)
                }
            }
        }
        return visited.filterTo(hashSetOf()) { it in town.territories }
    }

    fun cutOff(town: Town): List<TerritoryId> {
        if (!Config.supplyLinesEnabled) return emptyList()
        val supplied = connected(town)
        return town.territories.filter { it !in supplied }.sortedBy { it.toInt() }
    }

    fun isCutOff(town: Town, territoryId: TerritoryId): Boolean {
        if (!Config.supplyLinesEnabled) return false
        return territoryId !in connected(town)
    }

    fun captureTimeMultiplier(): Double = Config.supplyLinesCutCaptureTimeMultiplier.coerceIn(0.01, 1.0)

    fun incomeMultiplier(): Double = Config.supplyLinesCutIncomeMultiplier.coerceIn(0.0, 1.0)

    fun notifyCutOff(town: Town, cut: List<TerritoryId>) {
        if (cut.isEmpty()) return
        val ids = cut.joinToString(", ") { it.toInt().toString() }
        val msg = "${ChatColor.GOLD}[Supply] ${cut.size} territories cut off from your home " +
            "(income x${formatMultiplier(incomeMultiplier())}, enemies capture them faster): ${ChatColor.WHITE}$ids"
        for (player in town.playersOnline) {
            Message.print(player, msg)
        }
    }

    fun formatMultiplier(m: Double): String = if (m == Math.floor(m)) m.toLong().toString() else "%.2f".format(m).trimEnd('0').trimEnd('.')
}
