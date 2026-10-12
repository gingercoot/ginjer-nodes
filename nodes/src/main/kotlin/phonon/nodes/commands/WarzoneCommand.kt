package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.war.Warzone

class WarzoneCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "status" -> {
                if (!sender.hasPermission("nodes.warzone.status") &&
                    !sender.hasPermission("nodes.warzone.admin") &&
                    !sender.isOp
                ) {
                    Message.error(sender, "No permission")
                    return true
                }
                cmdStatus(sender, args)
            }
            "list" -> {
                // list is useful for everyone who can status
                if (!sender.hasPermission("nodes.warzone.status") &&
                    !sender.hasPermission("nodes.warzone.admin") &&
                    !sender.isOp
                ) {
                    Message.error(sender, "No permission")
                    return true
                }
                cmdList(sender)
            }
            "create", "enable", "disable", "remove", "save" -> {
                if (!sender.hasPermission("nodes.warzone.admin") && !sender.isOp) {
                    Message.error(sender, "No permission")
                    return true
                }
                when (args[0].lowercase()) {
                    "create" -> cmdCreate(sender, args)
                    "enable" -> cmdEnable(sender, args)
                    "disable" -> cmdDisable(sender, args)
                    "remove" -> cmdRemove(sender, args)
                    "save" -> {
                        Warzone.save()
                        Message.print(sender, "[Warzone] Saved warzones.json")
                    }
                }
            }
            else -> printHelp(sender)
        }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Warzone commands:")
        Message.print(sender, "/warzone create <name> <territoryId> [ids...]${ChatColor.WHITE}: Create wz town + claim nodes")
        Message.print(sender, "/warzone enable <name> <duration>${ChatColor.WHITE}: Start event (e.g. 72h, 90m)")
        Message.print(sender, "/warzone disable <name>${ChatColor.WHITE}: End event and award winner")
        Message.print(sender, "/warzone status [name]${ChatColor.WHITE}: Scores / time left")
        Message.print(sender, "/warzone list${ChatColor.WHITE}: List registered warzones")
        Message.print(sender, "/warzone remove <name>${ChatColor.WHITE}: Unregister from warzone list (must be disabled first)")
    }

    private fun cmdCreate(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /warzone create <name> <id> [id...]")
            return
        }
        val name = args[1]
        val ids = ArrayList<Int>()
        for (i in 2 until args.size) {
            val id = args[i].toIntOrNull()
            if (id == null) {
                Message.error(sender, "Invalid territory id: ${args[i]}")
                return
            }
            ids.add(id)
        }
        val result = Warzone.create(name, ids)
        if (result.isSuccess) {
            Message.print(sender, "${ChatColor.GREEN}Created warzone town \"$name\" with ${ids.size} territories. Use /warzone enable $name <duration>")
        } else {
            Message.error(sender, result.exceptionOrNull()?.message ?: "create failed")
        }
    }

    private fun cmdEnable(sender: CommandSender, args: Array<out String>) {
        if (args.size < 3) {
            Message.error(sender, "Usage: /warzone enable <name> <duration>  (e.g. 72h, 90m, 2h)")
            return
        }
        val name = args[1]
        val ms = Warzone.parseDurationMs(args[2])
        if (ms == null || ms <= 0) {
            Message.error(sender, "Invalid duration \"${args[2]}\". Use 72h, 90m, 2h30m, or minutes as a number.")
            return
        }
        if (!Warzone.isWarzoneTownName(name) && Nodes.towns.containsKey(name)) {
            val t = Nodes.towns[name]!!
            Warzone.zones[name] = phonon.nodes.war.WarzoneState(name, t.uuid)
        }
        val result = Warzone.enable(name, ms)
        if (result.isSuccess) {
            Message.print(sender, "${ChatColor.GREEN}Warzone \"$name\" enabled for ${args[2]}")
        } else {
            Message.error(sender, result.exceptionOrNull()?.message ?: "enable failed")
        }
    }

    private fun cmdDisable(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /warzone disable <name>")
            return
        }
        val result = Warzone.disable(args[1], award = true)
        if (result.isSuccess) {
            Message.print(sender, "${ChatColor.GREEN}Warzone \"${args[1]}\" disabled / ended")
        } else {
            Message.error(sender, result.exceptionOrNull()?.message ?: "disable failed")
        }
    }

    private fun cmdStatus(sender: CommandSender, args: Array<out String>) {
        if (args.size >= 2) {
            printOne(sender, args[1])
            return
        }
        if (Warzone.zones.isEmpty()) {
            Message.print(sender, "No warzones registered.")
            return
        }
        for (name in Warzone.zones.keys.sorted()) {
            printOne(sender, name)
        }
    }

    private fun printOne(sender: CommandSender, name: String) {
        val state = Warzone.getState(name)
        if (state == null) {
            Message.error(sender, "Unknown warzone: $name")
            return
        }
        val town = Nodes.towns[name]
        val n = town?.territories?.size ?: 0

        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}Warzone: ${ChatColor.WHITE}$name")
        Message.print(sender, "  Territories: ${ChatColor.WHITE}$n")

        if (state.enabled && System.currentTimeMillis() < state.endsAt) {
            Message.print(sender, "  Status: ${ChatColor.GREEN}LIVE")
            Message.print(sender, "  Time left: ${ChatColor.WHITE}${Warzone.formatDurationLeft(state.endsAt)}")
            // same text as boss bar (current majority + lead totals)
            Message.print(sender, "  ${ChatColor.WHITE}${Warzone.holdSummary(state)}")
        } else {
            Message.print(sender, "  Status: ${ChatColor.GRAY}inactive")
            if (state.lastMajorityTown != null) {
                Message.print(sender, "  Last majority: ${ChatColor.WHITE}${state.lastMajorityTown}")
            }
        }

        if (state.scores.isEmpty()) {
            Message.print(sender, "  ${ChatColor.GRAY}Total hold times: (none)")
        } else {
            Message.print(sender, "  ${ChatColor.GRAY}Total hold times:")
            state.scores.entries
                .sortedByDescending { it.value }
                .forEach { (townName, ms) ->
                    Message.print(
                        sender,
                        "    ${ChatColor.WHITE}$townName${ChatColor.GRAY}: " +
                            "${ChatColor.WHITE}${Warzone.formatHoldMs(ms)}",
                    )
                }
        }
    }
    private fun cmdList(sender: CommandSender) {
        if (Warzone.zones.isEmpty()) {
            Message.print(sender, "No warzones registered.")
            return
        }
        for ((name, state) in Warzone.zones.toSortedMap()) {
            val tag = if (state.enabled) {
                "${ChatColor.GREEN}LIVE ${Warzone.formatDurationLeft(state.endsAt)}"
            } else {
                "${ChatColor.GRAY}idle"
            }
            Message.print(sender, "  $name - $tag")
        }
    }
    private fun cmdRemove(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /warzone remove <name>")
            return
        }
        val name = args[1]
        val state = Warzone.zones[name]
        if (state == null) {
            Message.error(sender, "Not on warzone list: $name")
            return
        }
        if (state.enabled) {
            Message.error(sender, "Disable it first: /warzone disable $name")
            return
        }
        Warzone.zones.remove(name)
        Warzone.needsSave = true
        Warzone.save()
        Message.print(sender, "${ChatColor.GREEN}Removed \"$name\" from warzone list (town in towns.json is unchanged)")
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return listOf("create", "enable", "disable", "status", "list", "help", "save")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].lowercase() in setOf("enable", "disable", "status", "remove")) {
            return Warzone.zones.keys.filter { it.lowercase().startsWith(args[1].lowercase()) }.sorted()
        }
        return listOf()
    }
}
