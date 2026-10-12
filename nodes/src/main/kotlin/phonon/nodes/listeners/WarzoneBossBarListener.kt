package phonon.nodes.listeners

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import phonon.nodes.Nodes
import phonon.nodes.war.Warzone

class WarzoneBossBarListener : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from
        if (from.blockX == to.blockX && from.blockZ == to.blockZ) return
        Warzone.refreshBossBar(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        Warzone.clearBossBar(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val plugin = Nodes.plugin ?: return
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable { Warzone.refreshBossBar(event.player) },
            20L,
        )
    }
}
