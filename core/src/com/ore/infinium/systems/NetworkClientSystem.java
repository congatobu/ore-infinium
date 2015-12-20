package com.ore.infinium.systems;

import com.artemis.*;
import com.artemis.annotations.Wire;
import com.artemis.managers.TagManager;
import com.artemis.utils.IntBag;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.FrameworkMessage;
import com.esotericsoftware.kryonet.Listener;
import com.ore.infinium.*;
import com.ore.infinium.components.*;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ***************************************************************************
 * Copyright (C) 2015 by Shaun Reich <sreich02@gmail.com>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * ***************************************************************************
 */

/**
 * Handles the network side of things, for the client
 */
@Wire
public class NetworkClientSystem extends BaseSystem {
    /**
     * whether or not we're connected to the server (either local or mp).
     * <p>
     * This will only be true when the player has spawned.
     * <p>
     * This means, the server has spawned our player and the initial
     * player data has been sent back, indicating to the client that it has
     * been spawned, and under what player id.
     */
    public boolean connected;

    private ComponentMapper<PlayerComponent> playerMapper;
    private ComponentMapper<SpriteComponent> spriteMapper;
    private ComponentMapper<ControllableComponent> controlMapper;
    private ComponentMapper<ItemComponent> itemMapper;
    private ComponentMapper<VelocityComponent> velocityMapper;
    private ComponentMapper<JumpComponent> jumpMapper;
    private ComponentMapper<BlockComponent> blockMapper;
    private ComponentMapper<ToolComponent> toolMapper;

    private TagManager m_tagManager;
    private TileRenderSystem m_tileRenderer;

    private ConcurrentLinkedQueue<Object> m_netQueue = new ConcurrentLinkedQueue<>();

    private OreWorld m_world;

    public Client m_clientKryo;

    /**
     * the network id is a special id that is used to refer to an entity across
     * the network, for this client. Basically it is so the client knows what
     * entity id the server is talking about..as the client and server ECS engines
     * will have totally different sets of entitiy id's.
     * <p>
     * So, server sends over what its internal entity id is for an entity to spawn,
     * as well as for referring to future ones, and we make a map of <server entity, client entity>,
     * since we normally receive a server entity id, and we must determine what *our* (clients) entity
     * id is, so we can do things like move it around, perform actions etc on it.
     */
    // the internal (client) entity for the network(server's) entity ID
    private IntMap<Integer> m_entityForNetworkId = new IntMap<>(500);
    // map to reverse lookup, the long (server) entity ID for the given Entity
    /**
     * <client entity, server entity>
     */
    private IntMap<Integer> m_networkIdForEntityId = new IntMap<>(500);

    private Array<NetworkClientListener> m_listeners = new Array<>(5);

    public NetworkClientSystem(OreWorld world) {
        m_world = world;
    }

    public void addListener(NetworkClientListener listener) {
        m_listeners.add(listener);
    }

    public interface NetworkClientListener {
        public void connected();

        //todo send a disconnection reason along with the disconnect event. to eg differentiate between a kick or a
        // connection loss, or a server shutdown
        public void disconnected();
    }

    /**
     * connect the client network object to the given ip, at the given port
     *
     * @param ip
     */
    public void connect(String ip, int port) throws IOException {
        //m_clientKryo = new Client(16384, 8192, new JsonSerialization());
        m_clientKryo = new Client(8192, Network.bufferObjectSize);
        m_clientKryo.start();

        Network.register(m_clientKryo);

        m_clientKryo.addListener(new ClientListener());
        m_clientKryo.setKeepAliveTCP(999999);

        new Thread("kryonet connection client thread") {
            public void run() {
                try {
                    Gdx.app.log("NetworkClientSystem", "client attempting to connect to server");
                    m_clientKryo.connect(99999999 /*fixme, debug*/, ip, port);
                    // Server communication after connection can go here, or in Listener#connected().

                    sendInitialClientData();
                } catch (IOException ex) {
                    //fixme this is horrible..but i can't figure out how to rethrow it back to the calling thread
                    //throw new IOException("tesssst");
                    //                    ex.printStackTrace();
                    System.exit(1);
                }
            }
        }.start();

    }

    private void sendInitialClientData() {
        Network.InitialClientData initialClientData = new Network.InitialClientData();

        initialClientData.playerName = OreSettings.getInstance().playerName;

        //TODO generate some random thing
        initialClientData.playerUUID = UUID.randomUUID().toString();
        initialClientData.versionMajor = OreClient.ORE_VERSION_MAJOR;
        initialClientData.versionMinor = OreClient.ORE_VERSION_MINOR;
        initialClientData.versionRevision = OreClient.ORE_VERSION_REVISION;

        m_clientKryo.sendTCP(initialClientData);
    }

    @Override
    protected void processSystem() {
        processNetworkQueue();
    }

    private void processNetworkQueue() {
        for (Object receivedObject = m_netQueue.poll(); receivedObject != null; receivedObject = m_netQueue.poll()) {
            if (receivedObject instanceof Network.PlayerSpawnedFromServer) {
                receivePlayerSpawn(receivedObject);
            } else if (receivedObject instanceof Network.DisconnectReason) {
                receiveDisconnectReason(receivedObject);
            } else if (receivedObject instanceof Network.BlockRegion) {
                receiveBlockRegion(receivedObject);
            } else if (receivedObject instanceof Network.SparseBlockUpdate) {
                receiveSparseBlockUpdate(receivedObject);
            } else if (receivedObject instanceof Network.LoadedViewportMovedFromServer) {
                receiveLoadedViewportMoved(receivedObject);
            } else if (receivedObject instanceof Network.PlayerSpawnHotbarInventoryItemFromServer) {
                receivePlayerSpawnHotbarInventoryItem(receivedObject);
            } else if (receivedObject instanceof Network.EntitySpawnFromServer) {
                receiveEntitySpawn(receivedObject);
            } else if (receivedObject instanceof Network.ChatMessageFromServer) {
                receiveChatMessage(receivedObject);
            } else if (receivedObject instanceof Network.EntityMovedFromServer) {
                receiveEntityMoved(receivedObject);
            } else {
                if (!(receivedObject instanceof FrameworkMessage.KeepAlive)) {
                    assert false : "unhandled network receiving class in network client";
                }
            }
        }
    }

    private void receivePlayerSpawnHotbarInventoryItem(Object receivedObject) {
        Network.PlayerSpawnHotbarInventoryItemFromServer spawn =
                (Network.PlayerSpawnHotbarInventoryItemFromServer) receivedObject;

        //fixme spawn.id, sprite!!
        int e = getWorld().create();
        for (Component c : spawn.components) {
            EntityEdit entityEdit = getWorld().edit(e);
            entityEdit.add(c);
        }

        SpriteComponent spriteComponent = spriteMapper.create(e);
        spriteComponent.textureName = spawn.textureName;
        spriteComponent.sprite.setSize(spawn.size.size.x, spawn.size.size.y);

        TextureRegion textureRegion;
        if (!blockMapper.has(e)) {
            textureRegion = m_world.m_atlas.findRegion(spriteComponent.textureName);
        } else {
            textureRegion = m_tileRenderer.m_blockAtlas.findRegion(spriteComponent.textureName);
        }

        ToolComponent toolComponent = toolMapper.get(e);

        ItemComponent itemComponent = itemMapper.get(e);
        //fixme this indirection isn't so hot...
        m_world.m_client.m_hotbarInventory.setSlot(itemComponent.inventoryIndex, e);

        //TODO i wonder if i can implement my own serializer (trivially!) and make it use the
        // entity/component pool. look into kryo itself, you can override creation (easily i hope), per class
    }

    private void receiveChatMessage(Object receivedObject) {
        Network.ChatMessageFromServer data = (Network.ChatMessageFromServer) receivedObject;
        m_world.m_client.m_chat.addChatLine(data.timestamp, data.playerName, data.message, data.sender);
    }

    private void receiveEntityMoved(Object receivedObject) {
        Network.EntityMovedFromServer data = (Network.EntityMovedFromServer) receivedObject;
        int entity = m_entityForNetworkId.get(data.id);
        assert entity != OreWorld.ENTITY_INVALID;

        SpriteComponent spriteComponent = spriteMapper.get(entity);
        spriteComponent.sprite.setPosition(data.position.x, data.position.y);
    }

    private void receiveEntitySpawn(Object receivedObject) {
        //fixme this and hotbar code needs consolidation
        Network.EntitySpawnFromServer spawn = (Network.EntitySpawnFromServer) receivedObject;

        int e = getWorld().create();
        for (Component c : spawn.components) {
            EntityEdit entityEdit = getWorld().edit(e);
            entityEdit.add(c);
        }

        //fixme id..see above.
        SpriteComponent spriteComponent = spriteMapper.create(e);
        spriteComponent.textureName = spawn.textureName;
        spriteComponent.sprite.setSize(spawn.size.size.x, spawn.size.size.y);
        spriteComponent.sprite.setPosition(spawn.pos.pos.x, spawn.pos.pos.y);

        ItemComponent itemComponent = itemMapper.getSafe(e);
        if (itemComponent != null) {
            if (itemComponent.state == ItemComponent.State.DroppedInWorld) {
                //initial drop, set scale to smaller. when it gets picked up we can enlarge it again
                //this is to visually modify the size and logically, without size being lossy.
                spriteComponent.sprite.setScale(0.5f, 0.5f);
            }
        }

        TextureRegion textureRegion;
        if (!blockMapper.has(e)) {
            textureRegion = m_world.m_atlas.findRegion(spriteComponent.textureName);
        } else {
            textureRegion = m_tileRenderer.m_blockAtlas.findRegion(spriteComponent.textureName);
        }

        spriteComponent.sprite.setRegion(textureRegion);

        m_networkIdForEntityId.put(e, spawn.id);
        m_entityForNetworkId.put(spawn.id, e);
    }

    private void receiveLoadedViewportMoved(Object receivedObject) {
        Network.LoadedViewportMovedFromServer v = (Network.LoadedViewportMovedFromServer) receivedObject;
        PlayerComponent c = playerMapper.get(m_tagManager.getEntity(OreWorld.s_mainPlayer));
        c.loadedViewport.rect = v.rect;
    }

    private void receiveSparseBlockUpdate(Object receivedObject) {
        Network.SparseBlockUpdate update = (Network.SparseBlockUpdate) receivedObject;
        m_world.loadSparseBlockUpdate(update);
    }

    private void receiveDisconnectReason(Object receivedObject) {
        Network.DisconnectReason reason = (Network.DisconnectReason) receivedObject;

        for (NetworkClientListener listener : m_listeners) {
            listener.disconnected();
        }
    }

    private void receivePlayerSpawn(Object receivedObject) {
        Network.PlayerSpawnedFromServer spawn = (Network.PlayerSpawnedFromServer) receivedObject;

        if (!connected) {
            //fixme not ideal, calling into the client to do this????
            int player = m_world.m_client.createPlayer(spawn.playerName, m_clientKryo.getID());
            SpriteComponent spriteComp = spriteMapper.get(player);

            spriteComp.sprite.setPosition(spawn.pos.pos.x, spawn.pos.pos.y);

            SpriteComponent playerSprite = spriteMapper.get(player);
            playerSprite.sprite.setRegion(m_world.m_atlas.findRegion("player-32x64"));

            AspectSubscriptionManager aspectSubscriptionManager = getWorld().getAspectSubscriptionManager();
            EntitySubscription subscription = aspectSubscriptionManager.get(Aspect.all());
            subscription.addSubscriptionListener(new ClientEntitySubscriptionListener());

            connected = true;

            for (NetworkClientListener listener : m_listeners) {
                listener.connected();
            }
        } else {
            //FIXME cover other players joining case
            throw new RuntimeException("fixme, other players joining not yet implemented");
        }
    }

    private void receiveBlockRegion(Object receivedObject) {
        Network.BlockRegion region = (Network.BlockRegion) receivedObject;
        m_world.loadBlockRegion(region);
    }

    public void sendInventoryMove(Inventory.InventoryType sourceInventoryType, byte sourceIndex,
                                  Inventory.InventoryType destInventoryType, byte destIndex) {
        Network.PlayerMoveInventoryItemFromClient inventoryItemFromClient =
                new Network.PlayerMoveInventoryItemFromClient();
        inventoryItemFromClient.sourceType = sourceInventoryType;
        inventoryItemFromClient.sourceIndex = sourceIndex;
        inventoryItemFromClient.destType = destInventoryType;
        inventoryItemFromClient.destIndex = destIndex;

        m_clientKryo.sendTCP(inventoryItemFromClient);
    }

    /**
     * Send the command indicating (main) player moved to position
     */
    public void sendPlayerMoved() {
        int mainPlayer = m_tagManager.getEntity("mainPlayer").getId();
        SpriteComponent sprite = spriteMapper.get(mainPlayer);

        Network.PlayerMoveFromClient move = new Network.PlayerMoveFromClient();
        move.position = new Vector2(sprite.sprite.getX(), sprite.sprite.getY());

        m_clientKryo.sendTCP(move);
    }

    public void sendChatMessage(String message) {
        Network.ChatMessageFromClient chatMessageFromClient = new Network.ChatMessageFromClient();
        chatMessageFromClient.message = message;

        m_clientKryo.sendTCP(chatMessageFromClient);
    }

    public void sendHotbarEquipped(byte index) {
        Network.PlayerEquipHotbarIndexFromClient playerEquipHotbarIndexFromClient =
                new Network.PlayerEquipHotbarIndexFromClient();
        playerEquipHotbarIndexFromClient.index = index;

        m_clientKryo.sendTCP(playerEquipHotbarIndexFromClient);
    }

    public void sendBlockPick(int x, int y) {
        Network.BlockPickFromClient blockPickFromClient = new Network.BlockPickFromClient();
        blockPickFromClient.x = x;
        blockPickFromClient.y = y;
        m_clientKryo.sendTCP(blockPickFromClient);
    }

    public void sendBlockPlace(int x, int y) {
        Network.BlockPlaceFromClient blockPlaceFromClient = new Network.BlockPlaceFromClient();
        blockPlaceFromClient.x = x;
        blockPlaceFromClient.y = y;
        m_clientKryo.sendTCP(blockPlaceFromClient);
    }

    public void sendItemPlace(float x, float y) {
        Network.ItemPlaceFromClient itemPlace = new Network.ItemPlaceFromClient();
        itemPlace.x = x;
        itemPlace.y = y;

        m_clientKryo.sendTCP(itemPlace);
    }

    class ClientListener extends Listener {
        //private OreClient m_client;

        ClientListener() {
            //m_client = client;

        }

        public void connected(Connection connection) {
            connection.setTimeout(999999999);
            Gdx.app.log("NetworkClientSystem", "our client connected!");
        }

        //FIXME: do sanity checking (null etc) on both client, server
        public void received(Connection connection, Object object) {
            m_netQueue.add(object);
        }

        public void disconnected(Connection connection) {
        }
    }

    private class ClientEntitySubscriptionListener implements EntitySubscription.SubscriptionListener {

        /**
         * Called after entities have been matched and inserted into an
         * EntitySubscription.
         *
         * @param entities
         */
        @Override
        public void inserted(IntBag entities) {

        }

        /**
         * Called after entities have been removed from an EntitySubscription.
         *
         * @param entities
         */
        @Override
        public void removed(IntBag entities) {
            for (int entity = 0; entity < entities.size(); ++entity) {
                Integer networkId = m_networkIdForEntityId.remove(entity);
                if (networkId != null) {
                    //a local only thing, like crosshair etc
                    m_entityForNetworkId.remove(networkId);
                }
            }
        }
    }
}
