package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import phonon.nodes.Message
import phonon.nodes.refinery.Refineries
import phonon.nodes.refinery.RefineryGui

/**
 * Public refinery access — bypasses town/chest protection via HIGHEST + ignoreCancelled=false.
 */
class RefineryListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val r = Refineries.getByBlock(block) ?: return

        event.isCancelled = true // no vanilla dropper UI
        RefineryGui.open(event.player, r)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        val r = Refineries.getByBlock(block) ?: return

        if (!event.player.hasPermission("nodes.admin") && !event.player.isOp) {
            event.isCancelled = true
            Message.error(event.player, "You cannot break this refinery")
            return
        }

        // Admin: remove data + allow break (or cancel and set AIR yourself)
        event.isCancelled = true
        Refineries.remove(r.id)
        Message.print(event.player, "Removed refinery ${r.id}")
    }
}
