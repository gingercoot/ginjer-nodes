/**
 * Income background scheduler to periodically run
 * message that towns are over max claims
 */

package phonon.nodes.tasks

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Nodes

public object OverMaxClaimsReminder {

    private var task: BukkitTask? = null

    public fun start(plugin: Plugin, period: Long) {
        if (this.task !== null) {
            return
        }

        val task = object : Runnable {

            public override fun run() {
                Bukkit.getScheduler().runTask(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            Nodes.overMaxClaimsReminder()
                        }
                    },
                )
            }
        }

        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, period, period)
    }

    public fun stop() {
        val task = this.task
        if (task === null) {
            return
        }

        task.cancel()
        this.task = null
    }
}
