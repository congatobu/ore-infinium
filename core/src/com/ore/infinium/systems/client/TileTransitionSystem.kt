/**
MIT License

Copyright (c) 2016 Shaun Reich <sreich02@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package com.ore.infinium.systems.client

import com.artemis.Aspect
import com.artemis.annotations.Wire
import com.artemis.managers.TagManager
import com.artemis.systems.IntervalSystem
import com.badlogic.gdx.graphics.OrthographicCamera
import com.ore.infinium.LoadedViewport
import com.ore.infinium.OreBlock
import com.ore.infinium.OreWorld
import com.ore.infinium.components.PlayerComponent
import com.ore.infinium.util.mapper
import com.ore.infinium.util.system
import java.util.*

@Wire
class TileTransitionSystem(private val camera: OrthographicCamera, private val oreWorld: OreWorld)//every n ms
: IntervalSystem(Aspect.all(), 60.0f / 1000.0f) {

    private val mPlayer by mapper<PlayerComponent>()

    private val tagManager by system<TagManager>()
    private val clientNetworkSystem by system<ClientNetworkSystem>()

    /**
     * each flag here is handled (possibly, somewhat) differently depending on what kinda
     * block it is. The various types have their own logic, these are just sometimes-shared
     * identifiers.
     *
     *
     * Grass mostly uses the leftEmpty, etc. meaning that it will show grass on the left side of this block
     * Grass additionally uses the left, right, "should merge/transition" rules. That is, grass merges/blends with
     * dirt,
     * so if "left" is set, it means it will be a continuous stretch of dirt on the left side.
     *
     *
     * If eg "bottom" is NOT set, it means that it will look all jagged on that side. If it is set, for grass, it means
     * to blend
     * the dirt on that side.
     */
    private enum class Transitions {
        left,
        right,
        top,
        bottom,
        topLeftEmpty,
        topRightEmpty,
        bottomLeftEmpty,
        bottomRightEmpty,

        // show grass on the left side of this current block
        leftEmpty,
        rightEmpty,
        topEmpty,
        bottomEmpty,
        topLeftGrass,
        topRightGrass,

        //
        leftOre,
        rightOre,
        topOre,
        bottomOre,

        //
        leftDirt,
        rightDirt,
        topDirt,
        bottomDirt
    }

    override fun processSystem() {
        if (!clientNetworkSystem.connected) {
            return
        }

        val player = tagManager.getEntity(OreWorld.s_mainPlayer).id
        val cPlayer = mPlayer.get(player)
        val blockRegion = cPlayer.loadedViewport.blockRegionInViewport()

        transitionTiles(blockRegion)
        transitionGrass(blockRegion)
    }

    private fun transitionGrass(blockRegion: LoadedViewport.PlayerViewportBlockRegion) {
        for (y in blockRegion.y..blockRegion.height) {
            for (x in blockRegion.x..blockRegion.width) {

                val leftLeftBlockType = oreWorld.blockTypeSafely(x - 2, y)
                val rightRightBlockType = oreWorld.blockTypeSafely(x + 2, y)
                val leftBlockType = oreWorld.blockTypeSafely(x - 1, y)
                val rightBlockType = oreWorld.blockTypeSafely(x + 1, y)
                val topBlockType = oreWorld.blockTypeSafely(x, y - 1)
                val bottomBlockType = oreWorld.blockTypeSafely(x, y + 1)

                val topLeftBlockType = oreWorld.blockTypeSafely(x - 1, y - 1)
                val topRightBlockType = oreWorld.blockTypeSafely(x + 1, y - 1)
                val bottomLeftBlockType = oreWorld.blockTypeSafely(x - 1, y + 1)
                val bottomRightBlockType = oreWorld.blockTypeSafely(x + 1, y + 1)

                val blockType = oreWorld.blockTypeSafely(x, y)
                val blockHasGrass = oreWorld.blockHasFlag(x, y, OreBlock.BlockFlags.GrassBlock)
                if (blockType == OreBlock.BlockType.Dirt.oreValue && blockHasGrass) {

                    //should have grass on left side of this block..or not.
                    val leftEmpty = leftBlockType == OreBlock.BlockType.Air.oreValue
                    val leftLeftEmpty = leftLeftBlockType == OreBlock.BlockType.Air.oreValue

                    val rightEmpty = rightBlockType == OreBlock.BlockType.Air.oreValue
                    val rightRightEmpty = rightRightBlockType == OreBlock.BlockType.Air.oreValue

                    val topEmpty = topBlockType == OreBlock.BlockType.Air.oreValue

                    val bottomEmpty = bottomBlockType == OreBlock.BlockType.Air.oreValue

                    //if block to the left is dirt..
                    val leftDirt = leftBlockType == OreBlock.BlockType.Dirt.oreValue
                    val rightDirt = rightBlockType == OreBlock.BlockType.Dirt.oreValue
                    val topDirt = topBlockType == OreBlock.BlockType.Dirt.oreValue
                    val bottomDirt = bottomBlockType == OreBlock.BlockType.Dirt.oreValue

                    //handled a bit differently,
                    val topLeftEmpty = topLeftBlockType == OreBlock.BlockType.Air.oreValue
                    val topRightEmpty = topRightBlockType == OreBlock.BlockType.Air.oreValue
                    val bottomLeftEmpty = bottomLeftBlockType == OreBlock.BlockType.Air.oreValue
                    val bottomRightEmpty = bottomRightBlockType == OreBlock.BlockType.Air.oreValue

                    val leftOre = OreBlock.blockAttributes[leftBlockType]!!.category == OreBlock.BlockAttributes.BlockCategory.Ore

                    var finalMesh: Byte = -1

                    if (leftDirt && rightDirt && topDirt && bottomDirt && topLeftEmpty && topRightEmpty &&
                            bottomLeftEmpty && bottomRightEmpty) {
                        finalMesh = 0
                    } else if (leftEmpty && topEmpty && rightDirt && bottomDirt && !bottomRightEmpty) {
                        finalMesh = 1
                    } else if (leftDirt && topEmpty && rightDirt && bottomDirt &&
                            !(bottomLeftEmpty && bottomRightEmpty)) {
                        //fixme this is supsiciously different
                        finalMesh = 2
                    } else if (leftDirt && bottomDirt && rightEmpty && topEmpty) {
                        // fixme check leftleftempty etc?
                        finalMesh = 3
                    } else if (topDirt && rightDirt && bottomDirt && leftEmpty) {
                        finalMesh = 4
                    } else if (leftDirt && topDirt && bottomDirt && rightEmpty) {
                        finalMesh = 5
                    } else if (topDirt && rightDirt && leftEmpty && bottomEmpty && !topRightEmpty) {
                        finalMesh = 6
                    } else if (topDirt && leftDirt && rightDirt && bottomEmpty) {
                        finalMesh = 7
                    } else if (leftDirt && topDirt && rightEmpty && bottomEmpty && !topLeftEmpty) {
                        finalMesh = 8
                    } else if (leftEmpty && topEmpty && rightEmpty && bottomDirt) {
                        finalMesh = 9
                    } else if (leftEmpty && rightEmpty && topDirt && bottomDirt) {
                        finalMesh = 10
                    } else if (leftEmpty && topEmpty && bottomEmpty && rightDirt) {
                        finalMesh = 11
                    } else if (leftDirt && rightDirt && topEmpty && bottomEmpty) {
                        finalMesh = 12
                    } else if (leftDirt && topEmpty && bottomEmpty && rightEmpty) {
                        finalMesh = 13
                    } else if (leftEmpty && rightEmpty && bottomEmpty && topDirt) {
                        finalMesh = 14
                    } else if (leftEmpty && rightEmpty && topEmpty && bottomEmpty) {
                        finalMesh = 15
                    } else if (leftDirt && topDirt && rightDirt && bottomDirt && topLeftEmpty) {
                        finalMesh = 16
                    } else if (leftDirt && topDirt && bottomDirt && rightDirt && topRightEmpty) {
                        finalMesh = 17
                    } else if (leftDirt && bottomDirt && topDirt && bottomLeftEmpty &&
                            !topLeftEmpty) {
                        //fixme ADD TOP BOTTOM ETC
                        finalMesh = 18
                    } else if (rightDirt && bottomDirt && topDirt && leftDirt && bottomRightEmpty) {
                        finalMesh = 19
                    } else if (leftDirt && rightDirt && topDirt && topLeftEmpty && topRightEmpty) {
                        finalMesh = 20
                    } else if (topDirt && bottomDirt && leftDirt && topLeftEmpty && bottomLeftEmpty) {
                        finalMesh = 21
                    } else if (topDirt && bottomDirt && rightDirt && topRightEmpty && bottomRightEmpty) {
                        finalMesh = 22
                    } else if (leftDirt && rightDirt && topDirt && bottomLeftEmpty && bottomRightEmpty) {
                        finalMesh = 23
                    } else if (topDirt && rightDirt && bottomDirt && topRightEmpty && bottomRightEmpty &&
                            leftEmpty) {
                        //fixme
                        finalMesh = 24
                    } else if (leftDirt && topDirt && bottomDirt && topLeftEmpty && bottomLeftEmpty && rightEmpty) {
                        finalMesh = 25
                    } else if (leftDirt && rightDirt && topDirt && topLeftEmpty && topRightEmpty && bottomEmpty) {
                        finalMesh = 26
                    } else if (leftDirt && rightDirt && bottomDirt && topEmpty && bottomLeftEmpty && bottomRightEmpty) {
                        finalMesh = 27
                    } else if (leftDirt && topDirt && topLeftEmpty && rightEmpty && bottomEmpty) {
                        finalMesh = 28
                    } else if (topDirt && rightDirt && topRightEmpty && leftEmpty && bottomEmpty) {
                        finalMesh = 29
                    } else if (leftDirt && bottomDirt && bottomRightEmpty && rightEmpty && topEmpty) {
                        finalMesh = 30
                    } else if (rightDirt && bottomDirt && bottomRightEmpty && leftEmpty && topEmpty) {
                        finalMesh = 31
                    } else {
                        //failure
                        finalMesh = 15
                    }

                    oreWorld.setBlockMeshType(x, y, finalMesh)

                    if (finalMesh.toInt() == -1) {
                        assert(false) { "invalid mesh type retrieval, for some reason" }
                    }
                }
            }
        }
    }

    /*
    private boolean shouldGrassMesh(int sourceTileX, int sourceTileY, int nearbyTileX, int nearbyTileY) {
        boolean isMatched = false;
        int srcIndex = MathUtils.clamp(sourceTileX * worldSize.height + sourceTileY, 0, worldSize.height * worldSize.width - 1);
        int nearbyIndex = MathUtils.clamp(nearbyTileX * worldSize.height + nearbyTileY, 0, worldSize.height * worldSize.width - 1);

        if (blocks[srcIndex].type == blocks[nearbyIndex].type) {
            //todo in the future look up if it blends or not based on various thingies. not jsut "is tile same"
            //some may be exceptions??
            isMatched = true;
        }

        return isMatched;
    }
    */

    private fun transitionTiles(blockRegion: LoadedViewport.PlayerViewportBlockRegion) {
        for (y in blockRegion.y..blockRegion.height) {
            for (x in blockRegion.x..blockRegion.width) {

                val type = oreWorld.blockType(x, y)
                if (type == OreBlock.BlockType.Air.oreValue) {
                    continue
                }

                if (type == OreBlock.BlockType.Dirt.oreValue) {
                    //fixme may be able to be made generic. MAYBE.
                    transitionDirtTile(x, y)
                } else if (type == OreBlock.BlockType.Stone.oreValue) {
                    transitionStoneTile(x, y)
                }
            }
        }
    }

    private fun transitionStoneTile(x: Int, y: Int) {
        //essentially, if the *other* tiles in question are the same blocks, we should
        //merge/transition with them.

        val result = EnumSet.noneOf(Transitions::class.java)

        val leftMerge = shouldTileTransitionWith(x, y, x - 1, y)
        val rightMerge = shouldTileTransitionWith(x, y, x + 1, y)
        val topMerge = shouldTileTransitionWith(x, y, x, y - 1)
        val bottomMerge = shouldTileTransitionWith(x, y, x, y + 1)

        if (leftMerge) {
            result.add(Transitions.left)
        }

        if (rightMerge) {
            result.add(Transitions.right)
        }

        if (topMerge) {
            result.add(Transitions.top)
        }

        if (bottomMerge) {
            result.add(Transitions.bottom)
        }

        val lookup = stoneTransitionTypes[result]
        assert(lookup != null) { "transition lookup failure!" }
        oreWorld.setBlockMeshType(x, y, lookup!!)
    }

    private fun transitionDirtTile(x: Int, y: Int) {
        //essentially, if the *other* tiles in question are the same blocks, we should
        //merge/transition with them.

        val result = EnumSet.noneOf(Transitions::class.java)

        val leftMerge = shouldTileTransitionWith(x, y, x - 1, y)
        val rightMerge = shouldTileTransitionWith(x, y, x + 1, y)
        val topMerge = shouldTileTransitionWith(x, y, x, y - 1)
        val bottomMerge = shouldTileTransitionWith(x, y, x, y + 1)

        if (leftMerge) {
            result.add(Transitions.left)
        }

        if (rightMerge) {
            result.add(Transitions.right)
        }

        if (topMerge) {
            result.add(Transitions.top)
        }

        if (bottomMerge) {
            result.add(Transitions.bottom)
        }

        val lookup = dirtTransitionTypes[result]
        assert(lookup != null) { "transition lookup failure!" }
        oreWorld.setBlockMeshType(x, y, lookup!!)
    }

    /**
     * if given tile should transition with the neighbor tile. Usually indicated by if they are the same type or not.
     * (if they are, it's a yes. If they're different, no)

     * @param sourceTileX
     * *
     * @param sourceTileY
     * *
     * @param nearbyTileX
     * *
     * @param nearbyTileY
     * *
     * *
     * @return
     */
    private fun shouldTileTransitionWith(sourceTileX: Int,
                                         sourceTileY: Int,
                                         nearbyTileX: Int,
                                         nearbyTileY: Int): Boolean {
        var isMatched = false

        if (oreWorld.blockTypeSafely(sourceTileX, sourceTileY) == oreWorld.blockTypeSafely(nearbyTileX, nearbyTileY)) {
            //todo in the future look up if it blends or not based on various thingies. not jsut "is tile same"
            //some may be exceptions??
            isMatched = true
        }

        return isMatched
    }

    companion object {

        /**
         * @first bitmask of all sides, that maps to valid transition types e.g. left | right, indicates that it needs to
         * * mesh on the left and right sides ONLY
         * *
         * @second
         */
        private val dirtTransitionTypes = HashMap<EnumSet<Transitions>, Byte>()
        private val grassTransitions = HashMap<EnumSet<Transitions>, Byte>()
        private val stoneTransitionTypes = HashMap<EnumSet<Transitions>, Byte>()

        init {
            dirtTransitionTypes.put(
                    EnumSet.of(Transitions.left, Transitions.right, Transitions.top, Transitions.bottom),
                    0)
            dirtTransitionTypes.put(EnumSet.of(Transitions.bottom), 1)
            dirtTransitionTypes.put(EnumSet.of(Transitions.top, Transitions.bottom), 2)
            dirtTransitionTypes.put(EnumSet.of(Transitions.right), 3)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right), 4)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left), 5)
            dirtTransitionTypes.put(EnumSet.of(Transitions.top), 6)
            dirtTransitionTypes.put(EnumSet.of(Transitions.right, Transitions.bottom), 7)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right, Transitions.bottom), 8)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.bottom), 9)
            dirtTransitionTypes.put(EnumSet.of(Transitions.right, Transitions.top, Transitions.bottom), 10)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.top, Transitions.bottom), 11)
            dirtTransitionTypes.put(EnumSet.of(Transitions.right, Transitions.top), 12)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right, Transitions.top), 13)
            dirtTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.top), 14)
            dirtTransitionTypes.put(EnumSet.noneOf(Transitions::class.java), 15)

            ///////////////////////////////////////////////////////////////////////////////////

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.topLeftEmpty, Transitions.topRightEmpty, Transitions.bottomLeftEmpty,
                               Transitions.bottomRightEmpty), 0)

            grassTransitions.put(
                    EnumSet.of(Transitions.rightDirt, Transitions.bottomDirt, Transitions.leftEmpty,
                               Transitions.topEmpty),
                    1)

            grassTransitions.put(EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topEmpty), 2)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.bottomDirt, Transitions.topEmpty,
                               Transitions.rightEmpty),
                    3)

            grassTransitions.put(
                    EnumSet.of(Transitions.rightDirt, Transitions.bottomDirt, Transitions.topDirt,
                               Transitions.leftEmpty),
                    4)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.rightEmpty),
                    5)

            grassTransitions.put(
                    EnumSet.of(Transitions.topDirt, Transitions.rightDirt, Transitions.leftEmpty,
                               Transitions.bottomEmpty),
                    6)

            grassTransitions.put(
                    EnumSet.of(Transitions.topDirt, Transitions.leftDirt, Transitions.rightDirt,
                               Transitions.bottomEmpty),
                    7)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.topDirt, Transitions.rightEmpty,
                               Transitions.bottomEmpty),
                    8)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftEmpty, Transitions.rightEmpty, Transitions.topEmpty,
                               Transitions.bottomDirt),
                    9)
            grassTransitions.put(
                    EnumSet.of(Transitions.leftEmpty, Transitions.rightEmpty, Transitions.topEmpty,
                               Transitions.bottomDirt,
                               Transitions.topLeftEmpty, Transitions.topRightEmpty), 9)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftEmpty, Transitions.rightEmpty, Transitions.topDirt,
                               Transitions.bottomDirt),
                    10)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftEmpty, Transitions.topEmpty, Transitions.bottomEmpty,
                               Transitions.rightDirt),
                    11)
            grassTransitions.put(
                    EnumSet.of(Transitions.leftEmpty, Transitions.topEmpty, Transitions.bottomEmpty,
                               Transitions.rightDirt,
                               Transitions.topLeftEmpty, Transitions.topRightEmpty, Transitions.bottomLeftEmpty,
                               Transitions.bottomRightEmpty), 11)

            grassTransitions.put(
                    EnumSet.of(Transitions.topEmpty, Transitions.bottomEmpty, Transitions.leftDirt,
                               Transitions.rightDirt),
                    12)

            grassTransitions.put(
                    EnumSet.of(Transitions.topEmpty, Transitions.bottomEmpty, Transitions.rightEmpty,
                               Transitions.leftDirt),
                    13)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftEmpty, Transitions.rightEmpty, Transitions.bottomEmpty,
                               Transitions.topDirt),
                    14)

            grassTransitions.put(EnumSet.of(Transitions.topEmpty, Transitions.bottomEmpty, Transitions.leftEmpty,
                                            Transitions.rightEmpty), 15)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.topLeftEmpty), 16) //fixme 16, probably need one without bottom,etc

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.topRightEmpty), 17) //fixme

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.bottomLeftEmpty), 18)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.bottomRightEmpty), 19)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.topLeftEmpty, Transitions.topRightEmpty), 20)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.topLeftEmpty, Transitions.bottomLeftEmpty), 21)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.topRightEmpty, Transitions.bottomRightEmpty), 22)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.bottomLeftEmpty, Transitions.bottomRightEmpty), 23)

            grassTransitions.put(
                    EnumSet.of(Transitions.rightDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.leftEmpty),
                    24)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.topDirt, Transitions.bottomDirt,
                               Transitions.rightEmpty),
                    25)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.topDirt,
                               Transitions.bottomEmpty),
                    26)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.bottomDirt,
                               Transitions.topEmpty),
                    27)

            grassTransitions.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.topDirt, Transitions.topLeftEmpty,
                               Transitions.rightEmpty,
                               Transitions.bottomEmpty), 28)

            grassTransitions.put(
                    EnumSet.of(Transitions.rightDirt, Transitions.topDirt, Transitions.topRightEmpty,
                               Transitions.leftEmpty,
                               Transitions.bottomEmpty), 29)

            grassTransitions.put(EnumSet.of(Transitions.rightDirt, Transitions.bottomDirt, Transitions.bottomRightEmpty,
                                            Transitions.leftEmpty), 31)
            grassTransitions.put(EnumSet.of(Transitions.rightDirt, Transitions.bottomDirt, Transitions.bottomRightEmpty,
                                            Transitions.leftEmpty, Transitions.bottomLeftEmpty,
                                            Transitions.topLeftEmpty),
                                 31)
            grassTransitions.put(
                    EnumSet.of(Transitions.rightDirt, Transitions.bottomDirt, Transitions.leftEmpty,
                               Transitions.topEmpty,
                               Transitions.topLeftEmpty, Transitions.topRightEmpty, Transitions.bottomLeftEmpty,
                               Transitions.bottomRightEmpty), 31)

            //fixme?

            grassTransitions.put(EnumSet.of(Transitions.leftEmpty, Transitions.topEmpty, Transitions.rightDirt), 1)
            ////////////////////

            stoneTransitionTypes.put(
                    EnumSet.of(Transitions.left, Transitions.right, Transitions.top, Transitions.bottom),
                    0)
            stoneTransitionTypes.put(EnumSet.of(Transitions.right, Transitions.bottom), 1)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right), 2)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.bottom), 3)
            stoneTransitionTypes.put(EnumSet.of(Transitions.right, Transitions.bottom, Transitions.top), 4)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.top, Transitions.bottom), 5)
            stoneTransitionTypes.put(EnumSet.of(Transitions.top, Transitions.right), 6)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right, Transitions.top), 7)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right), 8)
            stoneTransitionTypes.put(EnumSet.of(Transitions.bottom), 9)
            stoneTransitionTypes.put(EnumSet.of(Transitions.bottom, Transitions.top), 10)
            stoneTransitionTypes.put(EnumSet.of(Transitions.right), 11)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right), 12)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left), 13)
            stoneTransitionTypes.put(EnumSet.of(Transitions.top), 14)
            stoneTransitionTypes.put(
                    EnumSet.of(Transitions.leftDirt, Transitions.rightDirt, Transitions.bottomDirt,
                               Transitions.topDirt),
                    15)
            stoneTransitionTypes.put(EnumSet.of(Transitions.bottom, Transitions.right), 16)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.bottom, Transitions.right), 17)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.bottom), 18)
            stoneTransitionTypes.put(EnumSet.of(Transitions.top, Transitions.bottom, Transitions.right), 19)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.top, Transitions.bottom), 20)
            stoneTransitionTypes.put(EnumSet.of(Transitions.top, Transitions.right), 21)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right), 22)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.top), 23)
            stoneTransitionTypes.put(EnumSet.of(Transitions.bottom), 24)
            stoneTransitionTypes.put(EnumSet.of(Transitions.bottom, Transitions.top), 25)
            stoneTransitionTypes.put(EnumSet.of(Transitions.right), 26)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left, Transitions.right), 27)
            stoneTransitionTypes.put(EnumSet.of(Transitions.left), 28)
            stoneTransitionTypes.put(EnumSet.of(Transitions.top), 29)
            stoneTransitionTypes.put(EnumSet.noneOf(Transitions::class.java), 30)
        }
    }

}
