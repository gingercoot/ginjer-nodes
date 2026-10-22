package phonon.nodes.listeners

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.getRelationshipOfPlayerToPlayer
import phonon.nodes.constants.DiplomaticRelationship

/**
 * Soft-hooks XC gun hits via XCProjectileDamageEvent (no compile dependency on XC).
 * Call NodesXcDamageListener.register(plugin) from onEnable.
 */
public object NodesXcDamageListener {

    public fun register(plugin: JavaPlugin) {
        val eventClass = try {
            Class.forName("phonon.xc.event.XCProjectileDamageEvent")
        } catch (_: ClassNotFoundException) {
            Nodes.logger?.info("[Nodes] XC not present — gun FF hook skipped")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val clazz = eventClass as Class<out Event>

        val executor = EventExecutor { _, event ->
            handle(event)
        }

        plugin.server.pluginManager.registerEvent(
            clazz,
            object : Listener {},
            EventPriority.HIGH,
            executor,
            plugin,
            false,
        )
        Nodes.logger?.info("[Nodes] Hooked XCProjectileDamageEvent for gun friendly-fire")
    }

    private fun handle(event: Event) {
        try {
            val target = event.javaClass.methods
                .firstOrNull { it.name == "getTarget" && it.parameterCount == 0 }
                ?.invoke(event) as? Player
                ?: return

            val source = event.javaClass.methods
                .firstOrNull { it.name == "getSource" && it.parameterCount == 0 }
                ?.invoke(event) as? Player
                ?: return

            if (source.uniqueId == target.uniqueId) return

            val relationship = getRelationshipOfPlayerToPlayer(target, source)
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
                event.javaClass.methods
                    .firstOrNull { it.name == "setCancelled" && it.parameterCount == 1 }
                    ?.invoke(event, true)
                if (message.isNotEmpty()) {
                    Message.error(source, message)
                }
            }
        } catch (e: Exception) {
            Nodes.logger?.warning("[Nodes] XC FF hook error: ${e.message}")
        }
    }
}
