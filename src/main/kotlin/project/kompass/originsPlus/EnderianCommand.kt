package project.kompass.originsPlus.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import project.kompass.originsPlus.OriginsPlus

class EnderianCommand(private val plugin: OriginsPlus) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cOnly players can execute this command.")
            return true
        }

        if (!plugin.hasOrigin(sender, "Enderian")) {
            sender.sendMessage("§cYou must have the Enderian origin to use this command.")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("§7Usage: §e/enderian toggle")
            return true
        }

        if (args[0].equals("toggle", ignoreCase = true)) {
            val isInstant = plugin.toggleTeleportMode(sender)
            val modeString = if (isInstant) "§aINSTANT (Blink)§7" else "§bPEARL (Classic)§7"
            sender.sendMessage("§7Your teleportation mode has been set to: $modeString")
            return true
        }

        sender.sendMessage("§cUnknown argument. Usage: §e/enderian toggle")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        if (sender !is Player) return null
        if (!plugin.hasOrigin(sender, "Enderian")) return null

        if (args.size == 1) {
            return listOf("toggle").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}