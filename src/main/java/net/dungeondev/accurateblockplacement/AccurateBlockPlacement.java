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
import org.bukkit.Material;
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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AccurateBlockPlacement extends JavaPlugin implements Listener {
	private ProtocolManager protocolManager;
	private FileConfiguration config;

	private final Map<Player, PacketData> playerPacketDataHashMap = new ConcurrentHashMap<>();

	@Override
	public void onEnable() {

		saveDefaultConfig();
		config = getConfig();

		getLogger().info("PaperAccurateBlockPlacement loaded!");
		protocolManager = ProtocolLibrary.getProtocolManager();

		protocolManager.addPacketListener(
				new PacketAdapter(this, ListenerPriority.LOWEST, PacketType.Play.Client.USE_ITEM_ON) {
					@Override
					public void onPacketReceiving(final PacketEvent event) {
						onBlockBuildPacket(event);
					}
				});
		protocolManager.addPacketListener(
				new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.CUSTOM_PAYLOAD) {
					@Override
					public void onPacketReceiving(final PacketEvent event) {
						onCustomPayload(event);
					}
				});
		getServer().getPluginManager().registerEvents(this, this);
	}

	private boolean isAirPlaceableBlock(Material material) {

		if (config.getBoolean("air-placement.candles", true)) {
			if (material.name().endsWith("_CANDLE") || material == Material.CANDLE) {
				return true;
			}
		}
		return false;
		// todo: consider other additional blocks to add
	}

	@Override
	public void onDisable() {
		playerPacketDataHashMap.clear();

		if (protocolManager != null) {
			protocolManager.removePacketListeners(this);
		}
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
			@NotNull String[] args) {
		if (command.getName().equalsIgnoreCase("abp")) {
			if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
				if (!sender.hasPermission("accurateblockplacement.reload")) {
					sender.sendMessage("<red>You don't have permission to reload the config!</red>");
					return true;
				}

				reloadConfig();
				config = getConfig();
				sender.sendMessage("<green>PaperAccurateBlockPlacement config reloaded</green>");
				return true;
			}

			sender.sendMessage("PaperAccurateBlockPlacement v" + getPluginMeta().getVersion());
			return true;
		}
		return false;
	}

	private void debug(String message) {
		if (config.getBoolean("debug", false)) {
			getLogger().info("[DEBUG] " + message);
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		try {
			PacketContainer packet = new PacketContainer(PacketType.Play.Server.CUSTOM_PAYLOAD);

			// constructing entire payload
			ByteArrayOutputStream fullPayload = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(fullPayload);

			StreamSerializer.getDefault().serializeString(dos, "carpet:hello");

			StreamSerializer.getDefault().serializeVarInt(dos, 69);
			StreamSerializer.getDefault().serializeString(dos, "PAPER-ABP");

			dos.flush();

			packet.getModifier().write(0, MinecraftReflection.getPacketDataSerializer(
					Unpooled.wrappedBuffer(fullPayload.toByteArray())));

			protocolManager.sendServerPacket(event.getPlayer(), packet);
		} catch (Exception e) {
			debug("Failed to send carpet hello packet to " + event.getPlayer().getName() + ": " + e.getMessage());
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		playerPacketDataHashMap.remove(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBuildEvent(BlockPlaceEvent event) {
		Player player = event.getPlayer();
		PacketData packetData = playerPacketDataHashMap.get(player);

		if (packetData == null) {
			return;
		}

		BlockPosition packetBlock = packetData.block();
		Block block = event.getBlock();
		Block clickedBlock = event.getBlockAgainst();

		boolean positionMatches = (packetBlock.getX() == block.getX() && packetBlock.getY() == block.getY()
				&& packetBlock.getZ() == block.getZ()) ||
				(packetBlock.getX() == clickedBlock.getX() && packetBlock.getY() == clickedBlock.getY()
						&& packetBlock.getZ() == clickedBlock.getZ());

		if (!positionMatches) {
			debug("Position mismatch: packet=" + packetBlock + " placed=" + block.getLocation() + " clicked="
					+ clickedBlock.getLocation());
			playerPacketDataHashMap.remove(player);
			return;
		}

		if (isAirPlaceableBlock(block.getType())) {
			handleAirPlacement(event, packetData.protocolValue());
			playerPacketDataHashMap.remove(player);
			return;
		}

		debug("Accurate placement: " + block.getType() + " protocol=" + packetData.protocolValue() + " at "
				+ block.getLocation() + " clicked: " + event.getBlockAgainst().getFace(block));

		accurateBlockProtocol(event, packetData.protocolValue());
		playerPacketDataHashMap.remove(player);
	}

	private void accurateBlockProtocol(BlockPlaceEvent event, int protocolValue) {
		Player player = event.getPlayer();
		Block block = event.getBlock();
		Block clickedBlock = event.getBlockAgainst();
		BlockData blockData = block.getBlockData();
		BlockData clickBlockData = clickedBlock.getBlockData();

		debug("accurateBlockProtocol: material=" + blockData.getMaterial() + " protocol=" + protocolValue + " binary="
				+ Integer.toBinaryString(protocolValue));

		if (blockData instanceof Bed) {
			return;
		}

		if (blockData instanceof Directional) {
			int facingIndex = protocolValue & 0xF;
			Directional directional = (Directional) blockData;

			BlockFace currentFacing = directional.getFacing();
			debug("Directional: facingIndex=" + facingIndex + " currentFacing=" + currentFacing);

			// handle directional block reversal
			// 6 for most blocks, >6 for stairs
			if (facingIndex == 6) {
				BlockFace newFacing = directional.getFacing().getOppositeFace();
				directional.setFacing(newFacing);
				debug("Reversed facing from " + currentFacing + " to " + newFacing);
			} else if (facingIndex <= 5) {
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

				debug("Trying to set facing to " + face + " valid="
						+ (face != null ? validFaces.contains(face) : "null"));

				if (face != null && validFaces.contains(face)) {
					directional.setFacing(face);
					debug("Set facing to " + face);

					// vert placement debug
					if (face == BlockFace.UP || face == BlockFace.DOWN) {
						debug("Set vertical facing: " + blockData.getMaterial() + " to " + face);
					}
				}
			} else if (blockData instanceof Stairs && facingIndex > 6) {
				// for higher indicie stairs try reversing
				BlockFace newFacing = directional.getFacing().getOppositeFace();
				directional.setFacing(newFacing);
				debug("Stairs special reverse: " + facingIndex + " from " + currentFacing + " to " + newFacing);
			}

			// chest merging
			if (blockData instanceof Chest) {
				Chest chest = (Chest) blockData;
				chest.setType(Chest.Type.SINGLE);
				BlockFace left = rotateCW(chest.getFacing());

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
				} else if (!player.isSneaking()) {
					BlockData leftBlock = block.getRelative(left).getBlockData();
					BlockData rightBlock = block.getRelative(left.getOppositeFace()).getBlockData();
					if (leftBlock.getMaterial() == chest.getMaterial() &&
							((Chest) leftBlock).getType() == Chest.Type.SINGLE &&
							((Chest) leftBlock).getFacing() == chest.getFacing()) {
						chest.setType(Chest.Type.LEFT);
					} else if (rightBlock.getMaterial() == chest.getMaterial() &&
							((Chest) rightBlock).getType() == Chest.Type.SINGLE &&
							((Chest) rightBlock).getFacing() == chest.getFacing()) {
						chest.setType(Chest.Type.RIGHT);
					}
				}
			} else if (blockData instanceof Stairs) {
				((Stairs) blockData).setShape(handleStairs(block, (Stairs) blockData));
			}
		} else if (blockData instanceof Orientable) {
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
			if (axis != null && validAxes.contains(axis)) {
				orientable.setAxis(axis);
				debug("Set axis to " + axis);
			}
		}

		// handle additional properties: stairs half toggle|repeater delay|comparator
		// mode
		protocolValue &= 0xFFFFFFF0;
		if (protocolValue >= 16) {
			if (blockData instanceof Repeater) {
				Repeater repeater = (Repeater) blockData;
				int delay = protocolValue / 16;
				if (delay >= repeater.getMinimumDelay() && delay <= repeater.getMaximumDelay()) {
					repeater.setDelay(delay);
					debug("Set repeater delay to " + delay);
				}
			} else if (protocolValue == 16) {
				if (blockData instanceof Comparator) {
					((Comparator) blockData).setMode(Comparator.Mode.SUBTRACT);
					debug("Set comparator to subtract mode");
				} else if (blockData instanceof Bisected) {
					Bisected bisected = (Bisected) blockData;
					bisected.setHalf(Bisected.Half.TOP);
					debug("Set bisected half to TOP");
				}
			}
		}

		// validate and apply block data
		boolean canPlace = block.canPlace(blockData);
		if (!canPlace && blockData instanceof Directional) {
			Directional dir = (Directional) blockData;
			if (dir.getFacing() == BlockFace.UP || dir.getFacing() == BlockFace.DOWN) {
				debug("canPlace=false for vertical: " + blockData.getMaterial() + " facing " + dir.getFacing() + " at "
						+ block.getLocation());
			}
		}

		if (canPlace) {
			// schedule the block update for next tick to bypass Paper's validation
			final BlockData finalBlockData = blockData;
			getServer().getScheduler().runTask(this, () -> {
				if (block.getType() == finalBlockData.getMaterial()) {
					block.setBlockData(finalBlockData, false);
					debug("Applied scheduled blockdata update");
				}
			});
		} else {
			event.setCancelled(true);
		}
	}

	// specifically for blocks you can't normally place without placing against
	// something i.e. candles
	private void handleAirPlacement(BlockPlaceEvent event, int protocolValue) {
		Block block = event.getBlock();
		BlockData blockData = block.getBlockData();

		// apply any directional/orientable properties from the protocol
		accurateBlockProtocol(event, protocolValue);

		// if not cancelled by accurateBlockProtocol, force the placement
		if (!event.isCancelled()) {
			final BlockData finalBlockData = blockData;
			getServer().getScheduler().runTask(this, () -> {
				block.setBlockData(finalBlockData, false);
			});
		}
	}

	private BlockFace rotateCW(BlockFace in) {
		return switch (in) {
			case NORTH -> BlockFace.EAST;
			case EAST -> BlockFace.SOUTH;
			case SOUTH -> BlockFace.WEST;
			case WEST -> BlockFace.NORTH;
			case NORTH_EAST -> BlockFace.SOUTH_EAST;
			case SOUTH_EAST -> BlockFace.SOUTH_WEST;
			case SOUTH_WEST -> BlockFace.NORTH_WEST;
			case NORTH_WEST -> BlockFace.NORTH_EAST;
			default -> in; // for UP, DOWN, SELF
		};
	}

	private Stairs.Shape handleStairs(Block block, Stairs stairs) {
		Bisected.Half half = stairs.getHalf();
		BlockFace backFace = stairs.getFacing();
		BlockFace frontFace = backFace.getOppositeFace();
		BlockFace rightFace = rotateCW(backFace);
		BlockFace leftFace = rightFace.getOppositeFace();
		Stairs backStairs = block.getRelative(backFace).getBlockData() instanceof Stairs
				? (Stairs) block.getRelative(backFace).getBlockData()
				: null;
		Stairs frontStairs = block.getRelative(frontFace).getBlockData() instanceof Stairs
				? (Stairs) block.getRelative(frontFace).getBlockData()
				: null;
		Stairs leftStairs = block.getRelative(leftFace).getBlockData() instanceof Stairs
				? (Stairs) block.getRelative(leftFace).getBlockData()
				: null;
		Stairs rightStairs = block.getRelative(rightFace).getBlockData() instanceof Stairs
				? (Stairs) block.getRelative(rightFace).getBlockData()
				: null;

		if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == leftFace) &&
				!(rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace)) {
			return Stairs.Shape.OUTER_LEFT;
		} else if ((backStairs != null && backStairs.getHalf() == half && backStairs.getFacing() == rightFace) &&
				!(leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace)) {
			return Stairs.Shape.OUTER_RIGHT;
		} else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == leftFace) &&
				!(leftStairs != null && leftStairs.getHalf() == half && leftStairs.getFacing() == backFace)) {
			return Stairs.Shape.INNER_LEFT;
		} else if ((frontStairs != null && frontStairs.getHalf() == half && frontStairs.getFacing() == rightFace) &&
				!(rightStairs != null && rightStairs.getHalf() == half && rightStairs.getFacing() == backFace)) {
			return Stairs.Shape.INNER_RIGHT;
		} else {
			return Stairs.Shape.STRAIGHT;
		}
	}

	private void onBlockBuildPacket(final PacketEvent event) {
		Player player = event.getPlayer();
		PacketContainer packet = event.getPacket();

		try {
			if (!packet.getType().equals(PacketType.Play.Client.USE_ITEM_ON)) {
				return;
			}

			MovingObjectPositionBlock clickInformation = packet.getMovingBlockPositions().read(0);
			BlockPosition blockPosition = clickInformation.getBlockPosition();
			Vector posVector = clickInformation.getPosVector();

			double originalX = posVector.getX();
			double relativeX = originalX - blockPosition.getX();

			if (relativeX >= 2) {
				// tweakeroo sends (value * 2) + 2, so we reverse it
				int protocolValue = ((int) relativeX - 2) / 2; // PUT THE DIVISION BACK

				playerPacketDataHashMap.put(player, new PacketData(blockPosition, protocolValue));

				// fix X to valid position
				posVector.setX(blockPosition.getX() + 0.5);
				clickInformation.setPosVector(posVector);
				packet.getMovingBlockPositions().write(0, clickInformation);

				debug("Fixed X from " + originalX + " to " + posVector.getX() + " (protocol=" + protocolValue + ")");
			}
		} catch (Exception e) {
			getLogger().warning("Error processing block placement packet: " + e.getMessage());
			if (config.getBoolean("debug", false)) {
				e.printStackTrace();
			}
			return;
		}
	}

	private void onCustomPayload(final PacketEvent event) {
		try {
			PacketContainer packet = event.getPacket();
			// try to get the payload data directly
			Object payload = packet.getModifier().read(1);
			if (payload == null)
				return;
			sendCarpetRules(event.getPlayer());
		} catch (Exception ignored) { // honestly don't mind ignoring this one
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
					NbtFactory.of("Rule", "accurateBlockPlacement")));
			StreamSerializer.getDefault().serializeCompound(dos, abpRule);

			dos.flush();

			rulePacket.getModifier().write(0, MinecraftReflection.getPacketDataSerializer(
					Unpooled.wrappedBuffer(fullPayload.toByteArray())));

			protocolManager.sendServerPacket(player, rulePacket);
		} catch (Exception e) {
			debug("Failed to send carpet rules: " + e.getMessage());
		}
	}
}