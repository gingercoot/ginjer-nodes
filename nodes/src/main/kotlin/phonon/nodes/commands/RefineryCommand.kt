package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.refinery.Refineries

/**
 * /refinery | /rf
 *
 * create <id> <interval>     — place dropper at feet (e.g. 2h, 30m)
 * setoutput <id> <amount>    — while holding the output item (custom items OK)
 * clearoutputs <id>
 * clearbuffer <id>
 * list
 * remove <id>
 * info <id>
 */
class RefineryCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "create", "place" -> cmdCreate(sender, args)
            "setoutput", "output", "addoutput" -> cmdSetOutput(sender, args)
            "clearoutputs" -> cmdClearOutputs(sender, args)
            "clearbuffer" -> cmdClearBuffer(sender, args)
            "list" -> cmdList(sender)
            "remove", "delete" -> cmdRemove(sender, args)
            "info" -> cmdInfo(sender, args)
            else -> printHelp(sender)
        }
        return true
    }

    private fun requireAdmin(sender: CommandSender): Boolean {
        if (sender.hasPermission("nodes.admin") || sender.isOp) return true
        Message.error(sender, "No permission")
        return false
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Refineries]")
        Message.print(sender, "/refinery create <id> <interval>${ChatColor.WHITE}: place dropper (e.g. 2h)")
        Message.print(sender, "/refinery setoutput <id> <amount>${ChatColor.WHITE}: hold item, add as output")
        Message.print(sender, "/refinery clearoutputs <id>")
        Message.print(sender, "/refinery clearbuffer <id>")
        Message.print(sender, "/refinery list | info <id> | remove <id>")
    }

    private fun cmdCreate(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /refinery create <id> <interval>")
            return
        }
        val id = args[1]
        val ms = Refineries.parseDurationMs(args[2])
        if (ms == null) {
            Message.error(sender, "Invalid interval (use 2h, 30m, 90m, …)")
            return
        }
        val result = Refineries.create(id, sender.location.block, ms)
        if (result.isSuccess) {
            Message.print(
                sender,
                "${ChatColor.GREEN}Created refinery \"$id\" interval=${args[2]}. " +
                    "Hold an item and run /refinery setoutput $id <amount>",
            )
        } else {
            Message.error(sender, result.exceptionOrNull()?.message ?: "failed")
        }
    }

    private fun cmdSetOutput(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /refinery setoutput <id> <amount>  (hold the item)")
            return
        }
        val id = args[1]
        val amount = args[2].toIntOrNull()
        if (amount == null || amount < 1) {
            Message.error(sender, "Amount must be a positive integer")
            return
        }
        val held = sender.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            Message.error(sender, "Hold the output item in your main hand")
            return
        }
        val result = Refineries.setOutput(id, held, amount)
        if (result.isSuccess) {
            val name = held.itemMeta?.displayName?.takeIf { it.isNotBlank() } ?: held.type.name
            Message.print(sender, "${ChatColor.GREEN}Added output to \"$id\": ${amount}x $name")
            Message.print(sender, "${ChatColor.GRAY}Run again with another item to add more outputs.")
        } else {
            Message.error(sender, result.exceptionOrNull()?.message ?: "failed")
        }
    }

    private fun cmdClearOutputs(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (args.size < 2) {
            Message.error(sender, "Usage: /refinery clearoutputs <id>")
            return
        }
        if (Refineries.clearOutputs(args[1])) {
            Message.print(sender, "${ChatColor.GREEN}Cleared outputs for ${args[1]}")
        } else {
            Message.error(sender, "Unknown refinery")
        }
    }

    private fun cmdClearBuffer(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (args.size < 2) {
            Message.error(sender, "Usage: /refinery clearbuffer <id>")
            return
        }
        if (Refineries.clearBuffer(args[1])) {
            Message.print(sender, "${ChatColor.GREEN}Cleared buffer for ${args[1]}")
        } else {
            Message.error(sender, "Unknown refinery")
        }
    }

    private fun cmdRemove(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (args.size < 2) {
            Message.error(sender, "Usage: /refinery remove <id>")
            return
        }
        if (Refineries.remove(args[1])) {
            Message.print(sender, "${ChatColor.GREEN}Removed refinery ${args[1]}")
        } else {
            Message.error(sender, "Unknown refinery")
        }
    }

    private fun cmdList(sender: CommandSender) {
        if (Refineries.byId.isEmpty()) {
            Message.print(sender, "No refineries")
            return
        }
        for (r in Refineries.byId.values.sortedBy { it.id }) {
            val next = if (r.outputs.isEmpty()) {
                "no outputs"
            } else if (r.isReady()) {
                "READY (${r.buffer.size} stacks)"
            } else {
                "next ${Refineries.formatDuration(r.msUntilNext())}"
            }
            Message.print(
                sender,
                "  ${r.id} @ ${r.world} ${r.x},${r.y},${r.z} every ${Refineries.formatDuration(r.intervalMs)} — $next",
            )
        }
    }

    private fun cmdInfo(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /refinery info <id>")
            return
        }
        val r = Refineries.byId[args[1]]
        if (r == null) {
            Message.error(sender, "Unknown refinery")
            return
        }
        Message.print(sender, "${ChatColor.GOLD}Refinery ${r.id}")
        Message.print(sender, "  Location: ${r.world} ${r.x},${r.y},${r.z}")
        Message.print(sender, "  Interval: ${Refineries.formatDuration(r.intervalMs)}")
        Message.print(sender, "  Next: ${Refineries.formatDuration(r.msUntilNext())}")
        Message.print(sender, "  Buffer stacks: ${r.buffer.size}")
        Message.print(sender, "  Outputs (${r.outputs.size}):")
        r.outputs.forEachIndexed { i, out ->
            val item = out.toItemStack()
            val name = item?.itemMeta?.displayName?.takeIf { !it.isNullOrBlank() }
                ?: item?.type?.name
                ?: "?"
            Message.print(sender, "    ${i + 1}. ${out.amount}x $name")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return listOf(
                "create",
                "setoutput",
                "clearoutputs",
                "clearbuffer",
                "list",
                "remove",
                "info",
                "help",
            ).filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 &&
            args[0].lowercase() in listOf(
                "setoutput",
                "clearoutputs",
                "clearbuffer",
                "remove",
                "info",
                "output",
                "addoutput",
            )
        ) {
            return Refineries.byId.keys.filter { it.lowercase().startsWith(args[1].lowercase()) }.sorted()
        }
        return listOf()
    }
}
