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
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Enderman
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Blaze
import org.bukkit.entity.MagmaCube
import org.bukkit.entity.Piglin
import org.bukkit.entity.PiglinBrute
import org.bukkit.entity.Mob
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.EventPriority
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent
import com.destroystokyo.paper.event.entity.EntityPathfindEvent
import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.entity.ai.GoalKey
import com.destroystokyo.paper.entity.ai.GoalType
import java.util.EnumSet

class EnderianListener(private val plugin: OriginsPlus) : Listener {

    private val spawnerTypeKey = NamespacedKey(plugin, "spawner_mob_type")

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!plugin.hasOrigin(player, "Enderian")) return
        if (player.gameMode == GameMode.CREATIVE) return

        val hand = player.inventory.itemInMainHand
        if (!hand.type.isAir) return

        val block = event.block
        if (block.type.hardness < 0) return

        event.isDropItems = false
        event.expToDrop = 0

        if (block.type == Material.SPAWNER) {
            val cs = block.state as? CreatureSpawner
            val spawnedType = cs?.spawnedType ?: EntityType.PIG

            val spawnerItem = ItemStack(Material.SPAWNER)
            val meta = spawnerItem.itemMeta
            if (meta != null) {
                meta.persistentDataContainer.set(spawnerTypeKey, PersistentDataType.STRING, spawnedType.name)

                val cleanName = spawnedType.name.lowercase().replace("_", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } } + " Spawner"
                meta.displayName(Component.text(cleanName, NamedTextColor.YELLOW))

                spawnerItem.itemMeta = meta
            }
            block.world.dropItemNaturally(block.location, spawnerItem)
        } else {
            val silkTool = ItemStack(Material.NETHERITE_PICKAXE).apply {
                addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH, 1)
            }
            val drops = block.getDrops(silkTool, player)

            if (drops.isEmpty()) {
                val fallbackDrops = block.getDrops(ItemStack(Material.AIR), player)
                for (drop in fallbackDrops) {
                    block.world.dropItemNaturally(block.location, drop)
                }
            } else {
                for (drop in drops) {
                    block.world.dropItemNaturally(block.location, drop)
                }
            }
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced

        if (block.type == Material.SPAWNER) {
            val item = event.itemInHand
            val meta = item.itemMeta ?: return

            val typeName = meta.persistentDataContainer.get(spawnerTypeKey, PersistentDataType.STRING) ?: return
            try {
                val entityType = EntityType.valueOf(typeName)
                val placedState = block.state as? CreatureSpawner ?: return

                placedState.spawnedType = entityType
                placedState.update(true, true)
            } catch (ignored: IllegalArgumentException) {
                // Fail-safe
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return
        val entity = event.entity as? Mob ?: return

        if (entity is Enderman) {
            if (plugin.hasOrigin(target, "Enderian") || plugin.hasOrigin(target, "Shulk")) {
                event.isCancelled = true
                entity.target = null
                entity.setScreaming(false)
                entity.setHasBeenStaredAt(false)
            }
        } else if (entity is Blaze || entity is MagmaCube) {
            if (plugin.hasOrigin(target, "Blazeborn")) {
                event.isCancelled = true
                entity.target = null
            }
        } else if (entity is Piglin || entity is PiglinBrute) {
            if (plugin.hasOrigin(target, "Blazeborn")) {
                event.isCancelled = true
                entity.target = null
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEndermanAttackPlayer(event: EndermanAttackPlayerEvent) {
        val player = event.player
        val enderman = event.entity
        if (plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Shulk")) {
            event.isCancelled = true
            enderman.target = null
            enderman.setScreaming(false)
            enderman.setHasBeenStaredAt(false)
            enderman.pathfinder.stopPathfinding()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityPathfind(event: EntityPathfindEvent) {
        val entity = event.entity as? Enderman ?: return

        // 1. Direct entity targeting pathfind check
        val targetEntity = event.targetEntity as? Player
        if (targetEntity != null && (plugin.hasOrigin(targetEntity, "Enderian") || plugin.hasOrigin(targetEntity, "Shulk"))) {
            event.isCancelled = true
            entity.target = null
            entity.setScreaming(false)
            entity.setHasBeenStaredAt(false)
            entity.pathfinder.stopPathfinding()
            return
        }

        // 2. Location-based coordinate pathfind check (destinations near protected players)
        val destination = event.loc
        val range = entity.getAttribute(Attribute.FOLLOW_RANGE)?.value ?: 64.0
        val rangeSq = range * range

        // Evaluates players directly from the world list instead of querying loaded chunks
        val worldPlayers = entity.world.players
        for (player in worldPlayers) {
            if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) continue
            if (!plugin.hasOrigin(player, "Enderian") && !plugin.hasOrigin(player, "Shulk")) continue

            val loc = player.location
            if (entity.location.distanceSquared(loc) <= rangeSq) {
                // Cancel if path destination falls within 3 blocks (9.0 squared) of a player
                if (destination.distanceSquared(loc) < 9.0) {
                    event.isCancelled = true
                    entity.target = null
                    entity.setScreaming(false)
                    entity.setHasBeenStaredAt(false)
                    entity.pathfinder.stopPathfinding()
                    return
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val entity = event.entity

        // Case 1: Enderman attempting to damage an Enderian/Shulk Player
        if (damager is Enderman && entity is Player) {
            if (plugin.hasOrigin(entity, "Enderian") || plugin.hasOrigin(entity, "Shulk")) {
                event.isCancelled = true
                damager.target = null
                damager.setScreaming(false)
                damager.setHasBeenStaredAt(false)
                damager.pathfinder.stopPathfinding()
            }
        }
        // Case 2: Enderian/Shulk Player damaging an Enderman (suppresses retaliatory anger)
        else if (damager is Player && entity is Enderman) {
            if (plugin.hasOrigin(damager, "Enderian") || plugin.hasOrigin(damager, "Shulk")) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (entity.isValid) {
                        entity.target = null
                        entity.setScreaming(false)
                        entity.setHasBeenStaredAt(false)
                        entity.pathfinder.stopPathfinding()
                    }
                })
            }
        }
    }

    @EventHandler
    fun onEntityAddToWorld(event: EntityAddToWorldEvent) {
        val entity = event.entity
        if (entity is Piglin || entity is PiglinBrute) {
            val mob = entity as Mob
            val mobGoals = Bukkit.getMobGoals()
            val goalKey = GoalKey.of(Mob::class.java, NamespacedKey(plugin, "run_away_from_blazeborn"))

            if (!mobGoals.hasGoal(mob, goalKey)) {
                mobGoals.addGoal(mob, 1, RunAwayFromBlazebornGoal(mob, plugin))
            }
        } else if (entity is Enderman) {
            val mobGoals = Bukkit.getMobGoals()
            val targetKey = GoalKey.of(Enderman::class.java, NamespacedKey(plugin, "prevent_enderian_target"))
            val proximityKey = GoalKey.of(Enderman::class.java, NamespacedKey(plugin, "prevent_enderian_proximity"))

            if (!mobGoals.hasGoal(entity, targetKey)) {
                mobGoals.addGoal(entity, 0, PreventEnderianTargetGoal(entity, plugin))
            }
            if (!mobGoals.hasGoal(entity, proximityKey)) {
                mobGoals.addGoal(entity, 0, PreventEnderianProximityGoal(entity, plugin))
            }
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
                cause == EntityDamageEvent.DamageCause.FREEZE) {

                val isRidingBoat = player.vehicle is org.bukkit.entity.Boat
                val inWater = player.isInWater && !isRidingBoat

                if (hasHelmet(player) && isInRain(player) && !inWater && !isStandingInWater(player)) {
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
        if (player.vehicle is org.bukkit.entity.Boat) {
            return false
        }

        val blocksToCheck = listOf(player.location.block, player.eyeLocation.block)
        for (block in blocksToCheck) {
            if (block.type == Material.WATER || block.type == Material.BUBBLE_COLUMN) {
                return true
            }
            val blockData = block.blockData
            if (blockData is Waterlogged && blockData.isWaterlogged) {
                return true
            }
        }
        return false
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

    private fun isInSun(player: Player): Boolean {
        val world = player.world
        val time = world.time
        if (time !in 0..12900) return false
        if (world.hasStorm()) return false

        return isExposedToSun(player)
    }

    private fun isInRain(player: Player): Boolean {
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

    private fun isExposedToSun(player: Player): Boolean {
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
                if (type.name.contains("GLASS") && !type.name.contains("TINTED")) {
                    continue
                }
                return false
            }
        }
        return true
    }
}

private class RunAwayFromBlazebornGoal(private val mob: Mob, private val plugin: OriginsPlus) : Goal<Mob> {

    private var targetPlayer: Player? = null
    private val key = GoalKey.of(Mob::class.java, NamespacedKey(plugin, "run_away_from_blazeborn"))

    override fun shouldActivate(): Boolean {
        val worldPlayers = mob.world.players.filter {
            plugin.hasOrigin(it, "Blazeborn") &&
                    it.gameMode != GameMode.CREATIVE &&
                    it.gameMode != GameMode.SPECTATOR &&
                    !it.isDead
        }

        if (worldPlayers.isEmpty()) return false

        val closestPlayer = worldPlayers.minByOrNull { mob.location.distanceSquared(it.location) } ?: return false

        // Limits checks to 16 blocks (256.0 squared)
        if (mob.location.distanceSquared(closestPlayer.location) > 256.0) return false

        targetPlayer = closestPlayer
        return true
    }

    override fun shouldStayActive(): Boolean {
        val player = targetPlayer ?: return false
        if (!player.isOnline || player.isDead || player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return false
        if (!plugin.hasOrigin(player, "Blazeborn")) return false

        return mob.location.distanceSquared(player.location) < 256.0
    }

    override fun start() {
        val currentTarget = mob.target as? Player
        if (currentTarget != null && plugin.hasOrigin(currentTarget, "Blazeborn")) {
            mob.target = null
        }
    }
    override fun stop() {
        targetPlayer = null
        mob.pathfinder.stopPathfinding()
    }

    override fun tick() {
        val player = targetPlayer ?: return

        val awayVector = mob.location.toVector().subtract(player.location.toVector())
        if (awayVector.lengthSquared() == 0.0) {
            awayVector.setX(1.0)
        }
        awayVector.normalize().multiply(8.0)

        val targetLoc = mob.location.clone().add(awayVector)

        mob.pathfinder.moveTo(targetLoc, 1.3)
    }

    override fun getKey(): GoalKey<Mob> = key

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK)
}

/**
 * High-priority target-suppressing goal. Uses world player array tracking
 * instead of spatial chunk entities query to protect TPS.
 */
private class PreventEnderianTargetGoal(
    private val enderman: Enderman,
    private val plugin: OriginsPlus
) : Goal<Enderman> {

    private val key = GoalKey.of(Enderman::class.java, NamespacedKey(plugin, "prevent_enderian_target"))

    override fun shouldActivate(): Boolean {
        val range = enderman.getAttribute(Attribute.FOLLOW_RANGE)?.value ?: 64.0
        val rangeSq = range * range

        val worldPlayers = enderman.world.players.filter {
            it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE
        }
        if (worldPlayers.isEmpty()) return false

        val closestPlayer = worldPlayers.minByOrNull { enderman.location.distanceSquared(it.location) } ?: return false

        if (enderman.location.distanceSquared(closestPlayer.location) > rangeSq) return false

        return plugin.hasOrigin(closestPlayer, "Enderian") || plugin.hasOrigin(closestPlayer, "Shulk")
    }

    override fun shouldStayActive(): Boolean = shouldActivate()

    override fun start() {
        enderman.target = null
    }

    override fun stop() {}

    override fun tick() {
        enderman.target = null
    }

    override fun getKey(): GoalKey<Enderman> = key

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.TARGET)
}

/**
 * High-priority proximity-locking goal. Claims MOVE and LOOK tokens
 * only when a protected player is within 16 blocks (256.0 squared).
 */
private class PreventEnderianProximityGoal(
    private val enderman: Enderman,
    private val plugin: OriginsPlus
) : Goal<Enderman> {

    private val key = GoalKey.of(Enderman::class.java, NamespacedKey(plugin, "prevent_enderian_proximity"))

    override fun shouldActivate(): Boolean {
        val rangeSq = 256.0 // 16.0 * 16.0

        val worldPlayers = enderman.world.players.filter {
            it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE
        }
        if (worldPlayers.isEmpty()) return false

        val closestPlayer = worldPlayers.minByOrNull { enderman.location.distanceSquared(it.location) } ?: return false

        if (enderman.location.distanceSquared(closestPlayer.location) > rangeSq) return false

        return plugin.hasOrigin(closestPlayer, "Enderian") || plugin.hasOrigin(closestPlayer, "Shulk")
    }

    override fun shouldStayActive(): Boolean = shouldActivate()

    override fun start() {
        resetAggression()
    }

    override fun stop() {}

    override fun tick() {
        resetAggression()
    }

    private fun resetAggression() {
        enderman.target = null
        enderman.setScreaming(false)
        enderman.setHasBeenStaredAt(false)
        enderman.pathfinder.stopPathfinding()
    }

    override fun getKey(): GoalKey<Enderman> = key

    override fun getTypes(): EnumSet<GoalType> = EnumSet.of(GoalType.MOVE, GoalType.LOOK)
}