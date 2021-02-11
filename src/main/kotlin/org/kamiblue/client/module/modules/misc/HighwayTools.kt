package org.kamiblue.client.module.modules.misc

import baritone.api.pathing.goals.GoalNear
import com.sun.jna.Native.toByteArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.init.Enchantments
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemPickaxe
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.network.play.server.SPacketSetSlot
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.kamiblue.client.event.SafeClientEvent
import org.kamiblue.client.event.events.PacketEvent
import org.kamiblue.client.event.events.RenderWorldEvent
import org.kamiblue.client.manager.managers.FriendManager
import org.kamiblue.client.manager.managers.PlayerPacketManager
import org.kamiblue.client.module.Category
import org.kamiblue.client.module.Module
import org.kamiblue.client.module.modules.client.Hud.primaryColor
import org.kamiblue.client.module.modules.client.Hud.secondaryColor
import org.kamiblue.client.module.modules.combat.VisualRange
import org.kamiblue.client.module.modules.movement.AntiHunger
import org.kamiblue.client.module.modules.player.AutoEat
import org.kamiblue.client.module.modules.player.InventoryManager
import org.kamiblue.client.process.HighwayToolsProcess
import org.kamiblue.client.util.*
import org.kamiblue.client.util.EntityUtils.flooredPosition
import org.kamiblue.client.util.EntityUtils.isFakeOrSelf
import org.kamiblue.client.util.WorldUtils.blackList
import org.kamiblue.client.util.WorldUtils.getBetterNeighbour
import org.kamiblue.client.util.WorldUtils.getMiningSide
import org.kamiblue.client.util.WorldUtils.getVisibleSides
import org.kamiblue.client.util.WorldUtils.isLiquid
import org.kamiblue.client.util.WorldUtils.isPlaceable
import org.kamiblue.client.util.WorldUtils.shulkerList
import org.kamiblue.client.util.color.ColorHolder
import org.kamiblue.client.util.graphics.ESPRenderer
import org.kamiblue.client.util.graphics.font.TextComponent
import org.kamiblue.client.util.items.*
import org.kamiblue.client.util.math.CoordinateConverter.asString
import org.kamiblue.client.util.math.Direction
import org.kamiblue.client.util.math.RotationUtils.getRotationTo
import org.kamiblue.client.util.math.VectorUtils.distanceTo
import org.kamiblue.client.util.math.VectorUtils.multiply
import org.kamiblue.client.util.math.VectorUtils.toVec3dCenter
import org.kamiblue.client.util.math.isInSight
import org.kamiblue.client.util.text.MessageSendHelper
import org.kamiblue.client.util.text.MessageSendHelper.sendServerMessage
import org.kamiblue.client.util.threads.*
import org.kamiblue.commons.extension.ceilToInt
import org.kamiblue.commons.extension.floorToInt
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap
import kotlin.random.Random.Default.nextInt

/**
 * @author Avanatiker
 * @since 20/08/2020
 */
internal object HighwayTools : Module(
    name = "HighwayTools",
    description = "Be the grief a step a head.",
    category = Category.MISC,
    alias = arrayOf("HT", "HWT"),
    modulePriority = 10
) {
    private val page by setting("Page", Page.BUILD, description = "Switch between the setting pages")

    private const val protocolPrefix = "[HTPv1.0]"
    val ignoreBlocks = linkedSetOf(
        Blocks.STANDING_SIGN,
        Blocks.WALL_SIGN,
        Blocks.STANDING_BANNER,
        Blocks.WALL_BANNER,
        Blocks.BEDROCK,
        Blocks.END_PORTAL,
        Blocks.END_PORTAL_FRAME,
        Blocks.PORTAL
    )

    // build settings
    private val mode by setting("Mode", Mode.HIGHWAY, { page == Page.BUILD }, description = "Choose the structure")
    private val clearSpace by setting("Clear Space", true, { page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Clears out the tunnel if necessary")
    private val width by setting("Width", 6, 1..11, 1, { page == Page.BUILD }, description = "Sets the width of blueprint")
    private val height by setting("Height", 4, 1..6, 1, { page == Page.BUILD && clearSpace }, description = "Sets height of blueprint")
    private val railing by setting("Railing", true, { page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Adds a railing / rim / border to the highway")
    private val railingHeight by setting("Railing Height", 1, 1..4, 1, { railing && page == Page.BUILD && mode == Mode.HIGHWAY }, description = "Sets height of railing")
    private val cornerBlock by setting("Corner Block", false, { page == Page.BUILD && (mode == Mode.HIGHWAY || mode == Mode.TUNNEL) }, description = "If activated will break the corner in tunnel or place a corner while paving")
//    val ignoreBlocks = setting(CollectionSetting("IgnoreList", defaultIgnoreList, { false }))

    // behavior settings
    private val interacting by setting("Rotation Mode", RotationMode.SPOOF, { page == Page.BEHAVIOR }, description = "Force view client side, only server side or no interaction at all")
    private val dynamicDelay by setting("Dynamic Place Delay", true, { page == Page.BEHAVIOR }, description = "Slows down on failed placement attempts")
    private var placeDelay by setting("Place Delay", 3, 1..20, 1, { page == Page.BEHAVIOR }, description = "Sets the delay ticks between placement tasks")
    private var breakDelay by setting("Break Delay", 1, 1..20, 1, { page == Page.BEHAVIOR }, description = "Sets the delay ticks between break tasks")
    private val illegalPlacements by setting("Illegal Placements", false, { page == Page.BEHAVIOR }, description = "Do not use on 2b2t. Tries to interact with invisible surfaces")
    private val bridging by setting("Bridging", true, { page == Page.BEHAVIOR }, description = "Tries to bridge / scaffold when stuck placing")
    private val placementSearch by setting("Place Deep Search", 1, 1..20, 1, { page == Page.BEHAVIOR }, description = "Attempts to find a support block for placing against")
    private val maxBreaks by setting("Multi Break", 3, 1..8, 1, { page == Page.BEHAVIOR }, description = "Breaks multiple instant breaking blocks per tick in view")
    private val toggleInventoryManager by setting("Toggle InvManager", false, { page == Page.BEHAVIOR }, description = "Activates InventoryManager on enable")
    private val toggleAutoObsidian by setting("Toggle AutoObsidian", true, { page == Page.BEHAVIOR }, description = "Activates AutoObsidian on enable")
    private val taskTimeout by setting("Task Timeout", 8, 0..20, 1, { page == Page.BEHAVIOR }, description = "Timeout for waiting for the server to try again")
    private val rubberbandTimeout by setting("Rubberband Timeout", 50, 5..100, 5, { page == Page.BEHAVIOR }, description = "Timeout for pausing after a lag")
    private val maxReach by setting("Max Reach", 4.9f, 1.0f..6.0f, 0.1f, { page == Page.BEHAVIOR }, description = "Sets the range of the blueprint. Decrease when tasks fail!")
    private val emptyDisable by setting("Disable on no tool", false, { page == Page.BEHAVIOR }, description = "Disables module when pickaxes are out")

    // skynet
    private val skynet by setting("Skynet", false, { page == Page.SKYNET }, description = "Makes HighwayBots communicate over $protocolPrefix")
    private val friends by setting("Only Friends", true, { page == Page.SKYNET }, description = "Only communicate with players that were added as friends")
    private val whisperDelay by setting("Whisper Delay", 100, 1..1000, 10, { page == Page.SKYNET }, description = "Sets the delay ticks between whispers")
    private val testLane by setting("Test Lane", 1, -10..10, 1, { page == Page.SKYNET }, description = "Sets the delay ticks between whispers")
    private val noWhispersShown by setting("Hide whispers", false, { page == Page.SKYNET }, description = "Hides HTProtocol whispers")
    private val suppressWhisper by setting("Suppress whispers", false, { page == Page.SKYNET }, description = "Suppresses whispers for debugging")
    private val debugLog by setting("Debug", true, { page == Page.SKYNET }, description = "Shows HTProtocol debug logs")

    // stats
    private val anonymizeStats by setting("Anonymize", false, { page == Page.STATS }, description = "Censors all coordinates in HUD and Chat.")
    private val resetStats = setting("Reset Stats", false, { page == Page.STATS }, description = "Resets the stats")
    private val simpleMovingAverageRange by setting("Moving Average", 60f, 5f..600f, 5f, { page == Page.STATS }, description = "Sets the timeframe of the average in seconds")
    private val showPerformance by setting("Show Performance", true, { page == Page.STATS }, description = "Toggles the Performance section in HUD")
    private val showEnvironment by setting("Show Environment", true, { page == Page.STATS }, description = "Toggles the Environment section in HUD")
    private val showTask by setting("Show Task", true, { page == Page.STATS }, description = "Toggles the Task section in HUD")
    private val showEstimations by setting("Show Estimations", true, { page == Page.STATS }, description = "Toggles the Estimations section in HUD")
    private val showSkynet by setting("Show Skynet", true, { page == Page.STATS && skynet }, description = "Toggles Skynet section in HUD")

    // config
    private val fakeSounds by setting("Fake Sounds", true, { page == Page.CONFIG }, description = "Adds artificial sounds to the actions")
    private val info by setting("Show Info", true, { page == Page.CONFIG }, description = "Prints session stats in chat")
    private val printDebug by setting("Show Queue", false, { page == Page.CONFIG }, description = "Shows task queue in HUD")
    private val debugMessages by setting("Debug Messages", DebugMessages.IMPORTANT, { page == Page.CONFIG }, description = "Sets the debug log depth level")
    private val goalRender by setting("Goal Render", false, { page == Page.CONFIG }, description = "Renders the baritone goal")
    private val filled by setting("Filled", true, { page == Page.CONFIG }, description = "Renders colored task surfaces")
    private val outline by setting("Outline", true, { page == Page.CONFIG }, description = "Renders colored task outlines")
    private val aFilled by setting("Filled Alpha", 26, 0..255, 1, { filled && page == Page.CONFIG }, description = "Sets the opacity")
    private val aOutline by setting("Outline Alpha", 91, 0..255, 1, { outline && page == Page.CONFIG }, description = "Sets the opacity")

    private enum class Mode {
        HIGHWAY, FLAT, TUNNEL
    }

    private enum class Page {
        BUILD, BEHAVIOR, SKYNET, STATS, CONFIG
    }

    @Suppress("UNUSED")
    private enum class RotationMode {
        OFF, SPOOF, VIEW_LOCK
    }

    private enum class DebugMessages {
        OFF, IMPORTANT, ALL
    }

    private enum class Command {
        HANDSHAKE, ASSIGN_STATUS
    }

    enum class Job {
        NONE, DIGGER, PAVER, SCAFFOLDER
    }

    enum class Rank {
        NONE, MASTER, SLAVE
    }

    // internal settings
    var material: Block = Blocks.OBSIDIAN
    var fillerMat: Block = Blocks.NETHERRACK
    private var baritoneSettingAllowPlace = false
    private var baritoneSettingRenderGoal = false

    // Blue print
    private var startingDirection = Direction.NORTH
    private var currentBlockPos = BlockPos(0, -1, 0)
    private var startingBlockPos = BlockPos(0, -1, 0)
    private val blueprint = LinkedHashMap<BlockPos, Block>()

    // State
    private val rubberbandTimer = TickTimer(TimeUnit.TICKS)
    private var active = false
    private var waitTicks = 0
    private var extraPlaceDelay = 0

    // Rotation
    private var lastHitVec = Vec3d.ZERO
    private val rotateTimer = TickTimer(TimeUnit.TICKS)

    // Pathing
    var goal: GoalNear? = null; private set

    // Skynet
    private val botSet = LinkedHashSet<Bot>()
    private var rank = Rank.NONE
    private var job = Job.NONE
    private var lane = 0
    private val whisperTimer = TickTimer(TimeUnit.TICKS)
    private val pendingWhispers: Queue<Pair<String, String>> = LinkedList()

    // Tasks
    private val pendingTasks = LinkedHashMap<BlockPos, BlockTask>()
    private val doneTasks = LinkedHashMap<BlockPos, BlockTask>()
    private var sortedTasks: List<BlockTask> = emptyList()
    var lastTask: BlockTask? = null; private set

    // Stats
    private val simpleMovingAveragePlaces = ArrayDeque<Long>()
    private val simpleMovingAverageBreaks = ArrayDeque<Long>()
    private val simpleMovingAverageDistance = ArrayDeque<Long>()
    private var totalBlocksPlaced = 0
    private var totalBlocksBroken = 0
    private var totalDistance = 0.0
    private var runtimeMilliSeconds = 0
    private var prevFood = 0
    private var foodLoss = 1
    private var materialLeft = 0
    private var fillerMatLeft = 0
    private var lastToolDamage = 0
    private var durabilityUsages = 0

    private val renderer = ESPRenderer()

    override fun isActive(): Boolean {
        return isEnabled && active
    }

    init {
        ignoreBlocks.addAll(shulkerList)

        onEnable {
            runSafeR {
                /* Turn on inventory manager if the users wants us to control it */
                if (toggleInventoryManager && InventoryManager.isDisabled) {
                    InventoryManager.enable()
                }

                /* Turn on Auto Obsidian if the user wants us to control it. */
                if (toggleAutoObsidian && AutoObsidian.isDisabled && mode != Mode.TUNNEL) {
                    AutoObsidian.enable()
                }

                startingBlockPos = player.flooredPosition
                currentBlockPos = startingBlockPos
                startingDirection = Direction.fromEntity(Companion.mc.player)

                baritoneSettingAllowPlace = BaritoneUtils.settings?.allowPlace?.value ?: true
                BaritoneUtils.settings?.allowPlace?.value = false

                if (!goalRender) {
                    baritoneSettingRenderGoal = BaritoneUtils.settings?.renderGoal?.value ?: true
                    BaritoneUtils.settings?.renderGoal?.value = false
                }

                refreshData()
                printEnable()
            } ?: disable()
        }

        onDisable {
            runSafe {
                /* Turn off inventory manager if the users wants us to control it */
                if (toggleInventoryManager && InventoryManager.isEnabled) {
                    InventoryManager.disable()
                }

                /* Turn off auto obsidian if the user wants us to control it */
                if (toggleAutoObsidian && AutoObsidian.isEnabled) {
                    AutoObsidian.disable()
                }

                BaritoneUtils.settings?.allowPlace?.value = baritoneSettingAllowPlace
                if (!goalRender) BaritoneUtils.settings?.renderGoal?.value = baritoneSettingRenderGoal

                active = false
                goal = null
                lastTask = null
                totalDistance += startingBlockPos.distanceTo(currentBlockPos)

                printDisable()
            }
        }

        resetStats.listeners.add {
            if (resetStats.value) {
                resetStats()
                resetStats.value = false
            }
        }
    }

    private fun printEnable() {
        if (info) {
            MessageSendHelper.sendRawChatMessage("    §9> §7Direction: §a${startingDirection.displayName} / ${startingDirection.displayNameXY}§r")

            if (!anonymizeStats) {
                if (startingDirection.isDiagonal) {
                    MessageSendHelper.sendRawChatMessage("    §9> §7Axis offset: §a${startingBlockPos.x} ${startingBlockPos.z}§r")
                } else {
                    if (startingDirection == Direction.NORTH || startingDirection == Direction.SOUTH) {
                        MessageSendHelper.sendRawChatMessage("    §9> §7Axis offset: §a${startingBlockPos.x}§r")
                    } else {
                        MessageSendHelper.sendRawChatMessage("    §9> §7Axis offset: §a${startingBlockPos.z}§r")
                    }
                }
            }

            if (startingBlockPos.y != 120 && mode != Mode.TUNNEL) {
                MessageSendHelper.sendRawChatMessage("    §9> §cCheck altitude and make sure to build at Y: 120 for the correct height")
            }

            if (AntiHunger.isEnabled) {
                MessageSendHelper.sendRawChatMessage("    §9> §cAntiHunger does slow down block interactions.")
            }

        }
    }

    private fun printDisable() {
        if (info) {
            MessageSendHelper.sendRawChatMessage("    §9> §7Placed blocks: §a$totalBlocksPlaced§r")
            MessageSendHelper.sendRawChatMessage("    §9> §7Destroyed blocks: §a$totalBlocksBroken§r")
            MessageSendHelper.sendRawChatMessage("    §9> §7Distance: §a${startingBlockPos.distanceTo(currentBlockPos).toInt()}§r")
        }
    }

    init {
        safeListener<PacketEvent.Receive> {
            when (it.packet) {
                is SPacketBlockChange -> {
                    val pos = it.packet.blockPosition
                    if (!isInsideBlueprint(pos)) return@safeListener

                    val prev = world.getBlockState(pos).block
                    val new = it.packet.getBlockState().block

                    if (prev != new) {
                        val task = pendingTasks[pos] ?: return@safeListener

                        when (task.taskState) {
                            TaskState.PENDING_BREAK, TaskState.BREAKING -> {
                                if (new == Blocks.AIR) {
                                    task.updateState(TaskState.BROKEN)
                                }
                            }
                            TaskState.PENDING_PLACE -> {
                                if (task.block != Blocks.AIR && task.block == new) {
                                    task.updateState(TaskState.PLACED)
                                }
                            }
                            else -> {
                                // Ignored
                            }
                        }
                    }
                }
                is SPacketPlayerPosLook -> {
                    rubberbandTimer.reset()
                }
                is SPacketSetSlot -> {
                    val currentToolDamage = it.packet.stack.itemDamage
                    if (lastToolDamage < currentToolDamage && currentToolDamage - lastToolDamage < 100) {
                        durabilityUsages += currentToolDamage - lastToolDamage
                    }
                    lastToolDamage = it.packet.stack.itemDamage
                }
            }
        }

        safeListener<RenderWorldEvent> {
            renderer.render(false)
        }

        safeListener<ClientChatReceivedEvent> { event ->
            val args = event.message.unformattedText.split(" ")
            if (args.size > 2) {
                if (isCommand(args)) {
                    handleCommand(args[0], args[2])
                    if (noWhispersShown) event.isCanceled = true
                }
            }
        }

        safeListener<TickEvent.ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.START) return@safeListener

            updateRenderer()
            updateFood()
            if (skynet) skynetHandler()

            if (!rubberbandTimer.tick(rubberbandTimeout.toLong(), false) ||
                AutoObsidian.isActive() ||
                AutoEat.eating ||
                player.isCreative && player.serverBrand.contains("2b2t")) {
                refreshData()
                return@safeListener
            }

            if (!active) {
                active = true
                BaritoneUtils.primary?.pathingControlManager?.registerProcess(HighwayToolsProcess)
            } else {
                runtimeMilliSeconds += 50
                updateDequeues()
            }

            doPathing()
            runTasks()

            doRotation()
        }
    }

    private fun SafeClientEvent.updateRenderer() {
        renderer.clear()
        renderer.aFilled = if (filled) aFilled else 0
        renderer.aOutline = if (outline) aOutline else 0

//        renderer.add(world.getBlockState(currentBlockPos).getSelectedBoundingBox(world, currentBlockPos), ColorHolder(255, 255, 255))

        for (blockTask in pendingTasks.values) {
            if (blockTask.taskState == TaskState.DONE) continue
            renderer.add(world.getBlockState(blockTask.blockPos).getSelectedBoundingBox(world, blockTask.blockPos), blockTask.taskState.color)
        }

        for (blockTask in doneTasks.values) {
            if (blockTask.block == Blocks.AIR) continue
            renderer.add(world.getBlockState(blockTask.blockPos).getSelectedBoundingBox(world, blockTask.blockPos), blockTask.taskState.color)
        }
    }

    private fun SafeClientEvent.updateFood() {
        val currentFood = player.foodStats.foodLevel
        if (currentFood != prevFood) {
            if (currentFood < prevFood) foodLoss++
            prevFood = currentFood
        }
    }

    private fun updateDequeues() {
        while (simpleMovingAveragePlaces.isNotEmpty() && System.currentTimeMillis() - simpleMovingAveragePlaces.first() > 1000L * simpleMovingAverageRange) {
            simpleMovingAveragePlaces.removeFirst()
        }
        while (simpleMovingAverageBreaks.isNotEmpty() && System.currentTimeMillis() - simpleMovingAverageBreaks.first() > 1000L * simpleMovingAverageRange) {
            simpleMovingAverageBreaks.removeFirst()
        }
        while (simpleMovingAverageDistance.isNotEmpty() && System.currentTimeMillis() - simpleMovingAverageDistance.first() > 1000L * simpleMovingAverageRange) {
            simpleMovingAverageDistance.removeFirst()
        }
    }

    private fun SafeClientEvent.doRotation() {
        if (rotateTimer.tick(20L, false)) return
        val rotation = lastHitVec?.let { getRotationTo(it) } ?: return

        when (interacting) {
            RotationMode.SPOOF -> {
                val packet = PlayerPacketManager.PlayerPacket(rotating = true, rotation = rotation)
                PlayerPacketManager.addPacket(this@HighwayTools, packet)
            }
            RotationMode.VIEW_LOCK -> {
                player.rotationYaw = rotation.x
                player.rotationPitch = rotation.y
            }
            else -> {
                // RotationMode.OFF
            }
        }
    }

    private fun SafeClientEvent.refreshData(originPos: BlockPos = currentBlockPos) {
        doneTasks.clear()
        pendingTasks.clear()
        lastTask = null

        blueprint.clear()
        generateBluePrint(originPos)

        for ((pos, block) in blueprint) {
            if (block == Blocks.AIR) {
                addTaskClear(pos)
            } else {
                addTaskBuild(pos, block)
            }
        }
    }

    private fun SafeClientEvent.addTaskBuild(pos: BlockPos, block: Block) {
        val blockState = world.getBlockState(pos)

        when {
            blockState.block == block -> {
                addTaskToDone(pos, block)
            }
            blockState.material.isReplaceable -> {
                addTaskToPending(pos, TaskState.PLACE, block)
            }
            else -> {
                addTaskToPending(pos, TaskState.BREAK, block)
            }
        }
    }

    private fun SafeClientEvent.addTaskClear(pos: BlockPos) {
        if (world.isAirBlock(pos)) {
            addTaskToDone(pos, Blocks.AIR)
        } else {
            addTaskToPending(pos, TaskState.BREAK, Blocks.AIR)
        }
    }

    private fun SafeClientEvent.generateBluePrint(feetPos: BlockPos) {
        val basePos = feetPos.down()

        if (mode != Mode.FLAT) {
            val zDirection = startingDirection
            val xDirection = zDirection.clockwise(if (zDirection.isDiagonal) 1 else 2)

            for (x in -maxReach.floorToInt()..maxReach.ceilToInt()) {
                val thisPos = basePos.add(zDirection.directionVec.multiply(x))
                generateClear(thisPos, xDirection)
                if (mode != Mode.TUNNEL) generateBase(thisPos, xDirection)
            }
            if (mode == Mode.TUNNEL) {
                if (startingDirection.isDiagonal) {
                    for (x in 1..maxReach.floorToInt()) {
                        blueprint[basePos.add(zDirection.directionVec.multiply(x))] = fillerMat
                    }
                } else {
                    for (x in 1..maxReach.floorToInt()) {
                        val pos = basePos.add(zDirection.directionVec.multiply(x))
                        blueprint[pos] = fillerMat
                        blueprint[pos.add(startingDirection.clockwise(4).directionVec)] = fillerMat
                    }
                }
            }

            pickTasksInRange()
        } else {
            generateFlat(basePos)
        }
    }

    private fun SafeClientEvent.pickTasksInRange() {
        val eyePos = player.getPositionEyes(1f)

        blueprint.keys.removeIf {
            eyePos.distanceTo(it) > maxReach - 0.7
        }
    }

    private fun generateClear(basePos: BlockPos, xDirection: Direction) {
        if (!clearSpace) return

        for (w in 0 until width) {
            for (h in 0 until height) {
                val x = w - width / 2
                val pos = basePos.add(xDirection.directionVec.multiply(x)).up(h)

                if (mode == Mode.HIGHWAY && h == 0 && isRail(w)) {
                    continue
                }

                if (mode == Mode.HIGHWAY) {
                    blueprint[pos] = Blocks.AIR
                } else {
                    if (!(isRail(w) && h == 0 && !cornerBlock)) blueprint[pos.up()] = Blocks.AIR
                }
            }
        }
    }

    private fun generateBase(basePos: BlockPos, xDirection: Direction) {
        for (w in 0 until width) {
            val x = w - width / 2
            val pos = basePos.add(xDirection.directionVec.multiply(x))

            if (mode == Mode.HIGHWAY && isRail(w)) {
                val startHeight = if (cornerBlock) 0 else 1
                for (y in startHeight..railingHeight) {
                    blueprint[pos.up(y)] = material
                }
            } else {
                blueprint[pos] = material
            }
        }
    }

    private fun isRail(w: Int) = railing && w !in 1 until width - 1

    private fun generateFlat(basePos: BlockPos) {
        // Base
        for (w1 in 0 until width) {
            for (w2 in 0 until width) {
                val x = w1 - width / 2
                val z = w2 - width / 2
                val pos = basePos.add(x, 0, z)

                blueprint[pos] = material
            }
        }

        // Clear
        if (!clearSpace) return
        for (w1 in -width..width) {
            for (w2 in -width..width) {
                for (y in 1 until height) {
                    val x = w1 - width / 2
                    val z = w2 - width / 2
                    val pos = basePos.add(x, y, z)

                    blueprint[pos] = Blocks.AIR
                }
            }
        }
    }

    private fun addTaskToPending(blockPos: BlockPos, taskState: TaskState, material: Block) {
        pendingTasks[blockPos] = (BlockTask(blockPos, taskState, material))
    }

    private fun addTaskToDone(blockPos: BlockPos, material: Block) {
        doneTasks[blockPos] = (BlockTask(blockPos, TaskState.DONE, material))
    }

    private fun SafeClientEvent.skynetHandler() {
        if (whisperTimer.tick(whisperDelay * 1L) && pendingWhispers.isNotEmpty()) {
            val pendingCommand = pendingWhispers.poll()
            botSet.forEach {
                if (it.name == pendingCommand.second) sendServerMessage(pendingCommand.first)
            }
        }

        val players = LinkedHashSet(world.playerEntities)

        players.removeIf {
            it.isFakeOrSelf || (!friends && FriendManager.isFriend(it.name))
        }

        players.forEach { player ->
            if (botSet.any { it.player == player }) {
                botSet.add(Bot(player, player.name, Rank.NONE, Job.NONE, 0))
                if (isEnabled) handshake(player.name)
            }
        }

        botSet.removeIf {
            !players.contains(it.player)
        }
    }

    private fun addPendingCommand(command: Command, player: String, data: String = "") {
        val commandInfo = if (data.isBlank()) {
            "$command"
        } else {
            "$command > $data"
        }

        val commandMessage = "$protocolPrefix ${toBase64(commandInfo)}"
        if (debugLog) MessageSendHelper.sendChatMessage("$protocolPrefix $player > $commandInfo")
        if (!suppressWhisper) pendingWhispers.add(Pair("/w $player $commandMessage", player))
    }

    private fun handshake(player: String) {
        addPendingCommand(Command.HANDSHAKE, player, "$rank $job $lane ${botSet.size}")
    }

    private fun assignStatus(player: String, botRank: Rank, botJob: Job, botLane: Int) {
        addPendingCommand(Command.ASSIGN_STATUS, player, "$botRank $botJob $botLane")
    }

    private fun SafeClientEvent.isCommand(args: List<String>): Boolean {
        return args[0] != player.name && args[1] == "whispers:" && args[2].startsWith(protocolPrefix)
    }

    private fun handleCommand(player: String, command: String) {
        val decoded = fromBase64(command).split(" ")
        if (debugLog) MessageSendHelper.sendChatMessage("$protocolPrefix $player > ${fromBase64(command)}")

        when (Command.valueOf(decoded[0])) {
            Command.HANDSHAKE -> {
                if (player != "Avanatiker" || Rank.valueOf(decoded[1]) != Rank.MASTER) {
                    var index = 0
                    botSet.forEach {
                        if (it.name == player) {
                            it.rank = Rank.SLAVE
                            it.job = Job.PAVER
                            it.lane = index.rem(width - 2)
                            index = botSet.indexOf(it)
                        }
                    }
                    assignStatus(player, Rank.SLAVE, Job.PAVER, index.rem(width - 2))
                }
            }
            Command.ASSIGN_STATUS -> {
                rank = Rank.valueOf(decoded[1])
                job = Job.valueOf(decoded[2])
                lane = decoded[3].toInt()
            }
        }
    }

    private fun toBase64(string: String): String {
        return Base64.getEncoder().encodeToString(toByteArray(string))
    }

    private fun fromBase64(string: String): String {
        return String(Base64.getDecoder().decode(string))
    }

    private fun getLaneOffset(pos: BlockPos): BlockPos {
        return if (skynet) {
            pos.add(startingDirection.clockwise(7).directionVec.multiply(testLane))
        } else {
            pos
        }
    }

    private fun SafeClientEvent.doPathing() {
        val nextPos = getNextPos()

        if (player.flooredPosition.distanceTo(getLaneOffset(nextPos)) < 2.0) {
            currentBlockPos = nextPos
        }

        goal = if (skynet && botSet.isNotEmpty()) {
            GoalNear(getLaneOffset(nextPos), 0)
        } else {
            GoalNear(nextPos, 0)
        }
    }

    private fun SafeClientEvent.getNextPos(): BlockPos {
        var nextPos = currentBlockPos

        val possiblePos = nextPos.add(startingDirection.directionVec)

        if (!isTaskDoneOrNull(getLaneOffset(possiblePos), false) ||
            !isTaskDoneOrNull(getLaneOffset(possiblePos).up(), false) ||
            !isTaskDoneOrNull(getLaneOffset(possiblePos).down(), true)) return nextPos

        if (checkTasks(getLaneOffset(possiblePos.up()))) nextPos = possiblePos

        if (currentBlockPos != nextPos) {
            simpleMovingAverageDistance.add(System.currentTimeMillis())
            refreshData()
        }

        return nextPos
    }

    private fun SafeClientEvent.isTaskDoneOrNull(pos: BlockPos, solid: Boolean) =
        (pendingTasks[pos] ?: doneTasks[pos])?.let {
            it.taskState == TaskState.DONE
        } ?: run {
            if (solid) {
                !isPlaceable(pos, true)
            } else {
                world.isAirBlock(pos)
            }
        }

    private fun checkTasks(pos: BlockPos): Boolean {
        return pendingTasks.values.all {
            it.taskState == TaskState.DONE || pos.distanceTo(it.blockPos) < maxReach - 0.7
        }
    }

    private fun SafeClientEvent.runTasks() {
        if (pendingTasks.isEmpty()) {
            if (checkDoneTasks()) doneTasks.clear()
            refreshData()
        } else {
            waitTicks--
            for (task in pendingTasks.values) {
                doTask(task, true)
            }
            sortTasks()

            for (task in sortedTasks) {
                if (!checkStuckTimeout(task)) return
                if (task.taskState != TaskState.DONE && waitTicks > 0) return

                doTask(task, false)

                when (task.taskState) {
                    TaskState.DONE, TaskState.BROKEN, TaskState.PLACED -> {
                        continue
                    }
                    else -> {
                        break
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.checkDoneTasks(): Boolean {
        for (blockTask in doneTasks.values) {
            val block = world.getBlockState(blockTask.blockPos).block
            if (ignoreBlocks.contains(block)) continue

            when {
                blockTask.block == material && block != material -> return false
                mode == Mode.TUNNEL && blockTask.block == fillerMat && block != fillerMat -> return false
                blockTask.block == Blocks.AIR && block != Blocks.AIR -> return false
            }

        }
        return true
    }

    private fun SafeClientEvent.sortTasks() {
        val eyePos = mc.player.getPositionEyes(1.0f)

        when (job) {
            Job.NONE -> {
                sortedTasks = pendingTasks.values.sortedWith(
                    compareBy<BlockTask> {
                        it.taskState.ordinal
                    }.thenBy {
                        it.stuckTicks / 5
                    }.thenBy {
                        when (it.taskState) {
                            TaskState.PLACE, TaskState.LIQUID_SOURCE, TaskState.LIQUID_FLOW -> {
                                getBetterNeighbour(it.blockPos, placementSearch, maxReach, true).size
                            }
                            TaskState.BREAK -> { // ToDo: Check for most block interceptions when kick issue solved
                                0
                            }
                            else -> 0
                        }
                    }.thenBy { // ToDo: We need a function that makes a score out of those 3 parameters
                        startingBlockPos.distanceTo(it.blockPos).toInt() / 2
                    }.thenBy {
                        eyePos.distanceTo(it.blockPos)
                    }.thenBy {
                        lastHitVec?.distanceTo(it.blockPos)
                    }
                )
            }
            Job.PAVER -> {
                pendingTasks.values.forEach { it.shuffle() }
                sortedTasks = pendingTasks.values.sortedWith(
                    compareBy<BlockTask> {
                        it.taskState.ordinal
                    }.thenBy {
                        it.stuckTicks / 5
                    }.thenBy {
                        it.shuffle
                    }
                )
            }
            else -> {

            }
        }
    }

    private fun SafeClientEvent.checkStuckTimeout(blockTask: BlockTask): Boolean {
        val timeout = blockTask.taskState.stuckTimeout

        if (blockTask.stuckTicks > timeout) {
            when (blockTask.taskState) {
                TaskState.PENDING_BREAK -> {
                    blockTask.updateState(TaskState.BREAK)
                }
                TaskState.PENDING_PLACE -> {
                    blockTask.updateState(TaskState.PLACE)
                }
                else -> {
                    if (debugMessages != DebugMessages.OFF) {
                        if (!anonymizeStats) {
                            MessageSendHelper.sendChatMessage("Stuck while ${blockTask.taskState}@(${blockTask.blockPos.asString()}) for more then $timeout ticks (${blockTask.stuckTicks}), refreshing data.")
                        } else {
                            MessageSendHelper.sendChatMessage("Stuck while ${blockTask.taskState} for more then $timeout ticks (${blockTask.stuckTicks}), refreshing data.")
                        }
                    }

                    if (dynamicDelay && blockTask.taskState == TaskState.PLACE && extraPlaceDelay < 10) extraPlaceDelay += 1

                    refreshData()
                    return false
                }
            }
        }

        return true
    }

    private fun SafeClientEvent.doTask(blockTask: BlockTask, updateOnly: Boolean) {
        if (!updateOnly) blockTask.onTick()

        // ToDo: Choose place task with least attempts
        when (blockTask.taskState) {
            TaskState.DONE -> {
                doDone(blockTask)
            }
            TaskState.BREAKING -> {
                doBreaking(blockTask, updateOnly)
            }
            TaskState.BROKEN -> {
                doBroken(blockTask)
            }
            TaskState.PLACED -> {
                doPlaced(blockTask)
            }
            TaskState.BREAK -> {
                doBreak(blockTask, updateOnly)
            }
            TaskState.PLACE, TaskState.LIQUID_SOURCE, TaskState.LIQUID_FLOW -> {
                doPlace(blockTask, updateOnly)
            }
            TaskState.PENDING_BREAK, TaskState.PENDING_PLACE -> {
                if (!updateOnly && debugMessages == DebugMessages.ALL) {
                    MessageSendHelper.sendChatMessage("$chatName Currently waiting for blockState updates...")
                }
                blockTask.onStuck()
            }
        }
    }

    private fun doDone(blockTask: BlockTask) {
        pendingTasks[blockTask.blockPos]
        doneTasks[blockTask.blockPos] = blockTask
    }

    private fun SafeClientEvent.doBreaking(blockTask: BlockTask, updateOnly: Boolean) {
        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                waitTicks = breakDelay
                blockTask.updateState(TaskState.BROKEN)
                return
            }
            is BlockLiquid -> {
                val filler = if (fillerMatLeft == 0 || isInsideBlueprintBuild(blockTask.blockPos)) {
                    material
                } else {
                    fillerMat
                }

                if (world.getBlockState(blockTask.blockPos).getValue(BlockLiquid.LEVEL) != 0) {
                    blockTask.updateState(TaskState.LIQUID_FLOW)
                    blockTask.updateMaterial(filler)
                } else {
                    blockTask.updateState(TaskState.LIQUID_SOURCE)
                    blockTask.updateMaterial(filler)
                }

                return
            }
        }

        if (!updateOnly) {
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doBroken(blockTask: BlockTask) {
        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                totalBlocksBroken++
                simpleMovingAverageBreaks.add(System.currentTimeMillis())

                if (blockTask.block == Blocks.AIR) {
                    if (fakeSounds) {
                        val soundType = blockTask.block.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                        world.playSound(player, blockTask.blockPos, soundType.breakSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                    }
                    blockTask.updateState(TaskState.DONE)
                } else {
                    blockTask.updateState(TaskState.PLACE)
                }
            }
            else -> {
                blockTask.updateState(TaskState.BREAK)
            }
        }
    }

    private fun SafeClientEvent.doPlaced(blockTask: BlockTask) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        when {
            blockTask.block == currentBlock && currentBlock != Blocks.AIR -> {
                totalBlocksPlaced++
                simpleMovingAveragePlaces.add(System.currentTimeMillis())

                if (dynamicDelay && extraPlaceDelay > 0) extraPlaceDelay -= 1

                blockTask.updateState(TaskState.DONE)
                if (fakeSounds) {
                    val soundType = currentBlock.getSoundType(world.getBlockState(blockTask.blockPos), world, blockTask.blockPos, player)
                    world.playSound(player, blockTask.blockPos, soundType.placeSound, SoundCategory.BLOCKS, (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f)
                }
            }
            blockTask.block == currentBlock && currentBlock == Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            blockTask.block == Blocks.AIR && currentBlock != Blocks.AIR -> {
                blockTask.updateState(TaskState.BREAK)
            }
            else -> {
                blockTask.updateState(TaskState.PLACE)
            }
        }
    }

    private fun SafeClientEvent.doBreak(blockTask: BlockTask, updateOnly: Boolean) {
        // ignore blocks
        if (ignoreBlocks.contains(world.getBlockState(blockTask.blockPos).block)) {
            blockTask.updateState(TaskState.DONE)
        }

        when (world.getBlockState(blockTask.blockPos).block) {
            Blocks.AIR -> {
                if (blockTask.block == Blocks.AIR) {
                    blockTask.updateState(TaskState.BROKEN)
                    return
                } else {
                    blockTask.updateState(TaskState.PLACE)
                    return
                }
            }
            is BlockLiquid -> {
                val filler = if (fillerMatLeft == 0 || isInsideBlueprintBuild(blockTask.blockPos)) material
                else fillerMat

                if (world.getBlockState(blockTask.blockPos).getValue(BlockLiquid.LEVEL) != 0) {
                    blockTask.updateState(TaskState.LIQUID_FLOW)
                    blockTask.updateMaterial(filler)
                } else {
                    blockTask.updateState(TaskState.LIQUID_SOURCE)
                    blockTask.updateMaterial(filler)
                }
            }
        }

        if (!updateOnly) {
            if (handleLiquid(blockTask)) return
            swapOrMoveBestTool(blockTask)
            mineBlock(blockTask)
        }
    }

    private fun SafeClientEvent.doPlace(blockTask: BlockTask, updateOnly: Boolean) {
        val currentBlock = world.getBlockState(blockTask.blockPos).block

        if (bridging && player.positionVector.distanceTo(currentBlockPos) < 1 && shouldBridge()) {
            val factor = if (startingDirection.isDiagonal) {
                0.51
            } else {
                0.505
            }
            val target = currentBlockPos.toVec3dCenter().add(Vec3d(startingDirection.directionVec).scale(factor))
            player.motionX = (target.x - player.posX).coerceIn(-0.2, 0.2)
            player.motionZ = (target.z - player.posZ).coerceIn(-0.2, 0.2)
        }

        when (blockTask.block) {
            material -> {
                if (currentBlock == material) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                } else if (currentBlock != Blocks.AIR && !isLiquid(blockTask.blockPos)) {
                    blockTask.updateState(TaskState.BREAK)
                    return
                }
            }
            fillerMat -> {
                if (currentBlock != Blocks.AIR && !isLiquid(blockTask.blockPos)) {
                    blockTask.updateState(TaskState.PLACED)
                    return
                }
            }
            Blocks.AIR -> {
                if (!isLiquid(blockTask.blockPos)) {
                    if (currentBlock != Blocks.AIR) {
                        blockTask.updateState(TaskState.BREAK)
                    } else {
                        blockTask.updateState(TaskState.BROKEN)
                    }
                    return
                }
            }
        }

        if (!updateOnly) {
            if (!isPlaceable(blockTask.blockPos)) {
                if (debugMessages != DebugMessages.OFF) {
                    if (!anonymizeStats) {
                        MessageSendHelper.sendChatMessage("Invalid place position: ${blockTask.blockPos}. Removing task")
                    } else {
                        MessageSendHelper.sendChatMessage("Invalid place position. Removing task")
                    }
                }
                pendingTasks.remove(blockTask.blockPos)
                return
            }

            if (!swapOrMoveBlock(blockTask)) {
                blockTask.onStuck()
                return
            }

            placeBlock(blockTask)
        }
    }

    private fun SafeClientEvent.swapOrMoveBlock(blockTask: BlockTask): Boolean {
        val success = swapToBlockOrMove(blockTask.block, predicateSlot = {
            it.item is ItemBlock
        })

        return if (!success) {
            MessageSendHelper.sendChatMessage("$chatName No ${blockTask.block.localizedName} was found in inventory")
            mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
            false
        } else {
            true
        }
    }

    private fun SafeClientEvent.placeBlock(blockTask: BlockTask) {
        val neighbours = if (illegalPlacements) {
            getBetterNeighbour(blockTask.blockPos, placementSearch, maxReach)
        } else {
            getBetterNeighbour(blockTask.blockPos, placementSearch, maxReach, true)
        }

        when (neighbours.size) {
            0 -> {
                if (debugMessages == DebugMessages.ALL) {
                    if (!anonymizeStats) {
                        MessageSendHelper.sendChatMessage("No neighbours found for ${blockTask.blockPos}")
                    } else {
                        MessageSendHelper.sendChatMessage("No neighbours found")
                    }
                }
                blockTask.stuck(21)
                return
            }
            1 -> {
                lastHitVec = WorldUtils.getHitVec(neighbours.last().second, neighbours.last().first)
                rotateTimer.reset()

                placeBlockNormal(blockTask, neighbours.last())
            }
            else -> {
                for (pair in neighbours) {
                    addTaskToPending(pair.second, TaskState.PLACE, fillerMat)
                }
            }
        }
    }

    private fun SafeClientEvent.placeBlockNormal(blockTask: BlockTask, pair: Pair<EnumFacing, BlockPos>) {
        val hitVecOffset = WorldUtils.getHitVecOffset(pair.first)
        val currentBlock = world.getBlockState(pair.second).block

        waitTicks = if (dynamicDelay) {
            placeDelay + extraPlaceDelay
        } else {
            placeDelay
        }
        blockTask.updateState(TaskState.PENDING_PLACE)

        if (currentBlock in blackList) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }

        defaultScope.launch {
            delay(20L)
            onMainThreadSafe {
                val placePacket = CPacketPlayerTryUseItemOnBlock(pair.second, pair.first, EnumHand.MAIN_HAND, hitVecOffset.x.toFloat(), hitVecOffset.y.toFloat(), hitVecOffset.z.toFloat())
                connection.sendPacket(placePacket)
                player.swingArm(EnumHand.MAIN_HAND)
            }

            if (currentBlock in blackList) {
                delay(20L)
                onMainThreadSafe {
                    connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                }
            }

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_PLACE) {
                blockTask.updateState(TaskState.PLACE)
                if (dynamicDelay && extraPlaceDelay < 10) extraPlaceDelay += 1
            }
        }
    }

    private fun SafeClientEvent.shouldBridge(): Boolean {
        var containsPlace = false
        for (task in sortedTasks) {
            if (task.taskState == TaskState.PLACE) {
                containsPlace = true
                if (getBetterNeighbour(task.blockPos, placementSearch, maxReach, true).isNotEmpty()) return false
            }
        }
        return containsPlace
    }

    private fun SafeClientEvent.getBestTool(blockTask: BlockTask): Slot? {
        return player.inventorySlots.asReversed().maxByOrNull {
            val stack = it.stack
            if (stack.isEmpty) {
                0.0f
            } else {
                var speed = stack.getDestroySpeed(world.getBlockState(blockTask.blockPos))

                if (speed > 1.0f) {
                    val efficiency = EnchantmentHelper.getEnchantmentLevel(Enchantments.EFFICIENCY, stack)
                    if (efficiency > 0) {
                        speed += efficiency * efficiency + 1.0f
                    }
                }

                speed
            }
        }
    }

    private fun SafeClientEvent.swapOrMoveBestTool(blockTask: BlockTask): Boolean {
        val slotFrom = getBestTool(blockTask)

        return if (slotFrom != null) {
            if (emptyDisable && slotFrom.stack.item != Items.DIAMOND_PICKAXE) {
                MessageSendHelper.sendChatMessage("$chatName No ${Items.DIAMOND_PICKAXE} was found in inventory, disable")
                mc.soundHandler.playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                disable()
            }
            slotFrom.toHotbarSlotOrNull()?.let {
                swapToSlot(it)
            } ?: run {
                val slotTo = player.hotbarSlots.firstEmpty()?.hotbarSlot ?: 0
                moveToHotbar(slotFrom.slotNumber, slotTo)
            }
            true
        } else {
            false
        }
    }

    private fun SafeClientEvent.handleLiquid(blockTask: BlockTask): Boolean {
        var foundLiquid = false
        for (side in EnumFacing.values()) {
            val neighbour = blockTask.blockPos.offset(side)
            val neighbourBlock = world.getBlockState(neighbour).block

            if (neighbourBlock is BlockLiquid) {
                val isFlowing = world.getBlockState(blockTask.blockPos).let {
                    it.block is BlockLiquid && it.getValue(BlockLiquid.LEVEL) != 0
                }

                if (player.distanceTo(neighbour) > maxReach) continue

                foundLiquid = true
                val found = ArrayList<Triple<BlockTask, TaskState, Block>>()
                val filler = if (isInsideBlueprintBuild(neighbour)) material else fillerMat

                for (task in pendingTasks.values) {
                    if (task.blockPos == neighbour) {
                        when (isFlowing) {
                            false -> found.add(Triple(task, TaskState.LIQUID_SOURCE, filler))
                            true -> found.add(Triple(task, TaskState.LIQUID_FLOW, filler))
                        }
                    }
                }

                if (found.isEmpty()) {
                    when (isFlowing) {
                        false -> addTaskToPending(neighbour, TaskState.LIQUID_SOURCE, filler)
                        true -> addTaskToPending(neighbour, TaskState.LIQUID_FLOW, filler)
                    }
                } else {
                    for (triple in found) {
                        triple.first.updateState(triple.second)
                        triple.first.updateMaterial(triple.third)
                    }
                }
            }
        }
        return foundLiquid
    }

    private fun SafeClientEvent.mineBlock(blockTask: BlockTask) {

        /* For fire, we just need to mine the top of the block below the fire */
        if (blockTask.block == Blocks.FIRE) {
            val blockBelowFire = blockTask.blockPos.down()
            if (getVisibleSides(blockBelowFire).contains(EnumFacing.UP)) {
                playerController.clickBlock(blockBelowFire, EnumFacing.UP)
                player.swingArm(EnumHand.MAIN_HAND)
                blockTask.updateState(TaskState.BREAKING)
            } else {
                blockTask.updateState(TaskState.LIQUID_FLOW)
            }
            return
        }

        val side = getMiningSide(blockTask.blockPos) ?: run {
            blockTask.onStuck()
            return
        }

        lastHitVec = WorldUtils.getHitVec(blockTask.blockPos, side)
        rotateTimer.reset()

        if (world.getBlockState(blockTask.blockPos).getPlayerRelativeBlockHardness(player, world, blockTask.blockPos) > 2.8) {
            mineBlockInstant(blockTask, side)
        } else {
            mineBlockNormal(blockTask, side)
        }
    }

    private fun mineBlockInstant(blockTask: BlockTask, side: EnumFacing) {
        waitTicks = breakDelay
        blockTask.updateState(TaskState.PENDING_BREAK)

        defaultScope.launch {
            delay(20L)
            sendMiningPackets(blockTask.blockPos, side)

            if (maxBreaks > 1) {
                tryMultiBreak(blockTask)
            }

            delay(50L * taskTimeout)
            if (blockTask.taskState == TaskState.PENDING_BREAK) {
                blockTask.updateState(TaskState.BREAK)
            }
        }
    }

    private fun tryMultiBreak(blockTask: BlockTask) {
        runSafe {
            val eyePos = player.getPositionEyes(1.0f)
            val viewVec = lastHitVec.subtract(eyePos).normalize()
            var breakCount = 1

            for (task in sortedTasks) {
                if (breakCount >= maxBreaks) break

                if (task == blockTask) continue
                if (task.taskState != TaskState.BREAK) continue
                if (world.getBlockState(task.blockPos).block != Blocks.NETHERRACK) continue

                val box = AxisAlignedBB(task.blockPos)
                val rayTraceResult = box.isInSight(eyePos, viewVec) ?: continue
                breakCount++

                defaultScope.launch {
                    sendMiningPackets(task.blockPos, rayTraceResult.sideHit)

                    delay(50L * taskTimeout)
                    if (blockTask.taskState == TaskState.PENDING_BREAK) {
                        blockTask.updateState(TaskState.BREAK)
                    }
                }
            }
        }
    }

    /* Dispatches a thread to mine any non-netherrack blocks generically */
    private fun mineBlockNormal(blockTask: BlockTask, side: EnumFacing) {
        if (blockTask.taskState == TaskState.BREAK) {
            blockTask.updateState(TaskState.BREAKING)
        }

        defaultScope.launch {
            delay(20L)
            sendMiningPackets(blockTask.blockPos, side)
        }
    }

    private suspend fun sendMiningPackets(pos: BlockPos, side: EnumFacing) {
        onMainThreadSafe {
            connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, side))
            connection.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, side))
            player.swingArm(EnumHand.MAIN_HAND)
        }
    }

    private fun isInsideBlueprint(pos: BlockPos): Boolean {
        return blueprint.containsKey(pos)
    }

    private fun isInsideBlueprintBuild(pos: BlockPos): Boolean {
        return blueprint[pos]?.let { it != Blocks.AIR } ?: false
    }

    fun printSettings() {
        StringBuilder(ignoreBlocks.size + 1).run {
            append("$chatName Settings" +
                "\n §9> §rMain material: §7${material.localizedName}" +
                "\n §9> §rFiller material: §7${fillerMat.localizedName}" +
                "\n §9> §rIgnored Blocks:")

            for (b in ignoreBlocks) append("\n     §9> §7${b.registryName}")

            MessageSendHelper.sendChatMessage(toString())
        }
    }

    fun SafeClientEvent.gatherStatistics(displayText: TextComponent) {


        val runtimeSec = (runtimeMilliSeconds / 1000) + 0.0001
        val distanceDone = startingBlockPos.distanceTo(currentBlockPos).toInt() + totalDistance

//        val statList = Gson().fromJson(StatList.MINE_BLOCK_STATS[0], Any::class.java)
//        MessageSendHelper.sendChatMessage(statList)

        if (showPerformance) gatherPerformance(displayText, runtimeSec, distanceDone)

        if (showEnvironment) gatherEnvironment(displayText)

        if (showTask) gatherTask(displayText)

        if (showEstimations) gatherEstimations(displayText, runtimeSec, distanceDone)

        if (showSkynet && skynet) gatherSkynet(displayText)

//        displayText.addLine("by Constructor#9948 aka Avanatiker", primaryColor)

        if (printDebug) {
            displayText.addLine("Pending", primaryColor)
            addTaskComponentList(displayText, sortedTasks)

            displayText.addLine("Done", primaryColor)
            addTaskComponentList(displayText, doneTasks.values)
        }
    }

    private fun gatherPerformance(displayText: TextComponent, runtimeSec: Double, distanceDone: Double) {
        val seconds = (runtimeSec % 60).toInt().toString().padStart(2, '0')
        val minutes = ((runtimeSec % 3600) / 60).toInt().toString().padStart(2, '0')
        val hours = (runtimeSec / 3600).toInt().toString().padStart(2, '0')

        displayText.addLine("Performance", primaryColor)
        displayText.add("    Runtime:", primaryColor)
        displayText.addLine("$hours:$minutes:$seconds", secondaryColor)
        displayText.add("    Placements / s: ", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format(totalBlocksPlaced / runtimeSec, simpleMovingAveragePlaces.size / simpleMovingAverageRange), secondaryColor)
        displayText.add("    Breaks / s:", primaryColor)
        displayText.addLine("%.2f SMA(%.2f)".format(totalBlocksBroken / runtimeSec, simpleMovingAverageBreaks.size / simpleMovingAverageRange), secondaryColor)
        displayText.add("    Distance km / h:", primaryColor)
        displayText.addLine("%.3f SMA(%.3f)".format((distanceDone / runtimeSec * 60 * 60) / 1000, ((simpleMovingAverageDistance.size / simpleMovingAverageRange) * 60 * 60) / 1000), secondaryColor)
        displayText.add("    Food level loss / h:", primaryColor)
        displayText.addLine("%.2f".format(totalBlocksBroken / foodLoss.toDouble()), secondaryColor)
        displayText.add("    Pickaxes / h:", primaryColor)
        displayText.addLine("%.2f".format((durabilityUsages / runtimeSec) * 60 * 60 / 1561), secondaryColor)
    }

    private fun gatherEnvironment(displayText: TextComponent) {
        displayText.addLine("Environment", primaryColor)
        if (!anonymizeStats) displayText.add("    Start:", primaryColor)
        if (!anonymizeStats) displayText.addLine("(${startingBlockPos.asString()})", secondaryColor)
        displayText.add("    Direction:", primaryColor)
        displayText.addLine("${startingDirection.displayName} / ${startingDirection.displayNameXY}", secondaryColor)
        displayText.add("    Blocks placed / destroyed:", primaryColor)
        displayText.addLine("$totalBlocksPlaced".padStart(6, '0') + " / " + "$totalBlocksBroken".padStart(6, '0'), secondaryColor)
        displayText.add("    Materials:", primaryColor)
        displayText.addLine("Main(${material.localizedName}) Filler(${fillerMat.localizedName})", secondaryColor)
        displayText.add("    Delays:", primaryColor)
        if (dynamicDelay) {
            displayText.addLine("Place(${placeDelay + extraPlaceDelay}) Break($breakDelay)", secondaryColor)
        } else {
            displayText.addLine("Place($placeDelay) Break($breakDelay)", secondaryColor)
        }
    }

    private fun gatherTask(displayText: TextComponent) {
        sortedTasks.firstOrNull()?.let {
            displayText.addLine("Task", primaryColor)
            displayText.add("    Status:", primaryColor)
            displayText.addLine("${it.taskState}", secondaryColor)
            displayText.add("    Target block:", primaryColor)
            displayText.addLine(it.block.localizedName, secondaryColor)
            if (!anonymizeStats) displayText.add("    Position:", primaryColor)
            if (!anonymizeStats) displayText.addLine("(${it.blockPos.asString()})", secondaryColor)
            displayText.add("    Ticks stuck:", primaryColor)
            displayText.addLine("${it.stuckTicks}", secondaryColor)
        }
    }

    private fun SafeClientEvent.gatherEstimations(displayText: TextComponent, runtimeSec: Double, distanceDone: Double) {
        when (mode) {
            Mode.HIGHWAY, Mode.FLAT -> {
                materialLeft = player.allSlots.countBlock(material)
                fillerMatLeft = player.allSlots.countBlock(fillerMat)
                val indirectMaterialLeft = 8 * player.allSlots.countBlock(Blocks.ENDER_CHEST)

                val pavingLeft = materialLeft / (totalBlocksPlaced.coerceAtLeast(1) / distanceDone.coerceAtLeast(1.0))

                // ToDo: Cache shulker count
//                  val pavingLeftAll = (materialLeft + indirectMaterialLeft) / ((totalBlocksPlaced + 0.001) / (distanceDone + 0.001))

                val secLeft = (pavingLeft).coerceAtLeast(0.0) / (startingBlockPos.distanceTo(currentBlockPos).toInt() / runtimeSec)
                val secondsLeft = (secLeft % 60).toInt().toString().padStart(2, '0')
                val minutesLeft = ((secLeft % 3600) / 60).toInt().toString().padStart(2, '0')
                val hoursLeft = (secLeft / 3600).toInt().toString().padStart(2, '0')

                displayText.addLine("Next refill", primaryColor)
                displayText.add("    ${material.localizedName}:", primaryColor)
                if (material == Blocks.OBSIDIAN) {
                    displayText.addLine("Direct($materialLeft) Indirect($indirectMaterialLeft)", secondaryColor)
                } else {
                    displayText.addLine("$materialLeft", secondaryColor)
                }
                displayText.add("    ${fillerMat.localizedName}:", primaryColor)
                displayText.addLine("$fillerMatLeft", secondaryColor)
                displayText.add("    Distance left:", primaryColor)
                displayText.addLine("${pavingLeft.toInt()}", secondaryColor)
                if (!anonymizeStats) displayText.add("    Destination:", primaryColor)
                if (!anonymizeStats) displayText.addLine("(${currentBlockPos.add(startingDirection.directionVec.multiply(pavingLeft.toInt())).asString()})", secondaryColor)
                displayText.add("    ETA:", primaryColor)
                displayText.addLine("$hoursLeft:$minutesLeft:$secondsLeft", secondaryColor)
            }
            Mode.TUNNEL -> {
                val pickaxesLeft = player.allSlots.countItem<ItemPickaxe>()

                val tunnelingLeft = (pickaxesLeft * 1561) / (durabilityUsages.coerceAtLeast(1) / distanceDone.coerceAtLeast(1.0))

                val secLeft = tunnelingLeft.coerceAtLeast(0.0) / (startingBlockPos.distanceTo(currentBlockPos).toInt() / runtimeSec)
                val secondsLeft = (secLeft % 60).toInt().toString().padStart(2, '0')
                val minutesLeft = ((secLeft % 3600) / 60).toInt().toString().padStart(2, '0')
                val hoursLeft = (secLeft / 3600).toInt().toString().padStart(2, '0')

                displayText.addLine("Destination:", primaryColor)
                displayText.add("    Pickaxes:", primaryColor)
                displayText.addLine("$pickaxesLeft", secondaryColor)
                displayText.add("    Distance left:", primaryColor)
                displayText.addLine("${tunnelingLeft.toInt()}", secondaryColor)
                if (!anonymizeStats) displayText.add("    Destination:", primaryColor)
                if (!anonymizeStats) displayText.addLine("(${currentBlockPos.add(startingDirection.directionVec.multiply(tunnelingLeft.toInt())).asString()})", secondaryColor)
                displayText.add("    ETA:", primaryColor)
                displayText.addLine("$hoursLeft:$minutesLeft:$secondsLeft", secondaryColor)
            }
        }
    }

    private fun gatherSkynet(displayText: TextComponent) {
        if (botSet.isEmpty()) {
            displayText.addLine("Skynet inactive.", primaryColor)
        } else {
            displayText.addLine("Skynet", primaryColor)
            displayText.add("    Rank:", primaryColor)
            displayText.addLine("$rank", secondaryColor)
            displayText.add("    Job:", primaryColor)
            displayText.addLine("$job", secondaryColor)
            displayText.add("    Lane:", primaryColor)
            displayText.addLine("$lane", secondaryColor)
            displayText.addLine("    Bots:", primaryColor)
            for (bot in botSet) {
                displayText.addLine("        Name: ${bot.name} Rank: ${bot.rank} Job: ${bot.job} Lane: ${bot.lane}", secondaryColor)
            }
        }
    }

    private fun resetStats() {
        simpleMovingAveragePlaces.clear()
        simpleMovingAverageBreaks.clear()
        simpleMovingAverageDistance.clear()
        totalBlocksPlaced = 0
        totalBlocksBroken = 0
        totalDistance = 0.0
        runtimeMilliSeconds = 0
        prevFood = 0
        foodLoss = 1
        materialLeft = 0
        fillerMatLeft = 0
        lastToolDamage = 0
        durabilityUsages = 0
    }

    private fun addTaskComponentList(displayText: TextComponent, tasks: Collection<BlockTask>) {
        for (blockTask in tasks) displayText.addLine("    ${blockTask.block.localizedName}@(${blockTask.blockPos.asString()}) State: ${blockTask.taskState} Timings: (Threshold: ${blockTask.taskState.stuckThreshold} Timeout: ${blockTask.taskState.stuckTimeout}) Priority: ${blockTask.taskState.ordinal} Stuck: ${blockTask.stuckTicks}")
    }

    class Bot(
        val player: EntityPlayer,
        val name: String,
        var rank: Rank,
        var job: Job,
        var lane: Int
    )

    class BlockTask(
        val blockPos: BlockPos,
        var taskState: TaskState,
        var block: Block
    ) {
        private var ranTicks = 0
        var stuckTicks = 0; private set
        var shuffle = 0

        fun updateState(state: TaskState) {
            if (state == taskState) return
            taskState = state
            if (state == TaskState.DONE || state == TaskState.PLACED || state == TaskState.BROKEN) {
                onUpdate()
            }
        }

        fun updateMaterial(material: Block) {
            if (material == block) return
            block = material
            onUpdate()
        }

        fun onTick() {
            ranTicks++
            if (ranTicks > taskState.stuckThreshold) {
                stuckTicks++
            }
        }

        fun onStuck() {
            stuckTicks++
        }

        fun stuck(weight: Int) {
            stuckTicks += weight
        }

        fun shuffle() {
            shuffle = nextInt(0, 1000)
        }

        private fun onUpdate() {
            stuckTicks = 0
            ranTicks = 0
        }

        override fun toString(): String {
            return "Block: ${block.localizedName} @ Position: (${blockPos.asString()}) State: ${taskState.name}"
        }

        override fun equals(other: Any?) = this === other
            || (other is BlockTask
            && blockPos == other.blockPos)

        override fun hashCode() = blockPos.hashCode()
    }

    enum class TaskState(val stuckThreshold: Int, val stuckTimeout: Int, val color: ColorHolder) {
        DONE(69420, 0x22, ColorHolder(50, 50, 50)),
        BROKEN(1000, 1000, ColorHolder(111, 0, 0)),
        PLACED(1000, 1000, ColorHolder(53, 222, 66)),
        LIQUID_SOURCE(100, 100, ColorHolder(114, 27, 255)),
        LIQUID_FLOW(100, 100, ColorHolder(68, 27, 255)),
        BREAKING(100, 100, ColorHolder(240, 222, 60)),
        BREAK(20, 20, ColorHolder(222, 0, 0)),
        PLACE(20, 20, ColorHolder(35, 188, 254)),
        PENDING_BREAK(100, 100, ColorHolder(0, 0, 0)),
        PENDING_PLACE(100, 100, ColorHolder(0, 0, 0))
    }

}

