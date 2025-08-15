package net.dungeondev.accurateblockplacement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.StreamSerializer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import io.netty.buffer.Unpooled;
import org.bukkit.Axis;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AccurateBlockPlacement extends JavaPlugin implements Listener
{
    private ProtocolManager protocolManager;

    private Map<Player, PacketData> playerPacketDataHashMap = new HashMap<>();
    @Override public void onEnable() {
	getLogger().info("AccurateBlockPlacement loaded!");
	protocolManager = ProtocolLibrary.getProtocolManager();
	protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ITEM) {
	    @Override public void onPacketReceiving(final PacketEvent event) {
		onBlockBuildPacket(event);
	    }
	});
	protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.CUSTOM_PAYLOAD) {
	    @Override public void onPacketReceiving(final PacketEvent event) {
		onCustomPayload(event);
	    }
	});
	getServer().getPluginManager().registerEvents(this, this);
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
	try {
	    PacketContainer packet = new PacketContainer(PacketType.Play.Server.CUSTOM_PAYLOAD);
	    
	    // Constructing entire payload
	    ByteArrayOutputStream fullPayload = new ByteArrayOutputStream();
	    DataOutputStream dos = new DataOutputStream(fullPayload);
	    
	    StreamSerializer.getDefault().serializeString(dos, "carpet:hello");
	    
	    StreamSerializer.getDefault().serializeVarInt(dos, 69);
	    StreamSerializer.getDefault().serializeString(dos, "SPIGOT-ABP");
	    
	    dos.flush();
	    
	    packet.getModifier().write(0, MinecraftReflection.getPacketDataSerializer(
		Unpooled.wrappedBuffer(fullPayload.toByteArray())
	    ));
	    
	    protocolManager.sendServerPacket(event.getPlayer(), packet);
	} catch (Exception ignored) {}
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
	playerPacketDataHashMap.remove(event.getPlayer());
    }

    @EventHandler (priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBuildEvent(BlockPlaceEvent event) {
	Player player = event.getPlayer();
	PacketData packetData = playerPacketDataHashMap.get(player);
	if (packetData == null) {
	    return;
	}
	BlockPosition packetBlock = packetData.block();
	Block block = event.getBlock();
	if (packetBlock.getX() != block.getX() || packetBlock.getY() != block.getY() || packetBlock.getZ() != block.getZ()) {
	    playerPacketDataHashMap.remove(player);
	    return;
	}
	// Debug: Log ALL accurate placements
	getLogger().info("Accurate placement: " + block.getType() + " protocol=" + packetData.protocolValue() + 
	    " at " + block.getLocation() + " clicked: " + event.getBlockAgainst().getFace(block));
	accurateBlockProtocol(event, packetData.protocolValue());
	playerPacketDataHashMap.remove(player);
    }

    private void accurateBlockProtocol(BlockPlaceEvent event, int protocolValue)
    {
	Player player = event.getPlayer();
	Block block = event.getBlock();
	Block clickedBlock = event.getBlockAgainst();
	BlockData blockData = block.getBlockData();
	BlockData clickBlockData = clickedBlock.getBlockData();
	if (blockData instanceof Bed) {
	    return;
	}
	if (blockData instanceof Directional) {
	    int facingIndex = protocolValue & 0xF;
	    Directional directional = (Directional) blockData;
	    if (facingIndex == 6) {
		directional.setFacing(directional.getFacing().getOppositeFace());
	    }
	    else if (facingIndex <= 5) {
		BlockFace face = null;
		Set<BlockFace> validFaces = directional.getFaces();
	    	switch (facingIndex) {
		    case 0:
			face = BlockFace.DOWN;
			break;
		    case 1:
			face = BlockFace.UP;
			break;
		    case 2:
			face = BlockFace.NORTH;
			break;
		    case 3:
			face = BlockFace.SOUTH;
			break;
		    case 4:
			face = BlockFace.WEST;
			break;
		    case 5:
			face = BlockFace.EAST;
			break;
		}
		// Debug: Log what's happening with face validation
		if (face == BlockFace.UP || face == BlockFace.DOWN) {
		    getLogger().info("Trying vertical: face=" + face + " valid=" + validFaces.contains(face) + 
			" validFaces=" + validFaces + " material=" + blockData.getMaterial());
		}
		if (face != null && validFaces.contains(face)) {
		    directional.setFacing(face);
		    // Debug vertical placements
		    if (face == BlockFace.UP || face == BlockFace.DOWN) {
			getLogger().info("Set vertical facing: " + blockData.getMaterial() + " to " + face);
		    }
		}
	    }
	    if (blockData instanceof Chest) {
		//Merge chests if needed.
		Chest chest = (Chest) blockData;
		//Make sure we don't rotate a "half-double" chest!
		chest.setType(Chest.Type.SINGLE);
		BlockFace left = rotateCW(chest.getFacing());
		// Handle clicking on a chest in the world.
		if (!clickedBlock.equals(block) && clickBlockData.getMaterial() == chest.getMaterial()) {
		    Chest clickChest = (Chest) clickBlockData;
		    if (clickChest.getType() == Chest.Type.SINGLE && chest.getFacing() == clickChest.getFacing()) {
			BlockFace relation = block.getFace(clickedBlock);
			if (left == relation) {
			    chest.setType(Chest.Type.LEFT);
			} else if (left.getOppositeFace() == relation) {
			    chest.setType(Chest.Type.RIGHT);
			}
		    }
		// Handle placing a chest normally.
		} else if (!player.isSneaking()) {
		    BlockData leftBlock = block.getRelative(left).getBlockData();
		    BlockData rigthBlock = block.getRelative(left.getOppositeFace()).getBlockData();
		    if (leftBlock.getMaterial() == chest.getMaterial() &&
			((Chest) leftBlock).getType() == Chest.Type.SINGLE &&
			((Chest) leftBlock).getFacing() == chest.getFacing()) {
			chest.setType(Chest.Type.LEFT);
		    } else if (rigthBlock.getMaterial() == chest.getMaterial() &&
			       ((Chest) rigthBlock).getType() == Chest.Type.SINGLE &&
			       ((Chest) rigthBlock).getFacing() == chest.getFacing()) {
			chest.setType(Chest.Type.RIGHT);
		    }
		}
	    } else if (blockData instanceof Stairs) {
		((Stairs) blockData).setShape(handleStairs(block, (Stairs) blockData));
	    }
	}
	else if (blockData instanceof Orientable) {
	    Orientable orientable = (Orientable) blockData;
	    Set<Axis> validAxes = orientable.getAxes();
	    Axis axis = null;
	    switch (protocolValue % 3) {
		case 0:
		    axis = Axis.X;
		    break;
		case 1:
		    axis = Axis.Y;
		    break;
		case 2:
		    axis = Axis.Z;
		    break;
	    }
	    if (validAxes.contains(axis)) {
		orientable.setAxis(axis);
	    }
	}
	protocolValue &= 0xFFFFFFF0;
	if (protocolValue >= 16) {
	    if (blockData instanceof Repeater) {
		Repeater repeater = (Repeater) blockData;
		int deley = protocolValue / 16;
		if (deley >= repeater.getMinimumDelay() && deley <= repeater.getMaximumDelay()) {
			repeater.setDelay(deley);
		}
	    }
	    else if (protocolValue == 16) {
		if (blockData instanceof Comparator) {
		    ((Comparator) blockData).setMode(Comparator.Mode.SUBTRACT);
		} else if (blockData instanceof Bisected) {
		    Bisected bisected = (Bisected) blockData;
		    bisected.setHalf(Bisected.Half.TOP);
		}
	    }
	}
	// Keep the canPlace check for security
	boolean canPlace = block.canPlace(blockData);
	if (!canPlace && blockData instanceof Directional) {
	    Directional dir = (Directional) blockData;
	    if (dir.getFacing() == BlockFace.UP || dir.getFacing() == BlockFace.DOWN) {
		getLogger().warning("canPlace=false for vertical: " + blockData.getMaterial() + 
		    " facing " + dir.getFacing() + " at " + block.getLocation());
	    }
	}
	
	if (canPlace) {
	    // Schedule the block update for next tick to bypass Paper's validation
	    final BlockData finalBlockData = blockData;
	    getServer().getScheduler().runTask(this, () -> {
		// Double-check the block is still the same type before updating
		if (block.getType() == finalBlockData.getMaterial()) {
		    block.setBlockData(finalBlockData, false);
		}
	    });
	} else {
	    event.setCancelled(true);
	}
    }

    private BlockFace rotateCW(BlockFace in) {
	BlockFace out = null;
	switch (in) {
	    case NORTH -> out = BlockFace.EAST;
	    case WEST -> out = BlockFace.NORTH;
	    case SOUTH -> out = BlockFace.WEST;
	    case EAST -> out = BlockFace.SOUTH;
	}
	return out;
    }

    private Stairs.Shape handleStairs(Block block, Stairs stairs) {
	Bisected.Half half = stairs.getHalf();
	BlockFace backFace = stairs.getFacing();
	BlockFace frontFace = backFace.getOppositeFace();
	BlockFace rightFace = rotateCW(backFace);
	BlockFace leftFace = rightFace.getOppositeFace();
	Stairs backStairs = block.getRelative(backFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(backFace).getBlockData() : null;
	Stairs frontStairs = block.getRelative(frontFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(frontFace).getBlockData() : null;
	Stairs leftStairs = block.getRelative(leftFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(leftFace).getBlockData() : null;
	Stairs rightStairs = block.getRelative(rightFace).getBlockData() instanceof Stairs ? (Stairs) block.getRelative(rightFace).getBlockData() : null;


	if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == leftFace) &&
	    ! (rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace) ) {
	    return Stairs.Shape.OUTER_LEFT;
	} else if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == rightFace) &&
		    ! (leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace) ) {
	    return Stairs.Shape.OUTER_RIGHT;
	} else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == leftFace) &&
		    ! (leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace) ) {
	    return Stairs.Shape.INNER_LEFT;
	} else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == rightFace) &&
		    ! (rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace) ) {
	    return Stairs.Shape.INNER_RIGHT;
	} else {
	    return Stairs.Shape.STRAIGHT;
	}

    }

    private void onBlockBuildPacket(final PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();
	MovingObjectPositionBlock clickInformation = packet.getMovingBlockPositions().read(0);
	BlockPosition blockPosition = clickInformation.getBlockPosition();
	Vector posVector = clickInformation.getPosVector();

	double relativeX = posVector.getX() - blockPosition.getX();

	if (relativeX < 2)
	{
	    playerPacketDataHashMap.remove(player);
	    return;
	}
	int protocolValue = ((int) relativeX - 2) / 2;
	playerPacketDataHashMap.put(player, new PacketData(clickInformation.getBlockPosition(), protocolValue));
	int relativeInt = (int) relativeX;
	relativeX -= (relativeInt/2)*2; //Remove largest multiple of 2.
	posVector.setX(relativeX + blockPosition.getX());
	clickInformation.setPosVector(posVector);
	packet.getMovingBlockPositions().write(0, clickInformation);
    }

    private void onCustomPayload(final PacketEvent event) {
	try {
	    PacketContainer packet = event.getPacket();
	    // Try to get the payload data directly
	    Object payload = packet.getModifier().read(1);
	    if (payload == null) return;
	    sendCarpetRules(event.getPlayer());
	} catch (Exception ignored) {
	}
    }
    
    private void sendCarpetRules(Player player) {
	try {
	    PacketContainer rulePacket = new PacketContainer(PacketType.Play.Server.CUSTOM_PAYLOAD);
	    
	    ByteArrayOutputStream fullPayload = new ByteArrayOutputStream();
	    DataOutputStream dos = new DataOutputStream(fullPayload);
	    StreamSerializer.getDefault().serializeString(dos, "carpet:hello");
	    
	    StreamSerializer.getDefault().serializeVarInt(dos, 1);
	    
	    NbtCompound abpRule = NbtFactory.ofCompound("Rules", List.of(
		NbtFactory.of("Value", "true"),
		NbtFactory.of("Manager", "carpet"),
		NbtFactory.of("Rule", "accurateBlockPlacement")
	    ));
	    StreamSerializer.getDefault().serializeCompound(dos, abpRule);
	    
	    dos.flush();
	    
	    rulePacket.getModifier().write(0, MinecraftReflection.getPacketDataSerializer(
		Unpooled.wrappedBuffer(fullPayload.toByteArray())
	    ));
	    
	    protocolManager.sendServerPacket(player, rulePacket);
	} catch (Exception ignored) {}
    }
}