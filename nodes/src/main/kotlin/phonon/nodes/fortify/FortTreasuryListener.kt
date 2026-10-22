package phonon.nodes.fortify

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent

class FortTreasuryListener : Listener {

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? FortTreasuryHolder ?: return
        val player = event.player as? Player ?: return
        Fortifications.depositFrom(player, holder)
    }
}
