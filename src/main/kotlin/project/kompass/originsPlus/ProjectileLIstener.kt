package project.kompass.originsPlus.listener

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
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
                if (isOnCooldown(player, projectileCooldowns, currentTime)) return
                val cooldown = plugin.config.getLong("cooldowns.shulk-bullet-millis", 5000)

                val bullet = player.launchProjectile(ShulkerBullet::class.java)
                bullet.shooter = player

                val target = getTargetInCone(player)
                if (target != null) {
                    bullet.target = target
                }

                player.world.playSound(player.location, Sound.ENTITY_SHULKER_SHOOT, 1.0f, 1.0f)
                projectileCooldowns[playerUUID] = currentTime + cooldown
                event.isCancelled = true
            }

            // Blazeborn Ability (Successive Burst of 3 Fireballs)
            else if (plugin.hasOrigin(player, "Blazeborn")) {
                if (isOnCooldown(player, projectileCooldowns, currentTime)) return

                // Set cooldown to 10 seconds if the player is in rain or snow
                val isRaining = isInRain(player)
                val cooldown = if (isRaining) 10000L else plugin.config.getLong("cooldowns.blaze-fireball-millis", 3000)

                shootFireballBurst(player)

                projectileCooldowns[playerUUID] = currentTime + cooldown
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

            // Check flight cooldown using the expiration timestamp
            val expireTime = flightCooldowns[playerUUID]
            if (expireTime != null && currentTime < expireTime) {
                val secondsLeft = "%.1f".format((expireTime - currentTime) / 1000.0)
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

                // Set flight cooldown to 25 seconds if landing in rain or snow
                val isRaining = isInRain(player)
                val cooldown = if (isRaining) 25000L else plugin.config.getLong("cooldowns.blaze-flight-millis", 5000)

                flightCooldowns[uuid] = startTime + cooldown

                startFlightCooldownAnimation(player, startTime, cooldown)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId

        plugin.activeLevitationPlayers.remove(uuid)
        particleTasks.remove(uuid)?.cancel()
        levitationTasks.remove(uuid)?.cancel()
        flightCooldownTasks.remove(uuid)?.cancel()

        if (plugin.slowFallingPlayers.contains(uuid)) {
            plugin.slowFallingPlayers.remove(uuid)
            event.player.removePotionEffect(PotionEffectType.SLOW_FALLING)
            // Put on immediate 5s fallback cooldown to prevent bypass on rejoin
            flightCooldowns[uuid] = System.currentTimeMillis() + 5000
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

        val levitationEffect = PotionEffect(PotionEffectType.LEVITATION, 60, 6, false, false, false)
        player.addPotionEffect(levitationEffect)

        val particleTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (player.isOnline && plugin.activeLevitationPlayers.contains(uuid)) {

                val holdingJump = try {
                    player.currentInput.isJump
                } catch (t: Throwable) {
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

            if (elapsed >= cooldownDuration) {
                sendActionBar(player, "§a§l✔ Thrusters Recharged! §r§7(Shift + Jump to fly)")
                player.world.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.4f, 2.0f)

                flightCooldownTasks.remove(uuid)?.cancel()
                return@Runnable
            }

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

    private fun isOnCooldown(player: Player, cooldownMap: HashMap<UUID, Long>, currentTime: Long): Boolean {
        val uuid = player.uniqueId
        val expireTime = cooldownMap[uuid]
        if (expireTime != null && currentTime < expireTime) {
            val secondsLeft = ((expireTime - currentTime) / 1000) + 1
            player.sendMessage("§cAbility on cooldown! ${secondsLeft}s remaining.")
            return true
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

    private fun isInRain(player: Player): Boolean {
        if (player.isInRain) return true

        val vehicle = player.vehicle
        if (vehicle != null && vehicle.isInRain) return true

        val world = player.world
        if (!world.hasStorm()) return false

        return isExposedToRain(player)
    }

    private fun isExposedToRain(player: Player): Boolean {
        val loc = player.eyeLocation
        val world = loc.world ?: return false
        val x = loc.blockX
        val z = loc.blockZ
        val highestBlock = world.getHighestBlockAt(x, z)

        val startY = loc.blockY + 1
        if (highestBlock.y < startY) {
            return true
        }

        for (y in startY..highestBlock.y) {
            val block = world.getBlockAt(x, y, z)
            val type = block.type
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR ||
                type == Material.WATER || type == Material.BUBBLE_COLUMN) {
                continue
            }
            if (type.isSolid) {
                return false
            }
        }
        return true
    }
}