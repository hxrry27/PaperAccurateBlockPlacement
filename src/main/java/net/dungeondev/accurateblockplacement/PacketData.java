package net.dungeondev.accurateblockplacement;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;

public record PacketData(Vector3i block, BlockFace face, int rawValue, long sequence, long receivedNanos)
{}
