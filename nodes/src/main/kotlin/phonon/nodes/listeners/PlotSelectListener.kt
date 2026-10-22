package phonon.nodes.listeners

import org.bukkit.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import phonon.nodes.Message
import phonon.nodes.plot.Plots

/**
 * While in /p select mode:
 * left-click block = pos1, right-click block = pos2.
 */
class PlotSelectListener : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (!Plots.isSelecting(player)) return
        if (event.hand != EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return
        val sel = Plots.getSelection(player) ?: return

        when (event.action) {
            Action.LEFT_CLICK_BLOCK -> {
                event.isCancelled = true
                sel.pos1 = block.location
                Message.print(
                    player,
                    "${ChatColor.GREEN}Plot pos1 set: ${block.x}, ${block.y}, ${block.z}",
                )
            }
            Action.RIGHT_CLICK_BLOCK -> {
                event.isCancelled = true
                sel.pos2 = block.location
                Message.print(
                    player,
                    "${ChatColor.GREEN}Plot pos2 set: ${block.x}, ${block.y}, ${block.z}",
                )
                if (sel.pos1 != null) {
                    Message.print(player, "${ChatColor.YELLOW}Selection complete. /p create <name>")
                }
            }
            else -> return
        }
    }
}
