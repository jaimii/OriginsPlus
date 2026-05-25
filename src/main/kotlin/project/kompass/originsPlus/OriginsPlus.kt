package project.kompass.originsPlus

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import project.kompass.originsPlus.command.EnderianCommand
import project.kompass.originsPlus.listener.EnderianListener
import project.kompass.originsPlus.listener.ProjectileListener
import java.io.File
import java.util.HashSet
import java.util.UUID

class OriginsPlus : JavaPlugin() {

    private var placeholderApiEnabled = false
    private val instantTeleportPlayers = HashSet<UUID>()

    // Shared flight / levitation state
    val activeLevitationPlayers = HashSet<UUID>()
    val slowFallingPlayers = HashSet<UUID>()

    private val dataConfig = YamlConfiguration()
    private val dataFile by lazy { File(dataFolder, "player_data.yml") }

    override fun onEnable() {
        saveDefaultConfig()
        loadPlayerData()

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderApiEnabled = true
            logger.info("PlaceholderAPI detected. Using placeholder hooks for Origin detection.")
        } else {
            logger.warning("PlaceholderAPI not found. Falling back to permission-based origin detection.")
        }

        // Register event handlers
        server.pluginManager.registerEvents(EnderianListener(this), this)
        server.pluginManager.registerEvents(ProjectileListener(this), this)

        // Register commands
        getCommand("enderian")?.apply {
            setExecutor(EnderianCommand(this@OriginsPlus))
            setTabCompleter(EnderianCommand(this@OriginsPlus))
        }

        logger.info("OriginsPlus successfully enabled for 1.21.11.")
    }

    override fun onDisable() {
        savePlayerData()

        // Clean up any levitating players on reload/stop
        for (uuid in activeLevitationPlayers) {
            val player = Bukkit.getPlayer(uuid)
            player?.removePotionEffect(PotionEffectType.LEVITATION)
        }

        // Clean up slow falling states
        for (uuid in slowFallingPlayers) {
            val player = Bukkit.getPlayer(uuid)
            player?.removePotionEffect(PotionEffectType.SLOW_FALLING)
        }

        logger.info("OriginsPlus successfully disabled.")
    }

    fun isInstantTeleportEnabled(player: Player): Boolean {
        return instantTeleportPlayers.contains(player.uniqueId)
    }

    fun toggleTeleportMode(player: Player): Boolean {
        val uuid = player.uniqueId
        return if (instantTeleportPlayers.contains(uuid)) {
            instantTeleportPlayers.remove(uuid)
            false
        } else {
            instantTeleportPlayers.add(uuid)
            true
        }
    }

    private fun loadPlayerData() {
        if (!dataFile.exists()) return
        try {
            dataConfig.load(dataFile)
            val list = dataConfig.getStringList("instant-teleport-players")
            for (uuidStr in list) {
                try {
                    instantTeleportPlayers.add(UUID.fromString(uuidStr))
                } catch (ignored: IllegalArgumentException) {}
            }
        } catch (e: Exception) {
            logger.severe("Could not load player_data.yml: ${e.message}")
        }
    }

    private fun savePlayerData() {
        try {
            val list = instantTeleportPlayers.map { it.toString() }
            dataConfig.set("instant-teleport-players", list)
            if (!dataFolder.exists()) {
                dataFolder.mkdirs()
            }
            dataConfig.save(dataFile)
        } catch (e: Exception) {
            logger.severe("Could not save player_data.yml: ${e.message}")
        }
    }

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
        return player.hasPermission("originsplus.origin.${originName.lowercase()}")
    }
}