package project.kompass.originsPlus

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class OriginsPlus : JavaPlugin() {

    private var placeholderApiEnabled = false

    override fun onEnable() {
        saveDefaultConfig()

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderApiEnabled = true
            logger.info("PlaceholderAPI detected. Using placeholder hooks for Origin detection.")
        } else {
            logger.warning("PlaceholderAPI not found. Falling back to permission-based origin detection.")
        }

        // Register event handlers
        server.pluginManager.registerEvents(EnderianListener(this), this)
        server.pluginManager.registerEvents(ProjectileListener(this), this)

        logger.info("OriginsPlus successfully enabled for 1.21.11.")
    }

    /**
     * Checks if a player has a specific origin.
     * Hooks into PlaceholderAPI or falls back to standard permissions.
     */
    fun hasOrigin(player: Player, originName: String): Boolean {
        if (placeholderApiEnabled) {
            val origin = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%origin%")
            if (origin != null && origin.equals(originName, ignoreCase = true)) {
                return true
            }
            val altOrigin = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%originsreborn_origin%")
            if (altOrigin != null && altOrigin.equals(originName, ignoreCase = true)) {
                return true
            }
        }

        // Permission fallback (e.g., "originsaddon.origin.enderian")
        return player.hasPermission("originsaddon.origin.${originName.lowercase()}")
    }
}


