package phonon.nodes.listeners

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import phonon.nodes.stats.PlayerStats
import phonon.nodes.stats.WarStats
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts:
 * - Lifetime K/D (PlayerStats) always
 * - War session K/D (WarStats) only while /nda war enable is tracking
 * - XC guns via deathEvents reflection
 */
class StatsListener : Listener {

    companion object {
        /** victim UUID -> last player UUID that damaged them */
        val lastDamager: ConcurrentHashMap<UUID, UUID> = ConcurrentHashMap()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker: Player? = when (val d = event.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        }
        if (attacker != null && attacker.uniqueId != victim.uniqueId) {
            lastDamager[victim.uniqueId] = attacker.uniqueId
        }
    }

    /**
     * LOW: before XC removes deathEvents (XC uses default NORMAL).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        PlayerStats.addDeath(victim.uniqueId)
        WarStats.addDeath(victim.uniqueId)

        val killerId: UUID? =
            resolveKiller(victim)?.uniqueId
                ?: tryGetXcKillerUuid(victim)
                ?: lastDamager.remove(victim.uniqueId)

        if (killerId != null && killerId != victim.uniqueId) {
            PlayerStats.addKill(killerId)
            WarStats.addKill(killerId)
            Bukkit.getPlayer(killerId)?.let { phonon.nodes.killshop.KillShop.onPlayerKill(it) }
        }
    }

    private fun resolveKiller(victim: Player): Player? {
        victim.killer?.let { return it }

        val last = victim.lastDamageCause as? EntityDamageByEntityEvent ?: return null
        return when (val damager = last.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
    }

    /**
     * XC stores killer in XC.deathEvents before damage(..., null).
     */
    private fun tryGetXcKillerUuid(victim: Player): UUID? {
        return try {
            val plugin = Bukkit.getPluginManager().getPlugin("xc")
                ?: Bukkit.getPluginManager().getPlugin("XC")
                ?: return null

            val xcField = plugin.javaClass.getDeclaredField("xc")
            xcField.isAccessible = true
            val xc = xcField.get(plugin) ?: return null

            val deathEventsField = xc.javaClass.getDeclaredField("deathEvents")
            deathEventsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = deathEventsField.get(xc) as? Map<UUID, Any?> ?: return null

            val deathEvent = map[victim.uniqueId] ?: return null

            val getKiller = deathEvent.javaClass.methods.firstOrNull {
                it.name == "getKiller" && it.parameterCount == 0
            } ?: return null

            val killerEntity = getKiller.invoke(deathEvent) as? org.bukkit.entity.Entity
            (killerEntity as? Player)?.uniqueId
        } catch (_: Exception) {
            null
        }
    }
}
