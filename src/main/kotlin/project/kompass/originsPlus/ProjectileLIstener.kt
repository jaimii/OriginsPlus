package project.kompass.originsPlus.listener

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.ShulkerBullet
import org.bukkit.entity.SmallFireball
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import project.kompass.originsPlus.OriginsPlus
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.HashMap
import java.util.UUID

class ProjectileListener(private val plugin: OriginsPlus) : Listener {

    private val projectileCooldowns = HashMap<UUID, Long>()
    private val flightCooldowns = HashMap<UUID, Long>()

    private val levitationTasks = HashMap<UUID, BukkitTask>()
    private val particleTasks = HashMap<UUID, BukkitTask>()
    private val flightCooldownTasks = HashMap<UUID, BukkitTask>()

    private fun sendActionBar(player: Player, message: String) {
        val component = LegacyComponentSerializer.legacySection().deserialize(message)
        player.sendActionBar(component)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        if (!player.isSneaking) return

        val handItem = player.inventory.itemInMainHand
        if (!handItem.type.isAir) return

        if (event.hand == EquipmentSlot.OFF_HAND) return

        val playerUUID = player.uniqueId
        val currentTime = System.currentTimeMillis()

        // --- TRIGGER: Shift + Left-Click (Projectiles) ---
        if (event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK) {

            // Shulk Ability (Shulker Bullet)
            if (plugin.hasOrigin(player, "Shulk")) {
                val cooldown = plugin.config.getLong("cooldowns.shulk-bullet-millis", 5000)
                if (isOnCooldown(player, projectileCooldowns, cooldown, currentTime)) return

                val bullet = player.launchProjectile(ShulkerBullet::class.java)
                bullet.shooter = player

                val target = getTargetInCone(player)
                if (target != null) {
                    bullet.target = target
                }

                player.world.playSound(player.location, Sound.ENTITY_SHULKER_SHOOT, 1.0f, 1.0f)
                projectileCooldowns[playerUUID] = currentTime
                event.isCancelled = true
            }

            // Blazeborn Ability (Successive Burst of 3 Fireballs)
            else if (plugin.hasOrigin(player, "Blazeborn")) {
                val cooldown = plugin.config.getLong("cooldowns.blaze-fireball-millis", 3000)
                if (isOnCooldown(player, projectileCooldowns, cooldown, currentTime)) return

                shootFireballBurst(player)

                projectileCooldowns[playerUUID] = currentTime
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onPlayerJump(event: PlayerJumpEvent) {
        val player = event.player
        if (!plugin.hasOrigin(player, "Blazeborn")) return
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return

        val playerUUID = player.uniqueId
        val currentTime = System.currentTimeMillis()

        // Flight activation requires sneaking (Shift + Jump)
        if (player.isSneaking) {
            if (plugin.slowFallingPlayers.contains(playerUUID)) {
                player.sendMessage("§cYou cannot fly while slow falling to the ground!")
                return
            }

            // Check flight cooldown
            val cooldown = plugin.config.getLong("cooldowns.blaze-flight-millis", 5000)
            val lastUsed = flightCooldowns[playerUUID]
            if (lastUsed != null && currentTime - lastUsed < cooldown) {
                val secondsLeft = "%.1f".format((cooldown - (currentTime - lastUsed)) / 1000.0)
                sendActionBar(player, "§cThrusters recharging! ${secondsLeft}s remaining.")
                return
            }

            event.isCancelled = true // Intercept native jump trigger so custom levitation handles upward velocity
            startBlazebornLevitation(player)
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val uuid = player.uniqueId

        // Check if slow falling player has touched solid ground
        if (plugin.slowFallingPlayers.contains(uuid)) {
            @Suppress("DEPRECATION")
            val grounded = player.isOnGround

            if (grounded) {
                plugin.slowFallingPlayers.remove(uuid)
                player.removePotionEffect(PotionEffectType.SLOW_FALLING)

                player.world.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.75f, 1.5f)

                // Flight cooldown begins ONLY now that the player has touched the ground
                val startTime = System.currentTimeMillis()
                flightCooldowns[uuid] = startTime

                val cooldown = plugin.config.getLong("cooldowns.blaze-flight-millis", 5000)
                startFlightCooldownAnimation(player, startTime, cooldown)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId

        // Safety cleanup to prevent memory leaks and lingering attributes
        plugin.activeLevitationPlayers.remove(uuid)
        particleTasks.remove(uuid)?.cancel()
        levitationTasks.remove(uuid)?.cancel()
        flightCooldownTasks.remove(uuid)?.cancel()

        if (plugin.slowFallingPlayers.contains(uuid)) {
            plugin.slowFallingPlayers.remove(uuid)
            event.player.removePotionEffect(PotionEffectType.SLOW_FALLING)
            // Put on immediate fallback cooldown to prevent bypass on rejoin
            flightCooldowns[uuid] = System.currentTimeMillis()
        }
    }

    private fun shootFireballBurst(player: Player) {
        // Fireball 1
        spawnFireball(player)

        // Fireball 2
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                spawnFireball(player)
            }
        }, 4L)

        // Fireball 3
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                spawnFireball(player)
            }
        }, 8L)
    }

    private fun spawnFireball(player: Player) {
        val fireball = player.launchProjectile(SmallFireball::class.java)
        fireball.shooter = player
        val direction = player.location.direction.multiply(1.5)
        fireball.velocity = direction
        player.world.playSound(player.location, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.0f)
    }

    private fun startBlazebornLevitation(player: Player) {
        val uuid = player.uniqueId
        plugin.activeLevitationPlayers.add(uuid)

        player.sendMessage("§6Flame-thrusters active! Launching upward.")
        player.world.playSound(player.location, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f)

        // Apply a strong Levitation effect (Amplifier 6 is a rapid upward thrust)
        // Duration: 3 seconds (60 ticks). ambient=false, particles=false, icon=false to hide effects
        val levitationEffect = PotionEffect(PotionEffectType.LEVITATION, 60, 6, false, false, false)
        player.addPotionEffect(levitationEffect)

        // Spawn Jetpack particle stream (every 2 ticks)
        val particleTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (player.isOnline && plugin.activeLevitationPlayers.contains(uuid)) {

                // SUSTAIN FLIGHT CHECK: Verify if the player is still holding down the Spacebar (Jump key)
                val holdingJump = try {
                    player.currentInput.isJump
                } catch (t: Throwable) {
                    // Safe fallback if running on an older version of Paper without the modern Input API
                    true
                }

                if (!holdingJump) {
                    stopLevitationAndStartGlide(player)
                    return@Runnable
                }

                val loc = player.location
                val particleLoc = loc.clone().add(0.0, 0.1, 0.0)

                player.world.spawnParticle(Particle.FLAME, particleLoc, 10, 0.15, 0.1, 0.15, 0.02)
                player.world.spawnParticle(Particle.LAVA, particleLoc, 2, 0.05, 0.0, 0.05, 0.0)

                if (Math.random() < 0.2) {
                    player.world.playSound(player.location, Sound.BLOCK_FIRE_AMBIENT, 0.5f, 1.5f)
                }
            }
        }, 0L, 2L)
        particleTasks[uuid] = particleTask

        // Stop flight task after 3 seconds (60 ticks)
        val levTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            stopLevitationAndStartGlide(player)
        }, 60L)
        levitationTasks[uuid] = levTask
    }

    private fun stopLevitationAndStartGlide(player: Player) {
        val uuid = player.uniqueId

        particleTasks.remove(uuid)?.cancel()
        levitationTasks.remove(uuid)?.cancel()

        if (player.isOnline) {
            player.removePotionEffect(PotionEffectType.LEVITATION)

            plugin.activeLevitationPlayers.remove(uuid)
            plugin.slowFallingPlayers.add(uuid)

            // Apply virtually infinite Slow Falling (cleaned up as soon as they hit the ground)
            // ambient=false, particles=false, icon=false to hide swirl particles and status icon
            val slowFallEffect = PotionEffect(PotionEffectType.SLOW_FALLING, 72000, 0, false, false, false)
            player.addPotionEffect(slowFallEffect)

            player.sendMessage("§eThrusters offline. Gliding to landing zone...")
            player.world.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f)
        } else {
            plugin.activeLevitationPlayers.remove(uuid)
        }
    }

    private fun startFlightCooldownAnimation(player: Player, startTime: Long, cooldownDuration: Long) {
        val uuid = player.uniqueId
        flightCooldownTasks[uuid]?.cancel()

        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                flightCooldownTasks.remove(uuid)?.cancel()
                return@Runnable
            }

            val currentTime = System.currentTimeMillis()
            val elapsed = currentTime - startTime

            // If cooldown is fully complete
            if (elapsed >= cooldownDuration) {
                sendActionBar(player, "§a§l✔ Thrusters Recharged! §r§7(Shift + Jump to fly)")
                player.world.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.4f, 2.0f)

                flightCooldownTasks.remove(uuid)?.cancel()
                return@Runnable
            }

            // Design progress bar: Total of 10 blocks
            val totalBars = 10
            val progressRatio = (elapsed.toDouble() / cooldownDuration).coerceIn(0.0, 1.0)
            val filledBars = (progressRatio * totalBars).toInt()
            val emptyBars = totalBars - filledBars

            val barRepresentation = "§a" + "█".repeat(filledBars) + "§7" + "░".repeat(emptyBars)
            val secondsRemaining = "%.1f".format((cooldownDuration - elapsed) / 1000.0)

            sendActionBar(player, "§6Thruster Charge: §e[${barRepresentation}§e] §b${secondsRemaining}s")
        }, 0L, 2L)

        flightCooldownTasks[uuid] = task
    }

    // Used exclusively for projectiles (Shulk / Blazeborn fireballs).
    private fun isOnCooldown(player: Player, cooldownMap: HashMap<UUID, Long>, cooldown: Long, currentTime: Long): Boolean {
        val uuid = player.uniqueId
        val lastUsed = cooldownMap[uuid]
        if (lastUsed != null) {
            if (currentTime - lastUsed < cooldown) {
                val secondsLeft = ((cooldown - (currentTime - lastUsed)) / 1000) + 1
                player.sendMessage("§cAbility on cooldown! ${secondsLeft}s remaining.") // Kept in normal chat
                return true
            }
        }
        return false
    }

    private fun getTargetInCone(player: Player): LivingEntity? {
        var closestTarget: LivingEntity? = null
        var closestDistance = 30.0
        val eyeLoc = player.eyeLocation
        val direction = eyeLoc.direction.normalize()

        for (entity in player.getNearbyEntities(30.0, 30.0, 30.0)) {
            if (entity is LivingEntity && entity != player) {
                val toTarget = entity.location.toVector().subtract(eyeLoc.toVector())
                val dot = toTarget.normalize().dot(direction)

                if (dot > 0.85) {
                    val dist = entity.location.distance(player.location)
                    if (dist < closestDistance) {
                        closestDistance = dist
                        closestTarget = entity
                    }
                }
            }
        }
        return closestTarget
    }
}