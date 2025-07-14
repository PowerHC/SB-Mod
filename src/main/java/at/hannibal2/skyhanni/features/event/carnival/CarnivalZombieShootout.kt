package at.hannibal2.skyhanni.features.event.carnival

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.data.HypixelData
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.ServerBlockChangeEvent
import at.hannibal2.skyhanni.events.chat.SkyHanniChatEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniRenderWorldEvent
import at.hannibal2.skyhanni.events.minecraft.SkyHanniTickEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.test.command.ErrorManager
import at.hannibal2.skyhanni.utils.EntityUtils
import at.hannibal2.skyhanni.utils.LorenzVec
import at.hannibal2.skyhanni.utils.RegexUtils.matches
import at.hannibal2.skyhanni.utils.RenderUtils
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderable
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.StringUtils.removeColor
import at.hannibal2.skyhanni.utils.compat.MinecraftCompat
import at.hannibal2.skyhanni.utils.compat.getEntityHelmet
import at.hannibal2.skyhanni.utils.getLorenzVec
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.draw3DLine
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawDynamicText
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawHitbox
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.drawWaypointFilled
import at.hannibal2.skyhanni.utils.render.WorldRenderUtils.exactPlayerEyeLocation
import at.hannibal2.skyhanni.utils.renderables.StringRenderable
import at.hannibal2.skyhanni.utils.renderables.container.HorizontalContainerRenderable
import at.hannibal2.skyhanni.utils.renderables.item.ItemStackRenderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraft.client.Minecraft
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.monster.EntityZombie
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.util.BlockPos
import net.minecraft.util.MathHelper
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

//#if MC > 1.21
//$$ import net.minecraft.state.property.Properties
//#endif

@SkyHanniModule
object CarnivalZombieShootout {

    private val config get() = SkyHanniMod.feature.event.carnival.zombieShootout

    private data class ShootoutLamp(var pos: LorenzVec, var time: SimpleTimeMark)
    private data class ShootoutZombie(val entity: EntityZombie, val type: ZombieType)

    private var lastActionTime = 0L

    private data class Target(val entity: Entity?, val lamp: ShootoutLamp?, val points: Int) {
        fun isSameTarget(other: Target): Boolean {
            return (this.entity != null && other.entity != null && this.entity.entityId == other.entity.entityId) ||
                (this.lamp != null && other.lamp != null && this.lamp.pos == other.lamp.pos)
        }
    }

    private var targetQueue = mutableListOf<Target>()

    private var content = HorizontalContainerRenderable(listOf())
    private var drawZombies = listOf<ShootoutZombie>()
    private val zombieTimes = mutableMapOf<ShootoutZombie, SimpleTimeMark>()
    private var maxType = ZombieType.LEATHER
    private var lamp: ShootoutLamp? = null
    private var started = false

    private val patternGroup = RepoPattern.group("event.carnival")

    /**
     * REGEX-TEST: [NPC] Carnival Cowboy: Good luck, pal!
     */
    private val startPattern by patternGroup.pattern(
        "shootout.start",
        "\\[NPC] Carnival Cowboy: Good luck, pal!",
    )

    /**
     * REGEX-TEST:                              Zombie Shootout
     */
    private val endPattern by patternGroup.pattern(
        "shootout.end",
        " {29}Zombie Shootout",
    )

    enum class ZombieType(val points: Int, val helmet: Item, val color: Color, val lifetime: Duration) {
        LEATHER(30, Items.leather_helmet, Color(165, 42, 42), 8.seconds), // Brown
        IRON(50, Items.iron_helmet, Color(192, 192, 192), 7.seconds), // Silver
        GOLD(80, Items.golden_helmet, Color(255, 215, 0), 6.seconds), // Gold
        DIAMOND(120, Items.diamond_helmet, Color(44, 214, 250), 5.seconds) // Diamond
    }

    @HandleEvent
    fun onRenderWorld(event: SkyHanniRenderWorldEvent) {
        if (!isEnabled() || (!config.coloredHitboxes && !config.coloredLines && !config.zombieTimer)) return

        if (config.zombieTimer) event.renderZombieTimer()
        if (config.coloredHitboxes) event.renderHitBoxes()
        if (config.coloredLines) event.renderLines()
    }

    private fun SkyHanniRenderWorldEvent.renderZombieTimer() {
        val zombiesToRemove = mutableListOf<ShootoutZombie>()

        for ((zombie, time) in zombieTimes) {
            val lifetime = zombie.type.lifetime
            val timer = lifetime - time.passedSince()

            if (config.highestOnly && zombie.type != maxType) continue

            if (timer > 0.seconds) {
                val entity = EntityUtils.getEntityByID(zombie.entity.entityId) ?: continue
                val isSmall = (entity as? EntityZombie)?.isChild ?: false

                val skips = lifetime / 3
                val prefix = determinePrefix(timer, lifetime, lifetime - skips, lifetime - skips * 2)
                val height = if (isSmall) entity.height / 2 else entity.height

                drawDynamicText(
                    entity.getLorenzVec().add(-0.5, height + 0.5, -0.5),
                    "$prefix${timer.toString(DurationUnit.SECONDS, 1)}",
                    scaleMultiplier = 1.25,
                )
            } else {
                if (timer < (-2).seconds) {
                    zombiesToRemove.add(zombie)
                }
            }
        }

        zombiesToRemove.forEach { zombieTimes.remove(it) }
    }

    private fun SkyHanniRenderWorldEvent.renderHitBoxes() {
        lamp?.let {
            drawWaypointFilled(it.pos, Color.RED, minimumAlpha = 1.0f)
        }

        for ((zombie, type) in drawZombies) {
            val entity = EntityUtils.getEntityByID(zombie.entityId) ?: continue
            val isSmall = (entity as? EntityZombie)?.isChild ?: false

            val boundingBox = if (isSmall) entity.entityBoundingBox.expand(0.0, -0.4, 0.0).offset(0.0, -0.4, 0.0)
            else entity.entityBoundingBox

            drawHitbox(
                boundingBox.expand(0.1, 0.05, 0.0).offset(0.0, 0.05, 0.0),
                type.color,
                lineWidth = 3,
                depth = false,
            )
        }
    }

    private fun SkyHanniRenderWorldEvent.renderLines() = lamp?.let {
        draw3DLine(
            exactPlayerEyeLocation(),
            it.pos.add(0.5, 0.5, 0.5),
            Color.RED,
            3,
            false,
        )
    }

    @HandleEvent
    fun onRenderOverlay(event: GuiRenderEvent.GuiOverlayRenderEvent) {
        if (!isEnabled() || !config.lampTimer) return

        config.lampPosition.renderRenderable(content, posLabel = "Lantern Timer")
    }

    @HandleEvent(ServerBlockChangeEvent::class)
    fun onBlockChange(event: ServerBlockChangeEvent) {
        if (!isEnabled() || !started) return

        //#if MC < 1.21
        val old = event.old
        val new = event.new

        lamp = when {
            old == "redstone_lamp" && new == "lit_redstone_lamp" -> ShootoutLamp(event.location, SimpleTimeMark.now())
            old == "lit_redstone_lamp" && new == "redstone_lamp" -> null
            else -> lamp
        }
        //#else
        //$$ val blockOld = event.old
        //$$ val blockNew = event.new
        //$$ if(blockOld == "redstone_lamp" && blockNew == "redstone_lamp") {
        //$$     val old = event.oldState.get(Properties.LIT)
        //$$     val new = event.newState.get(Properties.LIT)
        //$$     lamp = when {
        //$$         !old && new -> ShootoutLamp(event.location, SimpleTimeMark.now())
        //$$         old && !new -> null
        //$$         else -> lamp
        //$$     }
        //$$ }
        //#endif
    }

    @HandleEvent
    fun onChat(event: SkyHanniChatEvent) {
        if (!config.enabled || HypixelData.skyBlockArea != "Carnival") return

        val message = event.message.removeColor()

        if (startPattern.matches(message)) {
            started = true
        } else if (endPattern.matches(message)) {
            started = false
        }
    }

    @HandleEvent
    fun onTick(event: SkyHanniTickEvent) {
        // Handle preconditions and queue clearing
        handleTickPreconditions()

        // Check if main logic should be skipped based on module/feature state or tick type
        val moduleDisabled = !isEnabled()
        val allVisualOrAutoShootFeaturesOff = !config.coloredHitboxes && !config.zombieTimer && !config.lampTimer && !config.autoShoot
        val notModTick = !event.isMod(2)

        if (moduleDisabled || allVisualOrAutoShootFeaturesOff || notModTick) {
            return // Exit early if conditions are not met for the main logic
        }

        // Update features that run on mod ticks
        updateVisualFeatures()

        // Handle auto-shoot logic if enabled
        if (config.autoShoot) {
            handleAutoShootLogic()
        }
    }

    private fun handleTickPreconditions() {
        // This must run every tick regardless of event.isMod(2) to ensure targetQueue is cleared
        // immediately when autoShoot is disabled, or module/event state changes.
        if (!isEnabled() || !started || !config.autoShoot) {
            targetQueue.clear()
        }
    }

    private fun updateVisualFeatures() {
        if (config.coloredHitboxes || config.zombieTimer) {
            updateZombies()
        }

        if (config.lampTimer) {
            content = lamp?.let {
                updateContent(it.time)
            } ?: HorizontalContainerRenderable(listOf())
        }
    }

    private fun handleAutoShootLogic() {
        val currentTime = System.currentTimeMillis()

        val currentTargets = fetchAndFilterTargets()
        updateTargetQueue(currentTargets) // Add new targets and remove invalid ones

        targetQueue.sortByDescending { it.points } // Sort by points (highest first)

        val target = targetQueue.firstOrNull()
        if (target != null) {
            processTarget(target, currentTime)
        }
    }

    private fun fetchAndFilterTargets(): MutableList<Target> {
        val currentTargets = mutableListOf<Target>()

        // Find and add nearby zombies
        val nearbyZombies = EntityUtils.getEntitiesNextToPlayer<EntityZombie>(30.0).mapNotNull { zombie ->
            if (zombie.health <= 0) return@mapNotNull null
            val helmet = zombie.getEntityHelmet() ?: return@mapNotNull null
            val type = toType(helmet) ?: return@mapNotNull null
            Target(entity = zombie, lamp = null, points = type.points)
        }
        currentTargets.addAll(nearbyZombies)

        // Add lamp if present and active
        lamp?.let { currentLamp ->
            if (isLampActive(currentLamp)) {
                currentTargets.add(Target(entity = null, lamp = currentLamp, points = 100))
            } else {
                lamp = null // Lamp is no longer active, reset it
            }
        }
        return currentTargets
    }

    private fun updateTargetQueue(newTargets: MutableList<Target>) {
        // Add new targets to targetQueue if they aren't already present
        for (target in newTargets) {
            if (!targetQueue.any { it.isSameTarget(target) }) {
                targetQueue.add(target)
            }
        }

        // Remove invalid targets from targetQueue
        targetQueue = targetQueue.filter { target ->
            if (target.entity != null) {
                //#if MC < 1.21
                target.entity.isEntityAlive
                //#else
                //$$ target.entity.isAlive
                //#endif
            } else if (target.lamp != null) {
                isLampActive(target.lamp)
            } else {
                false
            }
        }.toMutableList()
    }

    private fun processTarget(target: Target, currentTime: Long) {
        //#if MC < 1.21
        val isValidTarget = (target.entity != null && target.entity.isEntityAlive) ||
            (target.lamp != null && isLampActive(target.lamp))
        //#else
        //$$ val isValidTarget = (target.entity != null && target.entity.isAlive) ||
        //$$     (target.lamp != null && isLampActive(target.lamp))
        //#endif

        if (isValidTarget) {
            if (currentTime - lastActionTime >= config.shootDelay) {
                if (target.entity != null) {
                    aimAtEntity(target.entity)
                } else if (target.lamp != null) {
                    aimAtLamp(target.lamp)
                }
                lastActionTime = currentTime
            }
        } else {
            targetQueue.remove(target) // Target is invalid, remove it
        }
    }

    // --- Existing Helper Functions ---

    private fun updateZombies() {
        val nearbyZombies = getZombies()
        maxType = nearbyZombies.maxByOrNull { it.type.points }?.type ?: ZombieType.LEATHER
        val maxZombies = nearbyZombies.filter { it.type == maxType }

        drawZombies = when {
            config.coloredHitboxes && config.highestOnly -> maxZombies
            config.coloredHitboxes -> nearbyZombies
            else -> emptyList()
        }

        if (config.zombieTimer) {
            for (zombie in nearbyZombies) {
                zombieTimes.putIfAbsent(zombie, SimpleTimeMark.now())
            }
        }
    }

    private fun updateContent(time: SimpleTimeMark): HorizontalContainerRenderable {
        val lamp = ItemStack(Blocks.redstone_lamp)
        val timer = 6.seconds - time.passedSince()
        val prefix = determinePrefix(timer, 6.seconds, 4.seconds, 2.seconds)

        return HorizontalContainerRenderable(
            listOf(
                ItemStackRenderable(lamp),
                StringRenderable("§6Disappears in $prefix$timer"),
            ),
            spacing = 1,
            verticalAlign = RenderUtils.VerticalAlignment.CENTER,
        )
    }

    private fun getZombies() =
        EntityUtils.getEntitiesNextToPlayer<EntityZombie>(50.0).mapNotNull { zombie ->
            if (zombie.health <= 0) return@mapNotNull null
            val helmet = zombie.getEntityHelmet() ?: return@mapNotNull null
            val type = toType(helmet) ?: run {
                ErrorManager.logErrorStateWithData(
                    "Could not identify Zombie Shootout type",
                    "zombie type for zombie entity helmet is null",
                    "helmet" to helmet,
                    "helmet.displayName" to helmet.displayName,
                    "helmet.item" to helmet.item,
                    //#if MC < 1.21
                    "helmet.unlocalizedName" to helmet.unlocalizedName,
                    //#else
                    //$$ "helmet.unlocalizedName" to helmet.item.translationKey,
                    //#endif
                )
                return@mapNotNull null
            }
            ShootoutZombie(zombie, type)
        }.toList()

    private fun determinePrefix(timer: Duration, good: Duration, mid: Duration, bad: Duration) =
        when (timer) {
            in mid..good -> "§a"
            in bad..mid -> "§e"
            else -> "§c"
        }

    private fun toType(item: ItemStack) = ZombieType.entries.find { it.helmet == item.item }

    private fun isEnabled() = config.enabled && HypixelData.skyBlockArea == "Carnival" && started

    private fun isLampActive(lamp: ShootoutLamp): Boolean {
        //#if MC < 1.21
        val world = MinecraftCompat.localWorld
        val blockPos = BlockPos(lamp.pos.x, lamp.pos.y, lamp.pos.z)
        //#else
        //$$ val world = MinecraftClient.getInstance().world ?: return false
        //$$ val blockPos = BlockPos(lamp.pos.x.toInt(), lamp.pos.y.toInt(), lamp.pos.z.toInt())
        //#endif

        val blockState = world.getBlockState(blockPos)
        val block = blockState.block

        //#if MC < 1.21
        return block == Blocks.lit_redstone_lamp
        //#else
        //$$ return block == Blocks.REDSTONE_LAMP && blockState.get(Properties.LIT)
        //#endif
    }

    private fun aimAtEntity(entity: Entity) {
        val player = MinecraftCompat.localPlayer ?: return
        if (player.worldObj == null) return

        val zombiePosX = entity.posX
        val zombiePosY = entity.posY + entity.eyeHeight
        val zombiePosZ = entity.posZ

        val motionX = entity.motionX
        val motionZ = entity.motionZ

        val adjustmentFactor = 10
        val adjustedPosX = zombiePosX + motionX * adjustmentFactor
        val adjustedPosZ = zombiePosZ + motionZ * adjustmentFactor

        val deltaX = adjustedPosX - player.posX
        var deltaY = zombiePosY - (player.posY + player.eyeHeight)
        val deltaZ = adjustedPosZ - player.posZ

        if (entity is EntityZombie && entity.isChild) {
            deltaY += 0.5
        }

        val distanceXZ = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val targetYaw = Math.toDegrees(atan2(deltaZ, deltaX)) - 90
        val targetPitch = -Math.toDegrees(atan2(deltaY, distanceXZ))

        //#if MC < 1.21
        val yawDifference = MathHelper.wrapAngleTo180_float((targetYaw - player.rotationYaw).toFloat())
        //#else
        //$$ val yawDifference = MathHelper.wrapDegrees((targetYaw - player.yaw).toFloat())
        //#endif
        val pitchDifference = (targetPitch - player.rotationPitch).toFloat()

        val maxYawRotation = 25.0f
        val maxPitchRotation = 25.0f

        if (Math.abs(yawDifference) > maxYawRotation) {
            player.rotationYaw += if (yawDifference > 0) maxYawRotation else -maxYawRotation
        } else {
            player.rotationYaw += yawDifference
        }

        if (Math.abs(pitchDifference) > maxPitchRotation) {
            player.rotationPitch += if (pitchDifference > 0) maxPitchRotation else -maxPitchRotation
        } else {
            player.rotationPitch += pitchDifference
        }

        //#if MC < 1.21
        Executors.newSingleThreadScheduledExecutor().schedule({
            KeyBinding.onTick(Minecraft.getMinecraft().gameSettings.keyBindUseItem.keyCode)
        }, 50, TimeUnit.MILLISECONDS)
        //#else
        //$$ Executors.newSingleThreadScheduledExecutor().schedule({
        //$$     val useItemKeyBinding = MinecraftClient.getInstance().options.useKey
        //$$     KeyBinding.setKeyPressed(useItemKeyBinding.boundKey, true)
        //$$     KeyBinding.setKeyPressed(useItemKeyBinding.boundKey, false)
        //$$ }, 50, TimeUnit.MILLISECONDS)
        //#endif
    }

    private fun aimAtLamp(lamp: ShootoutLamp) {
        val player = MinecraftCompat.localPlayer ?: return
        if (player.worldObj == null) return

        val lampPosX = lamp.pos.x + 0.5
        val lampPosY = lamp.pos.y + 0.5
        val lampPosZ = lamp.pos.z + 0.5

        val deltaX = lampPosX - player.posX
        val deltaY = lampPosY - (player.posY + player.getEyeHeight()) + 1
        val deltaZ = lampPosZ - player.posZ

        val distanceXZ = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        val targetYaw = Math.toDegrees(atan2(deltaZ, deltaX)) - 90
        val targetPitch = -Math.toDegrees(atan2(deltaY, distanceXZ))

        //#if MC < 1.21
        val yawDifference = MathHelper.wrapAngleTo180_float((targetYaw - player.rotationYaw).toFloat())
        //#else
        //$$ val yawDifference = MathHelper.wrapDegrees((targetYaw - player.yaw).toFloat())
        //#endif
        val pitchDifference = (targetPitch - player.rotationPitch).toFloat()

        val maxYawRotation = 20.0f
        val maxPitchRotation = 20.0f

        if (Math.abs(yawDifference) > maxYawRotation) {
            player.rotationYaw += if (yawDifference > 0) maxYawRotation else -maxYawRotation
        } else {
            player.rotationYaw += yawDifference
        }

        if (Math.abs(pitchDifference) > maxPitchRotation) {
            player.rotationPitch += if (pitchDifference > 0) maxPitchRotation else -maxPitchRotation
        } else {
            player.rotationPitch += pitchDifference
        }

        //#if MC < 1.21
        Executors.newSingleThreadScheduledExecutor().schedule({
            KeyBinding.onTick(Minecraft.getMinecraft().gameSettings.keyBindUseItem.keyCode)
        }, 50, TimeUnit.MILLISECONDS)
        //#else
        //$$ Executors.newSingleThreadScheduledExecutor().schedule({
        //$$     val useItemKeyBinding = MinecraftClient.getInstance().options.useKey
        //$$     KeyBinding.setKeyPressed(useItemKeyBinding.boundKey, true)
        //$$     KeyBinding.setKeyPressed(useItemKeyBinding.boundKey, false)
        //$$ }, 50, TimeUnit.MILLISECONDS)
        //#endif
    }
}
