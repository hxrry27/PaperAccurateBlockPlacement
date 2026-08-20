package net.dungeondev.accurateblockplacement;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
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
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AccurateBlockPlacement extends JavaPlugin implements Listener {
	private static final String SERVUX_TWEAKS = "servux:tweaks";
	private static final String SERVUX_LITEMATICS = "servux:litematics";
	private static final String CARPET_HELLO = "carpet:hello";
	private static final int SERVUX_S2C_METADATA = 1;
	private static final int SERVUX_PROTOCOL_VERSION = 1;
	
	private static final int CARPET_PROTOCOL_VERSION = 69;
	private static final String BRAND = "PAPER-ABP";
	private static final float PROTOCOL_CURSOR_THRESHOLD = 2.0f;

	private FileConfiguration config;

	private volatile boolean debugEnabled;
	private volatile boolean useV3;
	private volatile boolean toggleDirectional;
	private volatile boolean toggleOpen;
	private volatile boolean toggleMulti;

	private V3Protocol v3;

	private final Map<UUID, PacketData> playerPacketDataHashMap = new ConcurrentHashMap<>();

	private final Set<UUID> greeted = ConcurrentHashMap.newKeySet();

	@Override
	public void onLoad() {
		PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
		PacketEvents.getAPI().load();

		PacketEvents.getAPI().getEventManager().registerListener(
				new PacketListenerAbstract(PacketListenerPriority.LOWEST) {
					@Override
					public void onPacketReceive(PacketReceiveEvent event) {
						PacketTypeCommon type = event.getPacketType();
						if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
							onBlockBuildPacket(event);
						} else if (type == PacketType.Play.Client.PLUGIN_MESSAGE) {
							onCustomPayload(event);
						}
					}
				});
	}

	@Override
	public void onEnable() {
		PacketEvents.getAPI().init();

		saveDefaultConfig();
		config = getConfig();
		loadSettings();

		getLogger().info("PaperAccurateBlockPlacement loaded! Protocol: " + (useV3 ? "v3" : "v2"));

		getServer().getPluginManager().registerEvents(this, this);
	}

	private void loadSettings() {
		this.debugEnabled = config.getBoolean("debug", false);
		this.useV3 = !config.getString("protocol.version", "v3").equalsIgnoreCase("v2");
		this.toggleDirectional = config.getBoolean("placement.directional", true);
		this.toggleOpen = config.getBoolean("placement.open-state", true);
		this.toggleMulti = config.getBoolean("placement.multi-state", true);
		this.v3 = new V3Protocol(getLogger(), debugEnabled);
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
		greeted.clear();

		if (PacketEvents.getAPI() != null) {
			PacketEvents.getAPI().terminate();
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
		if (debugEnabled) {
			getLogger().info("[DEBUG] " + message);
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		advertiseProtocol(event.getPlayer());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		playerPacketDataHashMap.remove(uuid);
		greeted.remove(uuid);
	}

	private void advertiseProtocol(Player player) {
		long[] delays = { 0L, 20L, 40L, 100L, 200L };
		for (long delay : delays) {
			getServer().getScheduler().runTaskLater(this, () -> {
				if (!player.isOnline()) {
					return;
				}
				User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
				if (user == null) {
					return;
				}
				if (useV3) {
					sendServuxMetadata(user, SERVUX_TWEAKS);
					sendServuxMetadata(user, SERVUX_LITEMATICS);
				} else {
					sendCarpetHello(user);
					sendCarpetRules(user);
				}
			}, delay);
		}
	}

	private void onCustomPayload(final PacketReceiveEvent event) {
		try {
			WrapperPlayClientPluginMessage in = new WrapperPlayClientPluginMessage(event);
			String channel = in.getChannelName();

			boolean wanted = useV3
					? (SERVUX_TWEAKS.equals(channel) || SERVUX_LITEMATICS.equals(channel))
					: CARPET_HELLO.equals(channel);
			if (!wanted) {
				return;
			}

			User user = event.getUser();
			UUID uuid = user != null ? user.getUUID() : null;
			if (uuid == null || !greeted.add(uuid)) {
				return;
			}

			debug("Handshake request on " + channel + " from " + user.getName());
			if (useV3) {
				sendServuxMetadata(user, channel);
			} else {
				sendCarpetHello(user);
				sendCarpetRules(user);
			}
		} catch (Exception ignored) {
		}
	}

	private void sendCarpetHello(User user) {
		send(user, CARPET_HELLO, Payloads.carpetHello(CARPET_PROTOCOL_VERSION, BRAND));
	}

	private void sendCarpetRules(User user) {
		send(user, CARPET_HELLO, Payloads.carpetRule("Rules", "true", "carpet", "accurateBlockPlacement"));
	}

	private void sendServuxMetadata(User user, String channel) {
		send(user, channel, Payloads.servuxMetadata(SERVUX_S2C_METADATA, SERVUX_PROTOCOL_VERSION, BRAND, channel));
	}

	private void send(User user, String channel, byte[] body) {
		try {
			user.sendPacket(new WrapperPlayServerPluginMessage(channel, body));
			debug("Sent " + body.length + " bytes on " + channel + " to " + user.getName());
		} catch (Exception e) {
			debug("Failed to send on " + channel + " to " + user.getName() + ": " + e.getMessage());
		}
	}


	private void onBlockBuildPacket(final PacketReceiveEvent event) {
		try {
			WrapperPlayClientPlayerBlockPlacement wrapper = new WrapperPlayClientPlayerBlockPlacement(event);
			Vector3f cursor = wrapper.getCursorPosition();

			float relativeX = cursor.getX();
			if (relativeX < PROTOCOL_CURSOR_THRESHOLD) {
				return;
			}

			int rawValue = (int) relativeX - 2;

			User user = event.getUser();
			UUID uuid = user != null ? user.getUUID() : null;
			if (uuid != null) {
				playerPacketDataHashMap.put(uuid, new PacketData(wrapper.getBlockPosition(), rawValue));
			}

			wrapper.setCursorPosition(new Vector3f(0.5f, cursor.getY(), cursor.getZ()));
			event.markForReEncode(true);

			debug("Fixed X from " + relativeX + " to 0.5 (raw=" + rawValue + ")");
		} catch (Exception e) {
			debug("Error processing block placement packet: " + e.getMessage());
			if (debugEnabled) {
				e.printStackTrace();
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onBuildEvent(BlockPlaceEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		PacketData packetData = playerPacketDataHashMap.get(uuid);

		if (packetData == null) {
			return;
		}

		Vector3i packetBlock = packetData.block();
		Block block = event.getBlock();
		Block clickedBlock = event.getBlockAgainst();

		boolean positionMatches = (packetBlock.getX() == block.getX() && packetBlock.getY() == block.getY()
				&& packetBlock.getZ() == block.getZ()) ||
				(packetBlock.getX() == clickedBlock.getX() && packetBlock.getY() == clickedBlock.getY()
						&& packetBlock.getZ() == clickedBlock.getZ());

		if (!positionMatches) {
			debug("Position mismatch: packet=" + packetBlock + " placed=" + block.getLocation() + " clicked="
					+ clickedBlock.getLocation());
			playerPacketDataHashMap.remove(uuid);
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
		playerPacketDataHashMap.remove(uuid);
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
}
