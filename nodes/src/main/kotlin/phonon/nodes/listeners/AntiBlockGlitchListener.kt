package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.util.AntiBlockGlitch

/**
 * Counts protected-land denials even when other code cancels the event first.
 * MONITOR + ignoreCancelled=false still sees cancelled events.
 */
class AntiBlockGlitchListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (Config.antiBlockGlitchAttempts <= 0) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        if (!event.isCancelled) return

        val block = event.clickedBlock ?: return
        if (!isForeignProtected(event.player, block.x, block.z)) return

        AntiBlockGlitch.recordDenied(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) {
        if (Config.antiBlockGlitchAttempts <= 0) return
        if (!event.isCancelled) return
        if (!isForeignProtected(event.player, event.block.x, event.block.z)) return

        AntiBlockGlitch.recordDenied(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (Config.antiBlockGlitchAttempts <= 0) return
        if (!event.isCancelled) return
        // skip towers / refineries if those cancel for other reasons
        if (phonon.nodes.mining.MiningTowers.isTowerBlock(event.block)) return
        if (phonon.nodes.refinery.Refineries.getByBlock(event.block) != null) return
        if (!isForeignProtected(event.player, event.block.x, event.block.z)) return

        AntiBlockGlitch.recordDenied(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        AntiBlockGlitch.clear(event.player)
    }

    /**
     * True when this is town land the player should not freely grief.
     * Adjust if your resident API differs.
     */
    private fun isForeignProtected(player: org.bukkit.entity.Player, x: Int, z: Int): Boolean {
        val chunk = Nodes.getTerritoryChunkFromBlock(x, z) ?: return false
        val town = chunk.territory.town ?: return false

        val resident = Nodes.getResident(player)
            ?: Nodes.getResidentFromUUID(player.uniqueId)
            ?: return true // not in a town → foreign

        val playerTown = resident.town
        if (playerTown != null && playerTown.uuid == town.uuid) return false
        if (playerTown != null && playerTown.allies.contains(town)) return false
        if (player.isOp || player.hasPermission("nodes.admin")) return false

        return true
    }
}
