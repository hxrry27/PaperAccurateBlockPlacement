package net.dungeondev.accurateblockplacement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.StreamSerializer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
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
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AccurateBlockPlacement extends JavaPlugin implements Listener {
	private static final String SERVUX_TWEAKS = "servux:tweaks";
	private static final String SERVUX_LITEMATICS = "servux:litematics";
	private static final String CARPET_HELLO = "carpet:hello";
	private static final int SERVUX_S2C_METADATA = 1;
	private static final int SERVUX_PROTOCOL_VERSION = 1;

	private ProtocolManager protocolManager;
	private FileConfiguration config;

	private boolean useV3;
	private boolean toggleDirectional;
	private boolean toggleOpen;
	private boolean toggleMulti;

	private V3Protocol v3;

	private final Map<Player, PacketData> playerPacketDataHashMap = new ConcurrentHashMap<>();

	@Override
	public void onEnable() {

		saveDefaultConfig();
		config = getConfig();
		loadSettings();

		getLogger().info("PaperAccurateBlockPlacement loaded! Protocol: " + (useV3 ? "v3" : "v2"));
		protocolManager = ProtocolLibrary.getProtocolManager();

		protocolManager.addPacketListener(
				new PacketAdapter(this, ListenerPriority.LOWEST, PacketType.Play.Client.USE_ITEM_ON) {
					@Override
					public void onPacketReceiving(final PacketEvent event) {
						onBlockBuildPacket(event);
					}
				});

		registerChannels();

		getServer().getPluginManager().registerEvents(this, this);
	}

	private void registerChannels() {
		var messenger = getServer().getMessenger();
		// outgoing
		registerOutgoing(messenger, SERVUX_TWEAKS);
		registerOutgoing(messenger, SERVUX_LITEMATICS);
		registerOutgoing(messenger, CARPET_HELLO);
		// incoming
		registerIncoming(messenger, SERVUX_TWEAKS);
		registerIncoming(messenger, SERVUX_LITEMATICS);
	}

	private void registerOutgoing(Messenger messenger, String channel) {
		if (!messenger.isOutgoingChannelRegistered(this, channel)) {
			messenger.registerOutgoingPluginChannel(this, channel);
		}
	}

	private void registerIncoming(Messenger messenger, String channel) {
		if (!messenger.isIncomingChannelRegistered(this, channel)) {
			messenger.registerIncomingPluginChannel(this, channel, (ch, player, message) -> {
				debug("Received request on " + ch + " from " + player.getName() + " (" + message.length + " bytes)");
				sendServuxMetadata(player, ch);
			});
		}
	}

	private void loadSettings() {
		this.useV3 = !config.getString("protocol.version", "v3").equalsIgnoreCase("v2");
		this.toggleDirectional = config.getBoolean("placement.directional", true);
		this.toggleOpen = config.getBoolean("placement.open-state", true);
		this.toggleMulti = config.getBoolean("placement.multi-state", true);
		this.v3 = new V3Protocol(getLogger(), config.getBoolean("debug", false));
	}

	private V3Protocol.Toggles toggles() {
		return new V3Protocol.Toggles() {
			@Override
			public boolean directional() {
				return toggleDirectional;
			}

			@Override
			public boolean openState() {
				return toggleOpen;
			}

			@Override
			public boolean multiState() {
				return toggleMulti;
			}
		};
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
				loadSettings();
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
		advertiseProtocol(event.getPlayer());
	}

	private void advertiseProtocol(Player player) {
		// the client only registers its receiver once the world has loaded, so send a few times over the
		// first few seconds. the incoming listener also replies whenever the client asks.
		long[] delays = { 0L, 20L, 40L, 100L, 200L };
		for (long delay : delays) {
			getServer().getScheduler().runTaskLater(this, () -> {
				if (!player.isOnline()) {
					return;
				}
				if (useV3) {
					sendServuxMetadata(player, SERVUX_TWEAKS);
					sendServuxMetadata(player, SERVUX_LITEMATICS);
				} else {
					sendCarpetHello(player);
					sendCarpetRules(player);
				}
			}, delay);
		}
	}

	private void sendCarpetHello(Player player) {
		try {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(body);
			StreamSerializer.getDefault().serializeVarInt(dos, 69);
			StreamSerializer.getDefault().serializeString(dos, "PAPER-ABP");
			dos.flush();

			player.sendPluginMessage(this, CARPET_HELLO, body.toByteArray());
		} catch (Exception e) {
			debug("Failed to send carpet hello to " + player.getName() + ": " + e.getMessage());
		}
	}

	private void sendServuxMetadata(Player player, String channel) {
		try {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(body);

			// body = varint(metadata type) + network nbt; the channel goes to the messenger, not the body
			dos.writeByte(SERVUX_S2C_METADATA); // varint for 1 is a single byte

			// nameless root compound, then entries (type, name, payload), then the end tag
			dos.writeByte(10); // TAG_Compound
			writeIntTag(dos, "version", SERVUX_PROTOCOL_VERSION);
			writeStringTag(dos, "servux", "PAPER-ABP");
			writeStringTag(dos, "name", "PAPER-ABP");
			writeStringTag(dos, "id", channel);
			dos.writeByte(0); // TAG_End

			dos.flush();

			player.sendPluginMessage(this, channel, body.toByteArray());
			debug("Sent servux metadata on " + channel + " to " + player.getName());
		} catch (Exception e) {
			debug("Failed to send servux metadata (" + channel + ") to " + player.getName() + ": " + e.getMessage());
		}
	}

	private static void writeStringTag(DataOutputStream dos, String name, String value) throws IOException {
		dos.writeByte(8); // TAG_String
		dos.writeUTF(name);
		dos.writeUTF(value);
	}

	private static void writeIntTag(DataOutputStream dos, String name, int value) throws IOException {
		dos.writeByte(3); // TAG_Int
		dos.writeUTF(name);
		dos.writeInt(value);
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

		int rawValue = packetData.rawValue();

		debug("Accurate placement: " + block.getType() + " raw=" + rawValue + " at "
				+ block.getLocation() + " clicked: " + event.getBlockAgainst().getFace(block));

		if (useV3) {
			applyV3(event, rawValue);
		} else {
			// v2: payload = facing*2 + extras, so halve it back for the legacy decoder
			accurateBlockProtocol(event, rawValue >>> 1);
		}
		playerPacketDataHashMap.remove(player);
	}

	private void applyV3(BlockPlaceEvent event, int rawValue) {
		Player player = event.getPlayer();
		Block block = event.getBlock();
		BlockData original = block.getBlockData();

		boolean replacedWater = isWaterlike(event.getBlockReplacedState().getBlockData());
		BlockData decoded = v3.decode(original, rawValue, player.getFacing(), replacedWater, toggles());

		debug("V3 decode: " + original.getAsString() + " -> " + decoded.getAsString());

		if (block.canPlace(decoded)) {
			getServer().getScheduler().runTask(this, () -> {
				if (block.getType() == decoded.getMaterial()) {
					block.setBlockData(decoded, false);
				}
			});
		} else {
			event.setCancelled(true);
		}
	}

	private static boolean isWaterlike(BlockData data) {
		if (data.getMaterial() == Material.WATER) {
			return true;
		}
		return data instanceof Waterlogged wl && wl.isWaterlogged();
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
				int rawValue = (int) relativeX - 2;

				playerPacketDataHashMap.put(player, new PacketData(blockPosition, rawValue));

				// fix X to valid position
				posVector.setX(blockPosition.getX() + 0.5);
				clickInformation.setPosVector(posVector);
				packet.getMovingBlockPositions().write(0, clickInformation);

				debug("Fixed X from " + originalX + " to " + posVector.getX() + " (raw=" + rawValue + ")");
			}
		} catch (Exception e) {
			getLogger().warning("Error processing block placement packet: " + e.getMessage());
			if (config.getBoolean("debug", false)) {
				e.printStackTrace();
			}
			return;
		}
	}

	private void sendCarpetRules(Player player) {
		try {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(body);
			StreamSerializer.getDefault().serializeVarInt(dos, 1);

			NbtCompound abpRule = NbtFactory.ofCompound("Rules", List.of(
					NbtFactory.of("Value", "true"),
					NbtFactory.of("Manager", "carpet"),
					NbtFactory.of("Rule", "accurateBlockPlacement")));
			StreamSerializer.getDefault().serializeCompound(dos, abpRule);

			dos.flush();

			player.sendPluginMessage(this, CARPET_HELLO, body.toByteArray());
		} catch (Exception e) {
			debug("Failed to send carpet rules: " + e.getMessage());
		}
	}
}