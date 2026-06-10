package project.kompass.originsPlus.listener

import project.kompass.originsPlus.OriginsPlus
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Enderman
import org.bukkit.entity.Player
import org.bukkit.entity.memory.MemoryKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.EventPriority
import com.destroystokyo.paper.event.entity.EndermanAttackPlayerEvent
import com.destroystokyo.paper.event.entity.EntityPathfindEvent
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.UUID

class EndermanListener(private val plugin: OriginsPlus) : Listener {

    // High-performance cache that automatically purges entries 30 seconds after provocation,
    // fully replacing the deprecated Metadata API and guaranteeing zero memory leaks.
    private val provokedEndermen: Cache<UUID, UUID> = CacheBuilder.newBuilder()
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build()

    // Cached reflection fields to eliminate runtime method lookup overhead entirely
    private var sentientMobsPlugin: org.bukkit.plugin.Plugin? = null
    private var getAiManagerMethod: Method? = null
    private var getStateMachineMethod: Method? = null
    private var getContextMethod: Method? = null
    private var setTargetMethod: Method? = null
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
                    // Fast field-lookup check. Instantly skip the 99% of Endermen not targeting a player.
                    val target = enderman.target as? Player

                    if (target != null) {
                        if (target.gameMode != GameMode.SURVIVAL && target.gameMode != GameMode.ADVENTURE) continue

                        val distanceSq = enderman.location.distanceSquared(target.location)
                        val isProtected = plugin.hasOrigin(target, "Enderian") || plugin.hasOrigin(target, "Shulk")

                        if (isProtected) {
                            resetAggression(enderman)
                        } else {
                            val provokedByUuid = provokedEndermen.getIfPresent(enderman.uniqueId)
                            val isProvoked = provokedByUuid == target.uniqueId

                            if (!isProvoked) {
                                resetAggression(enderman)
                            }
                        }
                    } else {
                        // Scan the Brain-level ANGRY_AT memory to catch direct NMS target-injection bypasses
                        try {
                            val angryAtUuid = enderman.getMemory(MemoryKey.ANGRY_AT)
                            if (angryAtUuid != null) {
                                val angryAtPlayer = Bukkit.getPlayer(angryAtUuid)
                                if (angryAtPlayer != null && (angryAtPlayer.gameMode == GameMode.SURVIVAL || angryAtPlayer.gameMode == GameMode.ADVENTURE)) {
                                    val isProtected = plugin.hasOrigin(angryAtPlayer, "Enderian") || plugin.hasOrigin(angryAtPlayer, "Shulk")
                                    if (isProtected) {
                                        resetAggression(enderman)
                                    } else {
                                        val provokedByUuid = provokedEndermen.getIfPresent(enderman.uniqueId)
                                        val isProvoked = provokedByUuid == angryAtPlayer.uniqueId

                                        if (!isProvoked) {
                                            resetAggression(enderman)
                                        }
                                    }
                                }
                            }
                        } catch (ignored: Exception) {}

                        // Clean up visual metadata states once the combat sequence is fully terminated
                        if (enderman.isScreaming || enderman.hasBeenStaredAt()) {
                            enderman.setScreaming(false)
                            enderman.setHasBeenStaredAt(false)
                        }
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
                getStateMachineMethod = aiManagerClass?.getMethod("getStateMachine", UUID::class.java)

                val stateMachineClass = getStateMachineMethod?.returnType
                getContextMethod = stateMachineClass?.getMethod("getContext")

                val contextClass = getContextMethod?.returnType
                setTargetMethod = contextClass?.getMethod("setTarget", org.bukkit.entity.LivingEntity::class.java)
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

        // Layer 3: Wipes target from SentientMobs' internal AI Behavior State Machine Context
        clearSentientMobsTarget(enderman)

        enderman.pathfinder.stopPathfinding()
    }

    /**
     * Intercepts SentientMobs' custom API at runtime using cached reflection,
     * forcing its custom targeting state machines to cleanly stand down.
     */
    private fun clearSentientMobsTarget(enderman: Enderman) {
        initReflection()
        val sm = sentientMobsPlugin ?: return
        val getAiManager = getAiManagerMethod ?: return
        val getStateMachine = getStateMachineMethod ?: return
        val getContext = getContextMethod ?: return
        val setTarget = setTargetMethod ?: return

        try {
            val aiManager = getAiManager.invoke(sm)
            val stateMachine = getStateMachine.invoke(aiManager, enderman.uniqueId)
            if (stateMachine != null) {
                val context = getContext.invoke(stateMachine)
                if (context != null) {
                    setTarget.invoke(context, null)
                }
            }
        } catch (ignored: Exception) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityTarget(event: EntityTargetLivingEntityEvent) {
        val target = event.target as? Player ?: return
        val entity = event.entity as? Enderman ?: return

        val isProtected = plugin.hasOrigin(target, "Enderian") || plugin.hasOrigin(target, "Shulk")
        if (isProtected) {
            event.isCancelled = true
            resetAggression(entity)
        } else {
            // Normal players: verify if they actually provoked this Enderman
            val provokedByUuid = provokedEndermen.getIfPresent(entity.uniqueId)
            val isProvoked = provokedByUuid == target.uniqueId

            if (!isProvoked) {
                event.isCancelled = true
                resetAggression(entity)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEndermanAttackPlayer(event: EndermanAttackPlayerEvent) {
        val player = event.player ?: return
        val enderman = event.entity
        if (plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Shulk")) {
            event.isCancelled = true
            resetAggression(enderman)
        } else {
            // Normal player is staring; tag the Enderman as legitimately provoked by this player
            provokedEndermen.put(enderman.uniqueId, player.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityPathfind(event: EntityPathfindEvent) {
        val entity = event.entity as? Enderman ?: return

        // 1. Direct entity targeting pathfind check
        val targetEntity = event.targetEntity as? Player
        if (targetEntity != null) {
            val isProtected = plugin.hasOrigin(targetEntity, "Enderian") || plugin.hasOrigin(targetEntity, "Shulk")
            if (isProtected) {
                event.isCancelled = true
                resetAggression(entity)
                return
            } else {
                val provokedByUuid = provokedEndermen.getIfPresent(entity.uniqueId)
                val isProvoked = provokedByUuid == targetEntity.uniqueId

                if (!isProvoked) {
                    event.isCancelled = true
                    resetAggression(entity)
                    return
                }
            }
        }

        // 2. Location-based coordinate pathfind check (destinations near players)
        val destination = event.loc
        val range = entity.getAttribute(Attribute.FOLLOW_RANGE)?.value ?: 64.0
        val rangeSq = range * range

        val worldPlayers = entity.world.players
        for (player in worldPlayers) {
            if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) continue

            val loc = player.location
            if (entity.location.distanceSquared(loc) <= rangeSq) {
                val isProtected = plugin.hasOrigin(player, "Enderian") || plugin.hasOrigin(player, "Shulk")

                if (isProtected) {
                    // Cancel pathfinding if coordinates are within 16 blocks of protected players
                    if (destination.distanceSquared(loc) < 256.0) {
                        event.isCancelled = true
                        resetAggression(entity)
                        return
                    }
                } else {
                    // Normal players: cancel pathing if they didn't provoke the Enderman
                    val provokedByUuid = provokedEndermen.getIfPresent(entity.uniqueId)
                    val isProvoked = provokedByUuid == player.uniqueId

                    if (!isProvoked) {
                        if (destination.distanceSquared(loc) < 256.0) {
                            event.isCancelled = true
                            resetAggression(entity)
                            return
                        }
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val entity = event.entity

        // Case 1: Enderman attempting to damage a Player
        if (damager is Enderman && entity is Player) {
            val isProtected = plugin.hasOrigin(entity, "Enderian") || plugin.hasOrigin(entity, "Shulk")
            if (isProtected) {
                event.isCancelled = true
                resetAggression(damager)
            } else {
                // Normal player: block damage if they never provoked the Enderman
                val provokedByUuid = provokedEndermen.getIfPresent(damager.uniqueId)
                val isProvoked = provokedByUuid == entity.uniqueId

                if (!isProvoked) {
                    event.isCancelled = true
                    resetAggression(damager)
                }
            }
        }
        // Case 2: Player damaging an Enderman
        else if (damager is Player && entity is Enderman) {
            if (plugin.hasOrigin(damager, "Enderian") || plugin.hasOrigin(damager, "Shulk")) {
                // If protected player hits it, reset aggression immediately and don't tag as provoked
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (entity.isValid) {
                        resetAggression(entity)
                    }
                })
            } else {
                // Normal player hits it; tag the Enderman as legitimately provoked by this player
                provokedEndermen.put(entity.uniqueId, damager.uniqueId)
            }
        }
    }
}