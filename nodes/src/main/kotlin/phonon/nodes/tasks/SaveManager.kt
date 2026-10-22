/**
 * Scheduler for saving Nodes world state to towns.json
 *
 * Runs world save to towns.json on a fixed tick schedule.
 * If we save everytime world state updates, players can lag servers
 * by spamming commands. Running on fixed schedules avoids
 * this exploit.
 *
 */

package phonon.nodes.tasks

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation.NationSaveState
import phonon.nodes.objects.Port.PortSaveState
import phonon.nodes.objects.PortGroup.PortGroupSaveState
import phonon.nodes.objects.Resident.ResidentSaveState
import phonon.nodes.objects.Town.TownSaveState
import phonon.nodes.serdes.Serializer
import phonon.nodes.utils.saveStringToFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date

/** Saves the world; can run sync or async. */
public class TaskSaveWorld(
    val residentsSnapshot: List<ResidentSaveState>,
    val townsSnapshot: List<TownSaveState>,
    val nationsSnapshot: List<NationSaveState>,
    val pathTownSave: Path,
    val pathDynmapDir: Path,
    val pathDynmapTowns: Path,
    val pathBackupDir: Path,
    val pathLastBackupTime: Path,
    val copyToDynmap: Boolean,
    val backupTimestamp: Long,
) : Runnable {
    override fun run() {
        val jsonStr = Serializer.worldToJson(
            residentsSnapshot,
            townsSnapshot,
            nationsSnapshot,
        )

        saveStringToFile(jsonStr, pathTownSave)

        if (copyToDynmap) {
            Files.createDirectories(pathDynmapDir)
            Files.copy(pathTownSave, pathDynmapTowns, StandardCopyOption.REPLACE_EXISTING)
        }

        // Also copy to the backup folder when a backup timestamp is given
        if (backupTimestamp > 0) {
            TaskSaveBackup(backupTimestamp, pathTownSave, pathBackupDir, pathLastBackupTime).run()
        }
    }
}

private val BACKUP_DATE_FORMATTER = SimpleDateFormat("yyyy.MM.dd.HH.mm.ss")

internal class TaskSaveBackup(
    val timestamp: Long,
    val pathTowns: Path,
    val pathBackupDir: Path,
    val pathLastBackupTime: Path,
) : Runnable {
    override fun run() {
        if (Files.exists(pathTowns)) {
            Files.createDirectories(pathBackupDir)

            val date = Date(timestamp)
            val backupName = "towns.${BACKUP_DATE_FORMATTER.format(date)}.json"
            val pathBackup = pathBackupDir.resolve(backupName)
            Files.copy(pathTowns, pathBackup)
        }

        saveStringToFile(timestamp.toString(), Config.pathLastBackupTime)
    }
}

public class TaskSavePorts(
    val portsSnapshot: List<PortSaveState>,
    val portGroupsSnapshot: List<PortGroupSaveState>,
    val pathPortSave: Path,
    val pathDynmapDir: Path,
    val pathDynmapPorts: Path,
    val copyToDynmap: Boolean,
) : Runnable {
    override fun run() {
        val jsonStr = Serializer.portsToJson(
            portGroupsSnapshot,
            portsSnapshot,
        )

        saveStringToFile(jsonStr, pathPortSave)

        if (copyToDynmap) {
            Files.createDirectories(pathDynmapDir)
            Files.copy(pathPortSave, pathDynmapPorts, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Async periodic tick scheduler to signal main thread
 * to save world state.
 */
public object SaveManager {

    private var task: BukkitTask? = null

    public fun start(plugin: Plugin, period: Long) {
        if (this.task !== null) {
            return
        }

        val task = object : Runnable {

            init {
                Files.createDirectories(Paths.get(Config.pathPlugin).normalize())
            }

            public override fun run() {
                Bukkit.getScheduler().runTask(
                    plugin,
                    object : Runnable {
                        override fun run() {
                            Nodes.saveWorld(
                                checkIfNeedsSave = true,
                                async = true,
                            )
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
