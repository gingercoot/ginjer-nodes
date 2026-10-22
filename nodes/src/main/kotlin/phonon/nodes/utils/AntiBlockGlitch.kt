package phonon.nodes.util

import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import phonon.nodes.Config
import phonon.nodes.Message
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AntiBlockGlitch {
    private val fails: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()

    fun recordDenied(player: Player) {
        val max = Config.antiBlockGlitchAttempts
        if (max <= 0) return

        val n = (fails[player.uniqueId] ?: 0) + 1
        fails[player.uniqueId] = n

        if (n >= max) {
            fails[player.uniqueId] = 0
            val ticks = Config.antiBlockGlitchDurationTicks.coerceAtLeast(1)

            player.addPotionEffect(
                PotionEffect(PotionEffectType.POISON, ticks, 0, false, true, true),
            )
            val darkness = PotionEffectType.getByName("DARKNESS")
                ?: PotionEffectType.BLINDNESS
            player.addPotionEffect(
                PotionEffect(darkness, ticks, 0, false, true, true),
            )

            try {
                player.showTitle(
                    net.kyori.adventure.title.Title.title(
                        net.kyori.adventure.text.Component.text("NO BLOCK GLITCHING")
                            .color(net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD),
                        net.kyori.adventure.text.Component.text("Stop blockglitching boiii")
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                        net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(200),
                            java.time.Duration.ofMillis(2500),
                            java.time.Duration.ofMillis(500),
                        ),
                    ),
                )
                // Spin for the full punishment duration (same as darkness/poison)
                val plugin = phonon.nodes.Nodes.plugin
                if (plugin != null) {
                    val durationTicks = ticks
                    val period = 2L
                    val maxSteps = (durationTicks / period).toInt().coerceAtLeast(1)
                    // 3 full turns over the whole duration
                    val degreesPerStep = (360f * 3.0f) / maxSteps

                    var steps = 0
                    org.bukkit.Bukkit.getScheduler().runTaskTimer(
                        plugin,
                        Runnable {
                            if (!player.isOnline || steps >= maxSteps) {
                                return@Runnable
                            }
                            steps++
                            val loc = player.location.clone()
                            loc.yaw = (loc.yaw + degreesPerStep) % 360f
                            player.teleport(loc)
                        },
                        0L,
                        period,
                    ).also { task ->
                        org.bukkit.Bukkit.getScheduler().runTaskLater(
                            plugin,
                            Runnable { task.cancel() },
                            durationTicks.toLong() + 1L,
                        )
                    }
                }
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                player.sendTitle(
                    "${ChatColor.DARK_RED}${ChatColor.BOLD}NO BLOCK GLITCHING",
                    "${ChatColor.GRAY}Stop spam-clicking protected blocks",
                    10,
                    50,
                    10,
                )
            }

            Message.error(
                player,
                "${ChatColor.DARK_GRAY}Stop blockglitching boiii",
            )
        }
    }

    fun clear(player: Player) {
        fails.remove(player.uniqueId)
    }

    fun clear(uuid: UUID) {
        fails.remove(uuid)
    }
}
