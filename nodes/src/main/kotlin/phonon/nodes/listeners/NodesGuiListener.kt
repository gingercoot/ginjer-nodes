/**
 * Listener for inventory GUI events,
 * route events to GUI manager
 */

package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import phonon.nodes.gui.GuiWindow

public class NodesGuiListener : Listener {

    @EventHandler
    public fun onInventoryClick(event: InventoryClickEvent) {
        val inventory = event.getClickedInventory()
        val holder = inventory?.holder
        val view = event.getView()
        val topHolder = view.getTopInventory().holder

        if (topHolder is GuiWindow) {
            if (holder != null && holder is GuiWindow) {
                holder.runOnClick(event)
            } else {
                when (event.action) {
                    InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                        topHolder.runOnItemDeposit(event)
                    }

                    InventoryAction.COLLECT_TO_CURSOR -> {
                        event.setCancelled(true)
                    }
                    else -> {}
                }
            }
        }
    }

    @EventHandler
    public fun onInventoryDrag(event: InventoryDragEvent) {
        val inventory = event.getInventory()
        val holder = inventory?.holder

        if (holder != null && holder is GuiWindow) {
            holder.runOnDrag(event)
        }
    }

    @EventHandler
    public fun onInventoryClose(event: InventoryCloseEvent) {
        val inventory = event.getInventory()
        val holder = inventory?.holder
        if (holder != null && holder is GuiWindow) {
            holder.runOnClose(event)
        }
    }
}
