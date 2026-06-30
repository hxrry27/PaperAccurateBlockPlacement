package net.dungeondev.accurateblockplacement;

import com.comphenix.protocol.wrappers.BlockPosition;

public record PacketData(BlockPosition block, int rawValue)
{}
