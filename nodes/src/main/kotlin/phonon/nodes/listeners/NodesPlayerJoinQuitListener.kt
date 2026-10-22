/**
 * Handle when player join or quit server
 * join: create resident (if does not exist) and mark player online
 * quit: mark player offline
 */

package phonon.nodes.listeners

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import phonon.nodes.Nodes
import phonon.nodes.chat.Chat
import phonon.nodes.mining.MiningBoost
import phonon.nodes.objects.Resident
import phonon.nodes.war.WarTimer

public class NodesPlayerJoinQuitListener : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public fun onPlayerJoin(event: PlayerJoinEvent) {
        val player: Player = event.getPlayer()
        Nodes.createResident(player)
        phonon.nodes.mining.MiningBoost.onJoin(player)
        phonon.nodes.war.WarTimer.onPlayerJoin(player)

        val resident: Resident = Nodes.getResident(player)!!

        val nation = resident.nation ?: resident.town?.nation
        if (nation != null) {
            val cap = phonon.nodes.RallyCap.get(nation)
            if (cap >= 0 && nation.playersOnline.size >= cap) {
                org.bukkit.Bukkit.getScheduler().runTask(
                    Nodes.plugin!!,
                    Runnable {
                        player.kickPlayer(phonon.nodes.RallyCap.denyMessage(nation))
                    },
                )
                return
            }
        }

        Nodes.setResidentOnline(resident, player)

        if (Nodes.war.enabled == true) {
            Nodes.war.sendWarProgressBarToPlayer(player)
        }
    }

    @EventHandler
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        val player: Player = event.getPlayer()
        val resident = Nodes.getResident(player)
        if (resident != null) {
            resident.destroyMinimap()
            Nodes.setResidentOffline(resident, player)
            phonon.nodes.mining.MiningBoost.onQuit(player)
            phonon.nodes.war.WarTimer.onPlayerQuit(player)
        }

        Chat.enableGlobalChat(player)

        if (Nodes.war.enabled) {
            val attacks = Nodes.war.attackers.get(player.getUniqueId())
            if (attacks !== null) {
                for (a in attacks) {
                    a.cancel()
                }
            }
        }
    }
}
