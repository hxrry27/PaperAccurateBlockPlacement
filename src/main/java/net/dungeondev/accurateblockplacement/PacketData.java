package net.dungeondev.accurateblockplacement;

import com.comphenix.protocol.wrappers.BlockPosition;

/**
 * Immutable container for per-placement packet data captured from the client.
 *
 * @param block         absolute block position targeted by the client
 * @param protocolValue opaque value encoded in the X component that influences placement behavior
 */
public record PacketData(BlockPosition block, int protocolValue)
{}
