package phonon.nodes.listeners

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes.getRelationshipOfPlayerToPlayer
import phonon.nodes.constants.DiplomaticRelationship
import java.util.UUID

public class NodesPlayerDamageListener : Listener {

    private fun resolveAttacker(damager: Entity): Player? {
        when (damager) {
            is Player -> return damager
            is Projectile -> {
                val shooter = damager.shooter
                if (shooter is Player) return shooter
            }
        }
        for (key in listOf("owner", "shooter", "attacker", "xc_owner", "player")) {
            if (damager.hasMetadata(key)) {
                when (val v = damager.getMetadata(key).firstOrNull()?.value()) {
                    is Player -> return v
                    is UUID -> return Bukkit.getPlayer(v)
                    is String -> return Bukkit.getPlayerExact(v)
                }
            }
        }
        if (damager is Tameable) {
            val owner = damager.owner
            if (owner is Player) return owner
        }
        return null
    }

    private fun applyFriendlyFire(
        event: EntityDamageEvent,
        attacker: Player,
        victim: Player,
    ) {
        val relationship = getRelationshipOfPlayerToPlayer(victim, attacker)
        val (cancel, message) = when (relationship) {
            DiplomaticRelationship.TOWN -> {
                val c = !Config.allowTownFriendlyFire
                c to if (c) "You cannot attack members of your town" else ""
            }
            DiplomaticRelationship.NATION -> {
                val c = !Config.allowNationFriendlyFire
                c to if (c) "You cannot attack members of your nation" else ""
            }
            DiplomaticRelationship.ALLY -> {
                val c = !Config.allowAllyFriendlyFire
                c to if (c) "You cannot attack your allies" else ""
            }
            else -> false to ""
        }
        if (cancel) {
            event.isCancelled = true
            if (message.isNotEmpty()) {
                Message.error(attacker, message)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return

        phonon.nodes.Nodes.logger?.info(
            "[FF-raw] ByEntity cause=${event.cause} damager=${event.damager.javaClass.name} " +
                "victim=${victim.name}",
        )
        val attacker = resolveAttacker(event.damager) ?: return
        if (attacker.uniqueId == victim.uniqueId) return
        "[FF-raw] ByEntity cause=${event.cause} damager=${event.damager.javaClass.name} " +
            "${attacker.name} -> ${victim.name}"

        applyFriendlyFire(event, attacker, victim)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAnyDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) return
        val victim = event.entity as? Player ?: return
        phonon.nodes.Nodes.logger?.info(
            "[FF-raw] AnyDamage cause=${event.cause} victim=${victim.name}",
        )
        val causing = try {
            event.damageSource.causingEntity
        } catch (_: Throwable) {
            null
        } ?: return
        val attacker = resolveAttacker(causing) ?: (causing as? Player) ?: return
        if (attacker.uniqueId == victim.uniqueId) return
        applyFriendlyFire(event, attacker, victim)
    }
}
