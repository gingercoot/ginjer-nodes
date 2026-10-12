package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.mining.MiningTowers
import phonon.nodes.mining.TowerReward

/**
 * /miningtower | /mt
 *
 * create <id> <cooldownSec> <material> <amount> [<material> <amount> ...]
 * remove <id>
 * list
 */
class MiningTowerCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("nodes.admin") && !sender.isOp) {
            Message.error(sender, "No permission")
            return true
        }
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "create", "place" -> cmdCreate(sender, args)
            "remove", "delete" -> cmdRemove(sender, args)
            "list" -> cmdList(sender)
            else -> printHelp(sender)
        }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Mining Towers]")
        Message.print(sender, "/mt create <id> <cooldown> <item> <amt> [item amt ...]${ChatColor.WHITE}: cooldown e.g. 30m, 3h")
        Message.print(sender, "/mt remove <id>")
        Message.print(sender, "/mt list")
    }

    private fun cmdCreate(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return
        }
        // create id cooldown mat amt [mat amt ...]
        if (args.size < 5 || (args.size - 3) % 2 != 0) {
            Message.error(sender, "Usage: /mt create <id> <cooldownSec> <item> <amount> [item amount ...]")
            return
        }
        val id = args[1]
        val cdMs = phonon.nodes.mining.MiningBoost.parseDurationMs(args[2])
        if (cdMs == null || cdMs < 0) {
            Message.error(sender, "Invalid cooldown (use 30m, 3h, 1h30m, or minutes as a number)")
            return
        }
        val cdSec = cdMs / 1000L

        val rewards = ArrayList<TowerReward>()
        var i = 3
        while (i + 1 < args.size) {
            val mat = Material.matchMaterial(args[i])
            val amt = args[i + 1].toIntOrNull()
            if (mat == null || amt == null || amt < 1) {
                Message.error(sender, "Invalid reward: ${args[i]} ${args.getOrNull(i + 1)}")
                return
            }
            rewards.add(TowerReward(mat, amt))
            i += 2
        }
        val block = sender.location.block
        val result = MiningTowers.create(id, block, cdSec, rewards)
        if (result.isSuccess) {
            Message.print(
                sender,
                "${ChatColor.GREEN}Created mining tower \"$id\" at ${block.x},${block.y},${block.z} cooldown=${args[2]}",
            )
        } else {
            Message.error(sender, result.exceptionOrNull()?.message ?: "failed")
        }
    }

    private fun cmdRemove(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /mt remove <id>")
            return
        }
        if (MiningTowers.remove(args[1])) {
            Message.print(sender, "${ChatColor.GREEN}Removed tower ${args[1]}")
        } else {
            Message.error(sender, "Unknown tower ${args[1]}")
        }
    }

    private fun cmdList(sender: CommandSender) {
        if (MiningTowers.towers.isEmpty()) {
            Message.print(sender, "No mining towers")
            return
        }
        for (t in MiningTowers.towers.values.sortedBy { it.id }) {
            val loot = t.rewards.joinToString { "${it.material.name}x${it.amount}" }
            val cdLabel = formatCooldown(t.cooldownSeconds)
            Message.print(
                sender,
                "  ${t.id} @ ${t.world} ${t.x},${t.y},${t.z} cd=${MiningTowers.formatDuration(t.cooldownSeconds)} [$loot]",
            )
        }
    }

    private fun formatCooldown(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> "${h}h${m}m"
            h > 0 -> "${h}h"
            m > 0 && s > 0 -> "${m}m${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return listOf("create", "remove", "list", "help").filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("remove", true)) {
            return MiningTowers.towers.keys.filter { it.lowercase().startsWith(args[1].lowercase()) }.sorted()
        }
        return listOf()
    }
}
