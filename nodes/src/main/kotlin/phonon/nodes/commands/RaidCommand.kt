package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.TerritoryId
import phonon.nodes.utils.string.filterTown
import phonon.nodes.war.Raid

class RaidCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "status" -> {
                Message.print(sender, "${ChatColor.BOLD}[Nodes] Active raids:")
                for (line in Raid.statusLines()) {
                    Message.print(sender, "  $line")
                }
            }
            "cancel" -> cmdCancel(sender)
            else -> {
                if (args.size < 2) {
                    Message.error(sender, "Usage: /raid <town> <territoryId>")
                    return true
                }
                cmdStart(sender, args[0], args[1])
            }
        }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Raid commands:")
        Message.print(sender, "/raid <town> <territoryId>${ChatColor.WHITE}: Start a raid (officer/leader)")
        Message.print(sender, "/raid status${ChatColor.WHITE}: List active raids")
        Message.print(sender, "/raid cancel${ChatColor.WHITE}: Cancel your raid during prep")
        Message.print(
            sender,
            "${ChatColor.GRAY}Prep then live capture on that node (not home). Must border your town. Blocked during war.",
        )
    }

    private fun cmdStart(sender: CommandSender, townName: String, terrStr: String) {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return
        }
        val resident = Nodes.getResident(sender) ?: return
        val attacker = resident.town ?: run {
            Message.error(sender, "You are not in a town")
            return
        }
        if (resident !== attacker.leader && !attacker.officers.contains(resident)) {
            Message.error(sender, "Only officers or the leader can start a raid")
            return
        }
        val defender = Nodes.getTownFromName(townName) ?: run {
            Message.error(sender, "Town not found")
            return
        }
        val terrId = terrStr.toIntOrNull() ?: run {
            Message.error(sender, "Territory id must be a number")
            return
        }
        val territory = Nodes.getTerritoryFromId(TerritoryId(terrId)) ?: run {
            Message.error(sender, "Territory not found")
            return
        }
        val result = Raid.start(attacker, defender, territory)
        if (result.isFailure) {
            Message.error(sender, result.exceptionOrNull()?.message ?: "Failed to start raid")
        } else {
            Message.print(sender, "${ChatColor.GREEN}Raid started on ${defender.name} territory $terrId")
        }
    }

    private fun cmdCancel(sender: CommandSender) {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return
        }
        val resident = Nodes.getResident(sender) ?: return
        val town = resident.town ?: run {
            Message.error(sender, "You are not in a town")
            return
        }
        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(sender, "Only officers or the leader can cancel")
            return
        }
        val err = Raid.cancelByAttacker(town)
        if (err != null) {
            Message.error(sender, err)
        } else {
            Message.print(sender, "${ChatColor.YELLOW}Raid cancel processed")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            val fixed = listOf("help", "status", "cancel")
            val towns = filterTown(args[0])
            return (fixed.filter { it.startsWith(args[0], ignoreCase = true) } + towns).distinct()
        }
        if (args.size == 2 &&
            args[0].lowercase() !in listOf("help", "status", "cancel")
        ) {
            val town = Nodes.getTownFromName(args[0]) ?: return emptyList()
            return town.territories
                .map { it.toString() }
                .filter { it.startsWith(args[1]) }
        }
        return emptyList()
    }
}
