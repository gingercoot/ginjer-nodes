/**
 * Centralized handler for long periodic tasks:
 * - Income, backup, cooldowns
 */

package phonon.nodes

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.utils.FileWriteTask

public object PeriodicTickManager {

    private var task: BukkitTask? = null

    private var previousTime: Long = 0L

    public fun start(plugin: Plugin, period: Long) {
        if (this.task !== null) {
            return
        }

        previousTime = System.currentTimeMillis()

        val task = object : Runnable {
            public override fun run() {
                // Capture previousTime locally so dt stays valid even if the main thread
                // lags and this task runs again before the scheduled tasks do
                val currTime = System.currentTimeMillis()
                val capturedPreviousTime = previousTime
                previousTime = currTime

                // =================================
                // income cycle
                // =================================
                if (currTime > Nodes.lastIncomeTime + Config.incomePeriod) {
                    Nodes.lastIncomeTime = currTime

                    Bukkit.getScheduler().runTask(
                        plugin,
                        object : Runnable {
                            override fun run() {
                                if (Config.incomeEnabled) {
                                    Nodes.runIncome()
                                }
                            }
                        },
                    )

                    Bukkit.getScheduler().runTaskAsynchronously(Nodes.plugin!!, FileWriteTask(currTime.toString(), Config.pathLastIncomeTime, null))
                }

                // =================================
                // town, resident, truce cooldowns
                // pipeline by running on offset
                // =================================
                Bukkit.getScheduler().runTask(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            val currTime = System.currentTimeMillis()
                            val dt = currTime - capturedPreviousTime
                            Nodes.townMoveHomeCooldownTick(dt)
                        }
                    },
                )
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            val currTime = System.currentTimeMillis()
                            val dt = currTime - capturedPreviousTime
                            Nodes.claimsPowerRamp(dt)
                        }
                    },
                    1L,
                )
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            val currTime = System.currentTimeMillis()
                            val dt = currTime - capturedPreviousTime
                            Nodes.claimsPenaltyDecay(dt)
                        }
                    },
                    2L,
                )
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            val currTime = System.currentTimeMillis()
                            val dt = currTime - capturedPreviousTime
                            Nodes.residentTownCreateCooldownTick(dt)
                        }
                    },
                    3L,
                )
                Bukkit.getScheduler().runTaskLater(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            Nodes.truceTick()
                        }
                    },
                    4L,
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
