package net.dungeondev.accurateblockplacement;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.Note;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Cake;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.CopperGolemStatue;
import org.bukkit.block.data.type.DaylightDetector;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

// decodes the litematica/tweakeroo easyplace "v3" protocol (servux). the encoded int is
// (int)(hitVec.x - pos.x) - 2. bit 0 is reserved, bits 1-3 hold the facing as Direction.getIndex()
// (down0 up1 north2 south3 west4 east5, 6 = opposite). after that each whitelisted property the block
// has is read in alphabetical name order, ceil(log2(values)) bits each, the value being the index into
// that property's values sorted by ordinal. powered/waterlogged are forced off to match the client
// (waterlogged comes back only if we replaced water), which is why a lever can never arrive powered.
final class V3Protocol {

	// these gate whether a decoded property is applied. bits are consumed either way, to keep alignment.
	interface Toggles {
		boolean directional();
		boolean openState();
		boolean multiState();
	}

	// one whitelisted property: name (drives ordering), value count (drives bit width), and how to set index i
	private record PropDesc(String name, Predicate<BlockData> has, ToIntFunction<BlockData> count,
			Category category, Applier apply) {
	}

	@FunctionalInterface
	private interface Applier {
		void apply(BlockData data, int index);
	}

	private enum Category {
		DIRECTIONAL, OPEN, MULTI
	}

	private final Logger logger;
	private final boolean debug;

	V3Protocol(Logger logger, boolean debug) {
		this.logger = logger;
		this.debug = debug;
	}

	private void debug(String msg) {
		if (debug) {
			logger.info("[V3] " + msg);
		}
	}

	// apply the decoded protocol value onto a copy of the block data. raw is (int)(hitVec.x - pos.x) - 2,
	// playerFacing is the fallback for an invalid facing, replacedWater re-applies waterlogged.
	BlockData decode(BlockData original, int raw, BlockFace playerFacing, boolean replacedWater, Toggles cfg) {
		BlockData data = original.clone();
		int v = raw;

		// facing in bits 1-3. dripstone uses vertical_direction which isn't encoded, so skip it
		boolean hasDir = (data instanceof Directional) && data.getMaterial() != Material.POINTED_DRIPSTONE;
		if (hasDir) {
			int idx = (v & 0xF) >> 1;
			// beds are two blocks; changing the foot's facing desyncs the head, so consume the bits
			// but don't apply (same as the v2 path)
			if (cfg.directional() && !(data instanceof Bed)) {
				applyFacing((Directional) data, idx, playerFacing);
			}
			v >>>= 3;
		}
		// reserved bit 0
		v >>>= 1;

		// then the rest of the whitelisted props, alphabetical by name
		for (PropDesc d : applicable(data)) {
			int n = d.count().applyAsInt(data);
			int bits = bitsFor(n);
			int index = bits == 0 ? 0 : (v & ((1 << bits) - 1));
			if (enabled(d.category(), cfg) && index < n) {
				try {
					d.apply().apply(data, index);
					debug(data.getMaterial() + ": set " + d.name() + " -> index " + index);
				} catch (Exception e) {
					debug("failed to set " + d.name() + " idx " + index + " on " + data.getMaterial() + ": "
							+ e.getMessage());
				}
			}
			v >>>= bits;
		}

		// blacklist: powered off, waterlogged off unless we replaced water
		if (data instanceof Powerable powerable) {
			powerable.setPowered(false);
		}
		if (data instanceof Waterlogged waterlogged) {
			waterlogged.setWaterlogged(replacedWater);
		}

		return data;
	}

	private static boolean enabled(Category category, Toggles cfg) {
		return switch (category) {
			case DIRECTIONAL -> cfg.directional();
			case OPEN -> cfg.openState();
			case MULTI -> cfg.multiState();
		};
	}

	private void applyFacing(Directional directional, int index, BlockFace playerFacing) {
		if (index == 6) {
			directional.setFacing(directional.getFacing().getOppositeFace());
			return;
		}
		BlockFace face = switch (index) {
			case 0 -> BlockFace.DOWN;
			case 1 -> BlockFace.UP;
			case 2 -> BlockFace.NORTH;
			case 3 -> BlockFace.SOUTH;
			case 4 -> BlockFace.WEST;
			case 5 -> BlockFace.EAST;
			default -> null;
		};
		if (face != null && directional.getFaces().contains(face)) {
			directional.setFacing(face);
		} else if (playerFacing != null && directional.getFaces().contains(playerFacing.getOppositeFace())) {
			// client falls back to entity.getHorizontalFacing().getOpposite()
			directional.setFacing(playerFacing.getOppositeFace());
		}
	}

	// ceil(log2(n)), 0 for n <= 1
	private static int bitsFor(int n) {
		if (n <= 1) {
			return 0;
		}
		return 32 - Integer.numberOfLeadingZeros(n - 1);
	}

	// the whitelisted props this block has, sorted alphabetically by name
	private List<PropDesc> applicable(BlockData data) {
		List<PropDesc> out = new ArrayList<>();
		for (PropDesc d : DESCRIPTORS) {
			if (d.has().test(data)) {
				out.add(d);
			}
		}
		out.sort(Comparator.comparing(PropDesc::name));
		return out;
	}

	// property table. name drives the alphabetical ordering, count drives the bit width, and apply sets
	// index i (the bukkit enum ordinal, which matches vanilla's order). facing isn't here, it's the
	// first-direction handling above.

	// rotation 0-15 to a blockface. 0 is south, going clockwise from above in 22.5 degree steps
	private static final BlockFace[] ROTATION_SEGMENTS = {
			BlockFace.SOUTH,
			BlockFace.SOUTH_SOUTH_WEST,
			BlockFace.SOUTH_WEST,
			BlockFace.WEST_SOUTH_WEST,
			BlockFace.WEST,
			BlockFace.WEST_NORTH_WEST,
			BlockFace.NORTH_WEST,
			BlockFace.NORTH_NORTH_WEST,
			BlockFace.NORTH,
			BlockFace.NORTH_NORTH_EAST,
			BlockFace.NORTH_EAST,
			BlockFace.EAST_NORTH_EAST,
			BlockFace.EAST,
			BlockFace.EAST_SOUTH_EAST,
			BlockFace.SOUTH_EAST,
			BlockFace.SOUTH_SOUTH_EAST
	};

	private static final List<PropDesc> DESCRIPTORS = List.of(
			new PropDesc("axis",
					d -> d instanceof Orientable,
					d -> ((Orientable) d).getAxes().size(),
					Category.DIRECTIONAL,
					(d, i) -> {
						List<Axis> axes = new ArrayList<>(((Orientable) d).getAxes());
						axes.sort(Comparator.comparingInt(Enum::ordinal));
						((Orientable) d).setAxis(axes.get(i));
					}),
			new PropDesc("half",
					d -> d instanceof Stairs || d instanceof TrapDoor,
					d -> 2,
					Category.MULTI,
					(d, i) -> ((Bisected) d).setHalf(Bisected.Half.values()[i])),
			new PropDesc("hinge",
					d -> d instanceof Door,
					d -> 2,
					Category.MULTI,
					(d, i) -> ((Door) d).setHinge(Door.Hinge.values()[i])),
			new PropDesc("copper_golem_pose",
					d -> d instanceof CopperGolemStatue,
					d -> 4,
					Category.MULTI,
					(d, i) -> ((CopperGolemStatue) d).setCopperGolemPose(CopperGolemStatue.Pose.values()[i])),
			new PropDesc("inverted",
					d -> d instanceof DaylightDetector,
					d -> 2,
					Category.MULTI,
					(d, i) -> ((DaylightDetector) d).setInverted(i == 1)),
			new PropDesc("open",
					d -> d instanceof Openable,
					d -> 2,
					Category.OPEN,
					(d, i) -> ((Openable) d).setOpen(i == 1)),
			new PropDesc("delay",
					d -> d instanceof Repeater,
					d -> 4,
					Category.MULTI,
					(d, i) -> ((Repeater) d).setDelay(i + 1)),
			new PropDesc("mode",
					d -> d instanceof org.bukkit.block.data.type.Comparator,
					d -> 2,
					Category.MULTI,
					(d, i) -> ((org.bukkit.block.data.type.Comparator) d)
							.setMode(org.bukkit.block.data.type.Comparator.Mode.values()[i])),
			new PropDesc("bites",
					d -> d instanceof Cake,
					d -> 7,
					Category.MULTI,
					(d, i) -> ((Cake) d).setBites(i)),
			new PropDesc("note",
					d -> d instanceof NoteBlock,
					d -> 25,
					Category.MULTI,
					(d, i) -> ((NoteBlock) d).setNote(new Note(i))),
			new PropDesc("face",
					d -> d instanceof FaceAttachable,
					d -> 3,
					Category.DIRECTIONAL,
					(d, i) -> ((FaceAttachable) d).setAttachedFace(FaceAttachable.AttachedFace.values()[i])),
			new PropDesc("rotation",
					d -> d instanceof Rotatable,
					d -> 16,
					Category.MULTI,
					(d, i) -> ((Rotatable) d).setRotation(ROTATION_SEGMENTS[i])),
			new PropDesc("shape",
					d -> d instanceof Stairs,
					d -> 5,
					Category.MULTI,
					(d, i) -> ((Stairs) d).setShape(Stairs.Shape.values()[i])),
			new PropDesc("shape",
					d -> d instanceof Rail,
					d -> ((Rail) d).getShapes().size(),
					Category.MULTI,
					(d, i) -> {
						List<Rail.Shape> shapes = new ArrayList<>(((Rail) d).getShapes());
						shapes.sort(Comparator.comparingInt(Enum::ordinal));
						((Rail) d).setShape(shapes.get(i));
					}),
			new PropDesc("type",
					d -> d instanceof Slab,
					d -> 3,
					Category.MULTI,
					(d, i) -> {
						Slab.Type t = Slab.Type.values()[i];
						if (t != Slab.Type.DOUBLE) {
							((Slab) d).setType(t);
						}
					}),
			new PropDesc("type",
					d -> d instanceof Chest,
					d -> 3,
					Category.DIRECTIONAL,
					(d, i) -> ((Chest) d).setType(Chest.Type.values()[i])));
}
