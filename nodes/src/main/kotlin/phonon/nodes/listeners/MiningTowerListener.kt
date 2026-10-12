package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import phonon.nodes.mining.MiningTowers

/**
 * Mining towers stay break-to-mine.
 * Land protection cancels left-click/damage — we un-cancel those so a real break can happen,
 * then tryMine runs only on BlockBreakEvent.
 */
class MiningTowerListener : Listener {

    /** Allow starting to mine the furnace on protected land. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onLeftClick(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!MiningTowers.isTowerBlock(block)) return

        // Un-cancel land deny so the client can actually mine the block
        event.isCancelled = false
    }

    /** Allow block damage (cracking) on towers. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: BlockDamageEvent) {
        if (!MiningTowers.isTowerBlock(event.block)) return
        event.isCancelled = false
    }

    /** Only here: loot + cooldown + restore furnace (must fully break). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        if (!MiningTowers.isTowerBlock(event.block)) return

        event.isCancelled = true // no vanilla furnace drop
        MiningTowers.tryMine(event.player, event.block)
    }
}
