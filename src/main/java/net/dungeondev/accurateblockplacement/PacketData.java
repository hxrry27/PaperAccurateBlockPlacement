package net.dungeondev.accurateblockplacement;

import com.github.retrooper.packetevents.util.Vector3i;

public record PacketData(Vector3i block, int rawValue)
{}
