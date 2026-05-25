package project.kompass.originsPlus.listener

import project.kompass.originsPlus.OriginsPlus
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.Enderman
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class EnderianListener(private val plugin: OriginsPlus) : Listener {

    private val spawnerTypeKey = NamespacedKey(plugin, "spawner_mob_type")

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!plugin.hasOrigin(player, "Enderian")) return
        if (player.gameMode == GameMode.CREATIVE) return

        // Ensure player is mining with their empty hand
        val hand = player.inventory.itemInMainHand
        if (!hand.type.isAir) return

        val block = event.block
        if (block.type.hardness < 0) return // Skip bedrock or other unbreakable blocks

        event.isDropItems = false
        event.expToDrop = 0 // Match vanilla Silk Touch (no XP)

        if (block.type == Material.SPAWNER) {
            // --- Special Case: Spawner Type Preservation ---
            val cs = block.state as? CreatureSpawner
            val spawnedType = cs?.spawnedType ?: EntityType.PIG

            val spawnerItem = ItemStack(Material.SPAWNER)
            val meta = spawnerItem.itemMeta
            if (meta != null) {
                // Store the raw entity type inside the Item's PDC to bypass Spigot's BlockStateMeta world bug
                meta.persistentDataContainer.set(spawnerTypeKey, PersistentDataType.STRING, spawnedType.name)

                // Format a clean, non-deprecated display name using Kyori Adventure Components
                val cleanName = spawnedType.name.lowercase().replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } } + " Spawner"
                meta.displayName(Component.text(cleanName, NamedTextColor.YELLOW))

                spawnerItem.itemMeta = meta
            }
            block.world.dropItemNaturally(block.location, spawnerItem)
        } else {
            // --- Standard Blocks: Simulate Silk Touch drops ---
            val silkTool = ItemStack(Material.NETHERITE_PICKAXE).apply {
                addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH, 1)
            }
            val drops = block.getDrops(silkTool, player)

            if (drops.isEmpty()) {
                // Fallback for blocks (like torches or redstone wire) that bypass tool-dependent checks
                val fallbackDrops = block.getDrops(ItemStack(Material.AIR), player)
                for (drop in fallbackDrops) {
                    block.world.dropItemNaturally(block.location, drop)
                }
            } else {
                // Drop standard, clean vanilla items so they stack perfectly
                for (drop in drops) {
                    block.world.dropItemNaturally(block.location, drop)
                }
            }
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced

        // Restore spawner mob metadata upon placement in survival mode
        if (block.type == Material.SPAWNER) {
            val item = event.itemInHand
            val meta = item.itemMeta ?: return

            // Extract the custom spawner metadata from the item container
            val typeName = meta.persistentDataContainer.get(spawnerTypeKey, PersistentDataType.STRING) ?: return
            try {
                val entityType = EntityType.valueOf(typeName)
                val placedState = block.state as? CreatureSpawner ?: return

                placedState.spawnedType = entityType
                placedState.update(true, true) // Force update both the physics and the block state NBT data
            } catch (ignored: IllegalArgumentException) {
                // Fail-safe
            }
        }
    }

    @EventHandler
    fun onEndermanTarget(event: EntityTargetLivingEntityEvent) {
        if (event.entity !is Enderman) return
        val player = event.target as? Player ?: return

        // Endermen will ignore players with either Enderian or Shulk origins
        if (plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Shulk")) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val pearl = event.entity as? EnderPearl ?: return
        val player = pearl.shooter as? Player ?: return

        if (plugin.hasOrigin(player, "Enderian")) {
            if (plugin.isInstantTeleportEnabled(player)) {
                event.isCancelled = true
                performInstantTeleport(player)
            }
        }
    }

    @EventHandler
    fun onPlayerDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return

        // 1. Enderians and Blazeborns rain damage protection
        if (plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Blazeborn")) {
            val cause = event.cause
            if (cause == EntityDamageEvent.DamageCause.CUSTOM ||
                cause == EntityDamageEvent.DamageCause.DROWNING ||
                cause == EntityDamageEvent.DamageCause.MELTING ||
                cause == EntityDamageEvent.DamageCause.FREEZE) { // Added FREEZE to support Origins-Reborn custom rain mapping

                if (hasHelmet(player) && player.isInRain && !player.isInWater && !isStandingInWater(player)) {
                    event.isCancelled = true
                    return
                }
            }
        }

        // 2. Phantoms sun damage protection
        if (plugin.hasOrigin(player, "Phantom")) {
            val cause = event.cause
            if (cause == EntityDamageEvent.DamageCause.FIRE ||
                cause == EntityDamageEvent.DamageCause.FIRE_TICK ||
                cause == EntityDamageEvent.DamageCause.CUSTOM) {

                if (hasHelmet(player) && isInSun(player) && !isNearFireOrLava(player)) {
                    event.isCancelled = true
                    if (player.fireTicks > 0) {
                        player.fireTicks = 0
                    }
                    return
                }
            }
        }
    }

    private fun performInstantTeleport(player: Player) {
        val maxDistance = 45.0
        val rayTraceResult = player.rayTraceBlocks(maxDistance)

        if (rayTraceResult != null && rayTraceResult.hitBlock != null) {
            val startLoc = player.location.clone()

            val targetLoc = rayTraceResult.hitPosition.toLocation(player.world).apply {
                rayTraceResult.hitBlockFace?.direction?.let { direction ->
                    add(direction.multiply(0.5))
                }
                yaw = player.location.yaw
                pitch = player.location.pitch
            }

            player.world.playSound(startLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
            player.world.spawnParticle(Particle.PORTAL, startLoc, 30, 0.5, 1.0, 0.5, 0.1)

            drawParticleTrail(player.eyeLocation, targetLoc)

            player.teleport(targetLoc)

            player.world.playSound(targetLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
            player.world.spawnParticle(Particle.PORTAL, targetLoc, 30, 0.5, 1.0, 0.5, 0.1)
        } else {
            player.sendMessage("§cNo block in sight to teleport to!")
        }
    }

    private fun drawParticleTrail(start: Location, end: Location) {
        val world = start.world ?: return
        val distance = start.distance(end)
        val stepCount = (distance * 2).toInt().coerceAtLeast(1)
        val stepVector = end.toVector().subtract(start.toVector()).normalize().multiply(0.5)

        val currentPoint = start.clone()
        for (i in 0 until stepCount) {
            currentPoint.add(stepVector)
            world.spawnParticle(Particle.PORTAL, currentPoint, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun hasHelmet(player: Player): Boolean {
        val helmet = player.inventory.helmet ?: return false
        return helmet.type != Material.AIR
    }

    private fun isStandingInWater(player: Player): Boolean {
        val blocksToCheck = listOf(player.location.block, player.eyeLocation.block)
        for (block in blocksToCheck) {
            if (block.type == Material.WATER || block.type == Material.BUBBLE_COLUMN) {
                return true
            }
            // Check for waterlogged blocks (stairs, slabs, chests, etc.)
            val blockData = block.blockData
            if (blockData is Waterlogged && blockData.isWaterlogged) {
                return true
            }
        }
        return false
    }

    private fun isInSun(player: Player): Boolean {
        val loc = player.location
        val world = loc.world ?: return false

        // Sunlight is only active between tick 0 (sunrise) and roughly 12900 (sunset)
        val time = world.time
        if (time !in 0..12900) return false

        if (world.hasStorm()) return false

        // lightFromSky represents exposure to the sky (sunlight is 15 in open air)
        return player.eyeLocation.block.lightFromSky >= 15
    }

    private fun isNearFireOrLava(player: Player): Boolean {
        val block = player.location.block
        val headBlock = player.eyeLocation.block
        val legBlock = player.location.clone().add(0.0, -0.5, 0.0).block

        val fireTypes = listOf(
            Material.FIRE, Material.SOUL_FIRE,
            Material.LAVA, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE
        )

        return fireTypes.contains(block.type) ||
                fireTypes.contains(headBlock.type) ||
                fireTypes.contains(legBlock.type)
    }
}