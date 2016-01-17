package com.ore.infinium.systems

import com.artemis.Aspect
import com.artemis.ComponentMapper
import com.artemis.annotations.Wire
import com.artemis.managers.TagManager
import com.artemis.systems.IteratingSystem
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.ore.infinium.OreWorld
import com.ore.infinium.components.*

/**
 * ***************************************************************************
 * Copyright (C) 2015 by Shaun Reich @gmail.com>                    *
 * *
 * This program is free software; you can redistribute it and/or            *
 * modify it under the terms of the GNU General Public License as           *
 * published by the Free Software Foundation; either version 2 of           *
 * the License, or (at your option) any later version.                      *
 * *
 * This program is distributed in the hope that it will be useful,          *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of           *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            *
 * GNU General Public License for more details.                             *
 * *
 * You should have received a copy of the GNU General Public License        *
 * along with this program.  If not, see //www.gnu.org/licenses/>.    *
 * ***************************************************************************
 */
@Wire
class PowerOverlayRenderSystem(//   public TextureAtlas m_atlas;

        private val m_world: OreWorld, private val m_stage: Stage, private val m_skin: Skin) : IteratingSystem(
        Aspect.all(PowerDeviceComponent::class.java)), RenderSystemMarker {
    var overlayVisible = false
    private lateinit var m_batch: SpriteBatch

    private lateinit var playerMapper: ComponentMapper<PlayerComponent>
    private lateinit var spriteMapper: ComponentMapper<SpriteComponent>
    private lateinit var itemMapper: ComponentMapper<ItemComponent>
    private lateinit var velocityMapper: ComponentMapper<VelocityComponent>
    private lateinit var powerDeviceMapper: ComponentMapper<PowerDeviceComponent>
    private lateinit var powerGeneratorMapper: ComponentMapper<PowerGeneratorComponent>

    private lateinit var m_entityOverlaySystem: EntityOverlaySystem
    private lateinit var m_Server_powerCircuitSystem: ServerPowerCircuitSystem
    private lateinit var m_tagManager: TagManager

    //    public Sprite outputNode = new Sprite();

    private var m_leftClicked: Boolean = false
    private var m_dragInProgress: Boolean = false
    private var m_dragSourceEntity: Int = 0

    //displays info for the current circuit
    private var m_powerCircuitTooltipEntity: Int = 0

    private lateinit var m_container: Table
    private lateinit var m_totalStatsTable: Table

    private var currentCircuitSupply = -1
    private var currentCircuitDemand = -1

    private lateinit var m_circuitSupplyLabel: Label
    private lateinit var m_circuitDemandLabel: Label

    override fun initialize() {
        m_batch = SpriteBatch()

        m_powerCircuitTooltipEntity = world.create()

        val tooltipSprite = spriteMapper.create(m_powerCircuitTooltipEntity)
        tooltipSprite.sprite.setSize(1f, 1f)
        tooltipSprite.textureName = "debug"
        tooltipSprite.sprite.setRegion(m_world.m_atlas.findRegion(tooltipSprite.textureName))
        tooltipSprite.noClip = true

        m_container = Table(m_skin)
        m_container.setFillParent(true)
        //      m_container.padLeft(10).padTop(10);
        //        m_container.set

        m_totalStatsTable = Table(m_skin)
        m_totalStatsTable.top().left().pad(0f, 6f, 0f, 0f)
        m_totalStatsTable.setBackground("default-pane")

        val headerLabel = Label("Power Circuit Stats", m_skin)
        m_totalStatsTable.add(headerLabel).left()

        m_totalStatsTable.row()

        val demandLabel = Label("Circuit Demand:", m_skin)
        m_totalStatsTable.add(demandLabel).left()

        m_circuitDemandLabel = Label("-1", m_skin)
        m_totalStatsTable.add<Label>(m_circuitDemandLabel)

        m_totalStatsTable.row()

        val supplyLabel = Label("Circuit Supply:", m_skin)
        m_totalStatsTable.add(supplyLabel).left()

        m_circuitSupplyLabel = Label("-1", m_skin)
        m_totalStatsTable.add<Label>(m_circuitSupplyLabel)

        m_container.add<Table>(m_totalStatsTable).expand().bottom().right().size(400f, 100f)

        //        m_container.defaults().space(4);
        m_container.isVisible = false

        m_stage.addActor(m_container)
    }

    override fun dispose() {
        m_batch.dispose()
    }

    //todo sufficient until we get a spatial hash or whatever

    private fun entityAtPosition(pos: Vector2): Int {

        var spriteComponent: SpriteComponent
        val entities = entityIds
        for (i in 0..entities.size() - 1) {
            val currentEntity = entities.get(i)
            val entityBoxed = world.getEntity(currentEntity)

            val entityTag = m_tagManager.getTag(entityBoxed)

            //could be placement overlay, but we don't want this. skip over.
            if (entityTag != null && entityTag == OreWorld.s_itemPlacementOverlay) {
                continue
            }

            spriteComponent = spriteMapper.get(currentEntity)

            val rectangle = Rectangle(spriteComponent.sprite.x - spriteComponent.sprite.width * 0.5f,
                                      spriteComponent.sprite.y - spriteComponent.sprite.height * 0.5f,
                                      spriteComponent.sprite.width, spriteComponent.sprite.height)

            if (rectangle.contains(pos)) {
                return currentEntity
            }
        }

        return OreWorld.ENTITY_INVALID
    }

    fun leftMouseClicked() {
        m_leftClicked = true

        //fixme prolly make a threshold for dragging
        m_dragInProgress = true

        //find the entity we're dragging on
        m_dragSourceEntity = entityAtPosition(m_world.mousePositionWorldCoords())
    }

    fun rightMouseClicked() {
        //check if we can delete a wire
        m_Server_powerCircuitSystem.disconnectWireAtPosition(m_world.mousePositionWorldCoords())
    }

    fun leftMouseReleased() {
        m_leftClicked = false

        if (m_dragInProgress) {
            //check if drag can be connected

            if (m_dragSourceEntity != OreWorld.ENTITY_INVALID) {
                val mouse = m_world.mousePositionWorldCoords()

                val dropEntity = entityAtPosition(Vector2(mouse.x, mouse.y))
                //if the drop is invalid/empty, or they attempted to drop on the same spot they dragged from, ignore
                if (dropEntity == OreWorld.ENTITY_INVALID || dropEntity == m_dragSourceEntity) {
                    m_dragSourceEntity = OreWorld.ENTITY_INVALID
                    m_dragInProgress = false
                    return
                }

                val sourcePowerDeviceComponent = powerDeviceMapper.get(m_dragSourceEntity)
                val dropPowerDeviceComponent = powerDeviceMapper.get(dropEntity)

                //              if (!sourcePowerDeviceComponent.outputEntities.contains(dropEntity, true) &&
                //                     !dropPowerDeviceComponent.outputEntities.contains(m_dragSourceEntity, true)) {

                //                    sourcePowerDeviceComponent.outputEntities.add(dropEntity);

                m_Server_powerCircuitSystem.connectDevices(m_dragSourceEntity, dropEntity)

                //               }

                m_dragSourceEntity = OreWorld.ENTITY_INVALID
            }

            m_dragInProgress = false
        }
    }

    /**
     * Process the system.
     */
    override fun process(entityId: Int) {
        if (!overlayVisible) {
            return
        }

        //        m_batch.setProjectionMatrix(m_world.m_camera.combined);
        m_batch.projectionMatrix = m_world.m_camera.combined
        m_batch.begin()

        renderEntities(this.getWorld().delta)

        m_batch.end()

        //screen space rendering
        m_batch.projectionMatrix = m_world.m_client!!.viewport.camera.combined
        m_batch.begin()

        //fixme replace this crap w/ scene2d stuff?
        m_world.m_client!!.bitmapFont_8pt.setColor(1f, 0f, 0f, 1f)

        var fontY = 150f
        val fontX = (m_world.m_client!!.viewport.rightGutterX - 220).toFloat()

        m_batch.draw(m_world.m_atlas.findRegion("backgroundRect"), fontX - 10, fontY + 10, fontX + 100,
                     fontY - 300)

        m_world.m_client!!.bitmapFont_8pt.draw(m_batch, "Energy overlay visible (press E)", fontX, fontY)
        fontY -= 15f

        m_world.m_client!!.bitmapFont_8pt.draw(m_batch, "Input: N/A Output: N/A", fontX, fontY)

        m_world.m_client!!.bitmapFont_8pt.setColor(1f, 1f, 1f, 1f)

        m_batch.end()

        updateCircuitStats()
    }

    private fun updateCircuitStats() {
        val mouse = m_world.mousePositionWorldCoords()

        val dropEntity = entityAtPosition(Vector2(mouse.x, mouse.y))
        if (dropEntity != OreWorld.ENTITY_INVALID) {
            val powerDeviceComponent = powerDeviceMapper.getSafe(dropEntity)
            if (powerDeviceComponent == null || powerDeviceComponent.owningCircuit == null) {
                return
            }

            currentCircuitDemand = powerDeviceComponent.owningCircuit!!.totalDemand
            currentCircuitSupply = powerDeviceComponent.owningCircuit!!.totalSupply

            m_circuitDemandLabel.setText(currentCircuitDemand.toString())
            m_circuitSupplyLabel.setText(currentCircuitSupply.toString())
        } else {
            //reset them both and update the labels
            if (currentCircuitDemand != -1) {
                currentCircuitDemand = -1
                currentCircuitSupply = -1

                m_circuitDemandLabel.setText(currentCircuitDemand.toString())
                m_circuitSupplyLabel.setText(currentCircuitSupply.toString())
            }
        }
    }

    private fun renderEntities(delta: Float) {
        //todo need to exclude blocks?

        val mouse = m_world.mousePositionWorldCoords()

        val tooltipSprite = spriteMapper.get(m_powerCircuitTooltipEntity)
        //        tooltipSprite.sprite.setPosition(mouse.x, mouse.y);

        if (m_dragInProgress && m_dragSourceEntity != OreWorld.ENTITY_INVALID) {
            val dragSpriteComponent = spriteMapper.get(m_dragSourceEntity)

            m_batch.setColor(1f, 1f, 0f, 0.5f)

            //in the middle of a drag, draw powernode from source, to mouse position
            renderWire(Vector2(mouse.x, mouse.y),
                       Vector2(dragSpriteComponent.sprite.x + dragSpriteComponent.sprite.width * powerNodeOffsetRatioX,
                               dragSpriteComponent.sprite.y + dragSpriteComponent.sprite.height * powerNodeOffsetRatioY))
            m_batch.setColor(1f, 1f, 1f, 1f)
        }

        var firstEntitySpriteComponent: SpriteComponent
        var secondEntitySpriteComponent: SpriteComponent

        var deviceSprite: SpriteComponent
        val serverPowerCircuitSystem = m_Server_powerCircuitSystem
        for (circuit in serverPowerCircuitSystem.m_circuits) {
            //for each device, draw a power node, a "hub" of wireConnections of sorts.
            for (i in 0..circuit.generators.size - 1) {
                val gen = circuit.generators.get(i)
                deviceSprite = spriteMapper.get(gen)
                renderPowerNode(deviceSprite)
            }

            //do the same for devices. devices(consumers)
            for (i in 0..circuit.consumers.size - 1) {
                val device = circuit.consumers.get(i)
                deviceSprite = spriteMapper.get(device)
                renderPowerNode(deviceSprite)
            }

            //draw wires of each connection, in every circuit. Wires only have a start and end point.
            for (powerWireConnection in circuit.wireConnections) {

                firstEntitySpriteComponent = spriteMapper.get(powerWireConnection.firstEntity)
                secondEntitySpriteComponent = spriteMapper.get(powerWireConnection.secondEntity)

                //go over each output of this entity, and draw a connection from this entity to the connected dest
                renderWire(
                        Vector2(firstEntitySpriteComponent.sprite.x + firstEntitySpriteComponent.sprite.width * powerNodeOffsetRatioX,
                                firstEntitySpriteComponent.sprite.y + firstEntitySpriteComponent.sprite.height * powerNodeOffsetRatioY),
                        Vector2(secondEntitySpriteComponent.sprite.x + secondEntitySpriteComponent.sprite.width * powerNodeOffsetRatioX,
                                secondEntitySpriteComponent.sprite.y + secondEntitySpriteComponent.sprite.height * powerNodeOffsetRatioY))
            }
        }

    }

    private fun renderWire(source: Vector2, dest: Vector2) {
        val diff = Vector2(source.x - dest.x, source.y - dest.y)

        val rads = MathUtils.atan2(diff.y, diff.x)
        val degrees = rads * MathUtils.radiansToDegrees - 90

        val wireLength = Vector2.dst(source.x, source.y, dest.x, dest.y)

        m_batch.draw(m_world.m_atlas.findRegion("power-node-line"), dest.x, dest.y, 0f, 0f,
                     ServerPowerCircuitSystem.WIRE_THICKNESS, wireLength, 1.0f, 1.0f, degrees)
    }

    private fun renderPowerNode(spriteComponent: SpriteComponent) {
        val powerNodeWidth = 1f
        val powerNodeHeight = 1f

        m_batch.draw(m_world.m_atlas.findRegion("power-node-circle"),
                     spriteComponent.sprite.x + spriteComponent.sprite.width * powerNodeOffsetRatioX - powerNodeWidth * 0.5f,
                     spriteComponent.sprite.y + spriteComponent.sprite.height * powerNodeOffsetRatioY - powerNodeHeight * 0.5f,
                     powerNodeWidth, powerNodeHeight)
    }

    /**
     * handle toggling the state of if the wire editing overlay is shown.
     * including hiding other things that should not be shown while this is.
     * as well as turning on/off some state that should not be on.
     */
    fun toggleOverlay() {
        overlayVisible = !overlayVisible

        //also, turn off/on the overlays, like crosshairs, itemplacement overlays and stuff.
        //when wire overlay is visible, the entity overlays should be off.
        m_entityOverlaySystem.isEnabled = !overlayVisible
        m_entityOverlaySystem.setOverlaysVisible(!overlayVisible)

        if (overlayVisible) {
            m_container.toFront()
        }

        m_container.isVisible = overlayVisible
    }

    companion object {

        private val powerNodeOffsetRatioX = 0.1f
        private val powerNodeOffsetRatioY = 0.1f
    }

}