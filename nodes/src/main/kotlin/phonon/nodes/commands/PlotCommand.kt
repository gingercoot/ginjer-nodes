package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.PlotAction
import phonon.nodes.objects.PlotRule
import phonon.nodes.objects.PlotSubjectType
import phonon.nodes.plot.Plots

/**
 * /plot | /p
 * select, create, delete, owner, permissions, info, list
 */
class PlotCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only")
            return true
        }
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> sendHelp(sender)
            "select" -> cmdSelect(sender)
            "create" -> cmdCreate(sender, args)
            "delete" -> cmdDelete(sender, args)
            "owner" -> cmdOwner(sender, args)
            "permissions", "permission", "perm", "perms" -> cmdPermissions(sender, args)
            "info" -> cmdInfo(sender, args)
            "list" -> cmdList(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(p: Player) {
        if (!p.hasPermission("nodes.command.plot")) {
            Message.error(p, "No permission")
            return
        }
        Message.print(p, "${ChatColor.BOLD}[Nodes] Plot commands:")
        Message.print(p, "/plot select${ChatColor.WHITE}: Left-click pos1, right-click pos2 (again cancels)")
        if (p.hasPermission("nodes.command.plot.create")) {
            Message.print(p, "/plot create <name>${ChatColor.WHITE}: Create plot from selection (officer/leader)")
        }
        if (p.hasPermission("nodes.command.plot.delete")) {
            Message.print(p, "/plot delete <name>${ChatColor.WHITE}: Delete plot (owner or leader)")
        }
        if (p.hasPermission("nodes.command.plot.owner")) {
            Message.print(p, "/plot owner <plot> <player>${ChatColor.WHITE}: Transfer ownership")
        }
        if (p.hasPermission("nodes.command.plot.permissions")) {
            Message.print(
                p,
                "/plot permissions <plot> <subject> [player] <action> <allow|deny>${ChatColor.WHITE}: Set rules",
            )
        }
        Message.print(p, "/plot info <name>${ChatColor.WHITE}: Plot details")
        Message.print(p, "/plot list${ChatColor.WHITE}: Plots in your town")
        if (p.hasPermission("nodes.command.plot.permissions")) {
            Message.print(p, "${ChatColor.GRAY}Subjects: person, town, othertown, nation, ally, outsider, officers")
            Message.print(p, "${ChatColor.GRAY}Actions: build, destroy, interact, item, vehicles, all")
        }
    }

    private fun allowedSubcommands(p: Player): List<String> {
        val subs = mutableListOf("help", "select")
        for (s in listOf("create", "delete", "owner", "permissions")) {
            if (p.hasPermission("nodes.command.plot.$s")) subs.add(s)
        }
        subs.addAll(listOf("info", "list"))
        return subs
    }

    private fun cmdSelect(p: Player) {
        if (!p.hasPermission("nodes.command.plot")) {
            Message.error(p, "No permission")
            return
        }
        val on = Plots.toggleSelect(p)
        if (on) {
            Message.print(p, "${ChatColor.GREEN}Plot select ON. Left-click = pos1, right-click = pos2.")
        } else {
            Message.print(p, "${ChatColor.YELLOW}Plot select cancelled.")
        }
    }

    private fun cmdCreate(p: Player, args: Array<out String>) {
        if (!p.hasPermission("nodes.command.plot.create")) {
            Message.error(p, "No permission")
            return
        }
        if (args.size < 2) {
            Message.error(p, "Usage: /p create <name>")
            return
        }
        val name = args[1]
        val resident = Nodes.getResident(p) ?: run {
            Message.error(p, "You are not in a town")
            return
        }
        val town = resident.town ?: run {
            Message.error(p, "You are not in a town")
            return
        }
        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(p, "Only officers or the leader can create plots")
            return
        }
        val sel = Plots.getSelection(p)
        if (sel == null || !sel.isComplete()) {
            Message.error(p, "Select two corners first with /p select")
            return
        }
        val bounds = sel.bounds() ?: run {
            Message.error(p, "Selection must be in the same world")
            return
        }
        val (worldName, min, max) = bounds
        if (!Plots.selectionInTown(town, worldName, min, max)) {
            Message.error(p, "Selection must be entirely inside your town territories")
            return
        }
        val err = Plots.create(name, town, worldName, min, max, p)
        if (err != null) {
            Message.error(p, err)
            return
        }
        Plots.clearSelect(p)
        Message.print(
            p,
            "${ChatColor.GREEN}Plot '$name' created. You are owner.",
        )
    }

    private fun cmdDelete(p: Player, args: Array<out String>) {
        if (!p.hasPermission("nodes.command.plot.delete")) {
            Message.error(p, "No permission")
            return
        }
        if (args.size < 2) {
            Message.error(p, "Usage: /p delete <name>")
            return
        }
        val plot = Plots.get(args[1]) ?: run {
            Message.error(p, "Plot not found")
            return
        }
        val resident = Nodes.getResident(p)
        val town = Nodes.getTownFromName(plot.townName)
        val isOwner = plot.ownerUuid == p.uniqueId
        val isLeader = resident != null && town != null && resident === town.leader
        if (!isOwner && !isLeader) {
            Message.error(p, "Only the plot owner or town leader can delete this plot")
            return
        }
        Plots.delete(plot.name)
        Message.print(p, "${ChatColor.GREEN}Plot '${plot.name}' deleted")
    }

    private fun cmdOwner(p: Player, args: Array<out String>) {
        if (!p.hasPermission("nodes.command.plot.owner")) {
            Message.error(p, "No permission")
            return
        }
        if (args.size < 3) {
            Message.error(p, "Usage: /p owner <plot> <player>")
            return
        }
        val plot = Plots.get(args[1]) ?: run {
            Message.error(p, "Plot not found")
            return
        }
        if (plot.ownerUuid != p.uniqueId) {
            Message.error(p, "Only the current owner can transfer ownership")
            return
        }
        val target = Nodes.getResidentFromName(args[2]) ?: run {
            Message.error(p, "Player not found (must have joined the server)")
            return
        }
        if (!Plots.setOwner(plot, target)) {
            Message.error(p, "New owner must be a member of town ${plot.townName}")
            return
        }
        Message.print(p, "${ChatColor.GREEN}Plot '${plot.name}' owner is now ${target.name}")
    }

    private fun cmdPermissions(p: Player, args: Array<out String>) {
        if (!p.hasPermission("nodes.command.plot.permissions")) {
            Message.error(p, "No permission")
            return
        }
        // /p permissions <plot> <subject> [personName] <action> <allow|deny>
        if (args.size < 5) {
            Message.error(p, "Usage: /p permissions <plot> <subject> [player] <action> <allow|deny>")
            return
        }
        val plot = Plots.get(args[1]) ?: run {
            Message.error(p, "Plot not found")
            return
        }
        if (plot.ownerUuid != p.uniqueId) {
            Message.error(p, "Only the plot owner can edit permissions")
            return
        }

        val subject = PlotSubjectType.fromString(args[2]) ?: run {
            Message.error(p, "Unknown subject. Use: person, town, othertown, nation, ally, outsider, officers")
            return
        }

        var idx = 3
        var personUuid: java.util.UUID? = null
        var personName: String? = null
        if (subject == PlotSubjectType.PERSON) {
            if (args.size < 6) {
                Message.error(p, "Usage: /p permissions <plot> person <player> <action> <allow|deny>")
                return
            }
            val res = Nodes.getResidentFromName(args[3])
            val online = org.bukkit.Bukkit.getPlayerExact(args[3])
            personName = args[3]
            personUuid = res?.uuid ?: online?.uniqueId
            if (personUuid == null) {
                Message.error(p, "Player not found")
                return
            }
            idx = 4
        }

        val action = PlotAction.fromString(args[idx]) ?: run {
            Message.error(p, "Unknown action. Use: build, destroy, interact, item, vehicles, all")
            return
        }
        val allowStr = args.getOrNull(idx + 1)?.lowercase() ?: run {
            Message.error(p, "Use allow or deny")
            return
        }
        val allow = when (allowStr) {
            "allow", "true", "yes" -> true
            "deny", "false", "no" -> false
            else -> {
                Message.error(p, "Use allow or deny")
                return
            }
        }

        plot.rules.removeAll {
            it.subject == subject &&
                it.action == action &&
                (subject != PlotSubjectType.PERSON || it.personUuid == personUuid)
        }
        plot.rules.add(
            PlotRule(
                subject = subject,
                personUuid = personUuid,
                personName = personName,
                action = action,
                allow = allow,
            ),
        )
        Plots.save()
        val who = if (subject == PlotSubjectType.PERSON) personName else subject.name.lowercase()
        Message.print(
            p,
            "${ChatColor.GREEN}Plot '${plot.name}': $who ${action.name.lowercase()} = ${if (allow) "allow" else "deny"}",
        )
    }

    private fun cmdInfo(p: Player, args: Array<out String>) {
        if (!p.hasPermission("nodes.command.plot")) {
            Message.error(p, "No permission")
            return
        }
        if (args.size < 2) {
            Message.error(p, "Usage: /p info <name>")
            return
        }
        val plot = Plots.get(args[1]) ?: run {
            Message.error(p, "Plot not found")
            return
        }
        Message.print(p, "${ChatColor.GOLD}Plot ${plot.name}")
        Message.print(p, "  Town: ${plot.townName}")
        Message.print(p, "  Owner: ${plot.ownerName}")
        Message.print(
            p,
            "  Bounds: (${plot.min.x},${plot.min.y},${plot.min.z}) -> (${plot.max.x},${plot.max.y},${plot.max.z}) @ ${plot.worldName}",
        )
        if (plot.rules.isEmpty()) {
            Message.print(p, "  Rules: ${ChatColor.GRAY}none (all denied)")
        } else {
            Message.print(p, "  Rules:")
            for (r in plot.rules) {
                val who = when (r.subject) {
                    PlotSubjectType.PERSON -> r.personName ?: r.personUuid.toString()
                    else -> r.subject.name.lowercase()
                }
                Message.print(
                    p,
                    "    $who ${r.action.name.lowercase()} ${if (r.allow) "${ChatColor.GREEN}allow" else "${ChatColor.RED}deny"}",
                )
            }
        }
    }

    private fun cmdList(p: Player) {
        if (!p.hasPermission("nodes.command.plot")) {
            Message.error(p, "No permission")
            return
        }
        val town = Nodes.getResident(p)?.town
        if (town == null) {
            Message.error(p, "You are not in a town")
            return
        }
        val list = Plots.plotsInTown(town.name)
        if (list.isEmpty()) {
            Message.print(p, "No plots in ${town.name}")
            return
        }
        Message.print(p, "${ChatColor.GOLD}Plots in ${town.name}:")
        for (plot in list) {
            Message.print(p, "  ${plot.name} (owner: ${plot.ownerName})")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (sender !is Player) return emptyList()

        val sub = allowedSubcommands(sender)

        if (args.size == 1) {
            return sub.filter { it.startsWith(args[0], ignoreCase = true) }
        }

        val cmd = args[0].lowercase()
        if (cmd !in sub) return emptyList()

        if (args.size == 2 && cmd in listOf("delete", "owner", "permissions", "info")) {
            return Plots.all()
                .map { it.name }
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .sorted()
        }

        if (args.size == 2 && cmd == "create") {
            return emptyList()
        }

        if (args.size == 3 && cmd == "owner") {
            return org.bukkit.Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[2], ignoreCase = true) }
                .sorted()
        }

        if (cmd == "permissions") {
            when (args.size) {
                3 -> return listOf(
                    "person",
                    "town",
                    "othertown",
                    "nation",
                    "ally",
                    "outsider",
                    "officers",
                ).filter { it.startsWith(args[2], ignoreCase = true) }

                4 -> {
                    if (args[2].equals("person", ignoreCase = true)) {
                        return org.bukkit.Bukkit.getOnlinePlayers()
                            .map { it.name }
                            .filter { it.startsWith(args[3], ignoreCase = true) }
                            .sorted()
                    }
                    return listOf("build", "destroy", "interact", "item", "vehicles", "all")
                        .filter { it.startsWith(args[3], ignoreCase = true) }
                }

                5 -> {
                    if (args[2].equals("person", ignoreCase = true)) {
                        return listOf("build", "destroy", "interact", "item", "vehicles", "all")
                            .filter { it.startsWith(args[4], ignoreCase = true) }
                    }
                    return listOf("allow", "deny")
                        .filter { it.startsWith(args[4], ignoreCase = true) }
                }

                6 -> {
                    if (args[2].equals("person", ignoreCase = true)) {
                        return listOf("allow", "deny")
                            .filter { it.startsWith(args[5], ignoreCase = true) }
                    }
                }
            }
        }

        return emptyList()
    }
}
