/**
 * Handles all events for creating/deleting
 * player town name tags
 */

package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import phonon.nodes.Nodes
import phonon.nodes.objects.Nametag

public class NodesNametagListener : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (Nodes.getResident(player)?.town !== null) {
            Nametag.updateTextForPlayer(player)
        }
    }
}
