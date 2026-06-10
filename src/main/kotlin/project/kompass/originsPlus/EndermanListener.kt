package project.kompass.originsPlus.listener

import project.kompass.originsPlus.OriginsPlus
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Enderman
import org.bukkit.entity.Player
import org.bukkit.entity.Mob
import org.bukkit.entity.memory.MemoryKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.EventPriority
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent
import com.destroystokyo.paper.event.entity.EntityPathfindEvent
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.HashSet

class EndermanListener(private val plugin: OriginsPlus) : Listener {

    private val provokedEndermen: Cache<UUID, UUID> = CacheBuilder.newBuilder()
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build()

    // Tracking set to ensure reflection is only executed once per Enderman lifecycle,
    // completely eliminating any repeating CPU or memory overhead.
    private val processedEndermen = HashSet<UUID>()

    // Cached reflection fields to eliminate runtime method lookup overhead entirely
    private var sentientMobsPlugin: org.bukkit.plugin.Plugin? = null
    private var getAiManagerMethod: Method? = null
    private var unregisterEntityMethod: Method? = null
    private var reflectionInitialized = false

    init {
        // High-frequency task running every server tick to aggressively override target selection,
        // pathfinding, and visual metadata states that bypass Paper's MobGoals API.
        // Extremely optimized: Directly inspects both legacy targets and brain-level ANGRY_AT memories.
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (world in Bukkit.getWorlds()) {
                val endermen = world.getEntitiesByClass(Enderman::class.java)
                if (endermen.isEmpty()) continue

                for (enderman in endermen) {
                    // Only unregister if we haven't already processed this entity
                    if (!processedEndermen.contains(enderman.uniqueId)) {
                        unregisterFromSentientMobs(enderman)
                        processedEndermen.add(enderman.uniqueId)
                    }

                    // Fast field-lookup check. Instantly skip the 99% of Endermen not targeting a player.
                    val target = enderman.target as? Player ?: continue
                    if (target.gameMode != GameMode.SURVIVAL && target.gameMode != GameMode.ADVENTURE) continue

                    val distanceSq = enderman.location.distanceSquared(target.location)
                    val isProtected = plugin.hasOrigin(target, "Enderian") || plugin.hasOrigin(target, "Shulk")

                    if (isProtected) {
                        // Rule 1: Protected player is ALWAYS immunised against target and visual state changes
                        resetAggression(enderman)
                    }
                }
            }
        }, 1L, 1L)
    }

    /**
     * Look up and cache all required SentientMobs reflection handles once on first use,
     * ensuring there are zero performance lookups during server ticking.
     */
    private fun initReflection() {
        if (reflectionInitialized) return
        try {
            val sm = Bukkit.getPluginManager().getPlugin("SentientMobs")
            if (sm != null && sm.isEnabled) {
                sentientMobsPlugin = sm
                getAiManagerMethod = sm.javaClass.getMethod("getAiManager")

                val aiManagerClass = getAiManagerMethod?.returnType
                // Corrected: Specifically look up "unregisterEntity" instead of "getStateMachine"
                unregisterEntityMethod = aiManagerClass?.getMethod("unregisterEntity", UUID::class.java)
            }
        } catch (ignored: Exception) {}
        reflectionInitialized = true
    }

    /**
     * Unified, triple-layer reset. Clears legacy Bukkit targets, modern NMS brain memories,
     * client-side metadata, and SentientMobs' internal State Machine target contexts.
     */
    private fun resetAggression(enderman: Enderman) {
        enderman.target = null
        enderman.setScreaming(false)
        enderman.setHasBeenStaredAt(false)

        try {
            // Layer 2: Wipes target from the modern NMS AI memory system
            enderman.setMemory(MemoryKey.ANGRY_AT, null)
        } catch (ignored: Exception) {}

        enderman.pathfinder.stopPathfinding()
    }

    /**
     * Intercepts SentientMobs' custom API at runtime using cached reflection,
     * forcing its custom targeting state machines to cleanly stand down.
     */
    private fun unregisterFromSentientMobs(enderman: Enderman) {
        initReflection()
        val sm = sentientMobsPlugin ?: return
        val getAiManager = getAiManagerMethod ?: return
        val unregisterEntity = unregisterEntityMethod ?: return

        try {
            val aiManager = getAiManager.invoke(sm)
            // Corrected: Invokes the actual unregisterEntity method handle
            unregisterEntity.invoke(aiManager, enderman.uniqueId)
        } catch (ignored: Exception) {}
    }

    @EventHandler
    fun onEntityAddToWorld(event: EntityAddToWorldEvent) {
        val entity = event.entity
        if (entity is Enderman) {
            if (!processedEndermen.contains(entity.uniqueId)) {
                unregisterFromSentientMobs(entity)
                processedEndermen.add(entity.uniqueId)
            }
        }
    }

    @EventHandler
    fun onEntityRemoveFromWorld(event: EntityRemoveFromWorldEvent) {
        val entity = event.entity
        if (entity is Enderman) {
            // Prevents any memory leak of cached UUIDs when Endermen unload or die
            processedEndermen.remove(entity.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return
        val entity = event.entity as? Enderman ?: return

        if (plugin.hasOrigin(target, "Enderian") || plugin.hasOrigin(target, "Shulk")) {
            event.isCancelled = true
            resetAggression(entity)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEndermanAttackPlayer(event: EndermanAttackPlayerEvent) {
        val player = event.player ?: return
        val enderman = event.entity
        if (plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Shulk")) {
            event.isCancelled = true
            resetAggression(enderman)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityPathfind(event: EntityPathfindEvent) {
        val entity = event.entity as? Enderman ?: return

        // 1. Direct entity targeting pathfind check
        val targetEntity = event.targetEntity as? Player
        if (targetEntity != null) {
            if (plugin.hasOrigin(targetEntity, "Enderian") || plugin.hasOrigin(targetEntity, "Shulk")) {
                event.isCancelled = true
                resetAggression(entity)
                return
            }
        }

        // 2. Location-based coordinate pathfind check (destinations near protected players)
        val destination = event.loc
        val range = entity.getAttribute(Attribute.FOLLOW_RANGE)?.value ?: 64.0
        val rangeSq = range * range

        val worldPlayers = entity.world.players
        for (player in worldPlayers) {
            if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) continue

            val loc = player.location
            if (entity.location.distanceSquared(loc) <= rangeSq) {
                if (plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Shulk")) {
                    // Cancel pathfinding if coordinates are within 16 blocks of protected players
                    if (destination.distanceSquared(loc) < 256.0) {
                        event.isCancelled = true
                        resetAggression(entity)
                        return
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val entity = event.entity

        // Case 1: Enderman attempting to damage a protected Player
        if (damager is Enderman && entity is Player) {
            if (plugin.hasOrigin(entity, "Enderian") || plugin.hasOrigin(entity, "Shulk")) {
                event.isCancelled = true
                resetAggression(damager)
            }
        }
        // Case 2: Protected Player damaging an Enderman
        else if (damager is Player && entity is Enderman) {
            if (plugin.hasOrigin(damager, "Enderian") || plugin.hasOrigin(damager, "Shulk")) {
                // If protected player hits it, reset aggression immediately
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (entity.isValid) {
                        resetAggression(entity)
                    }
                })
            }
        }
    }
}