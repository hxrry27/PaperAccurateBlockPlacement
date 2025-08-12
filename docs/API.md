### Accurate Block Placement – Public API and Usage

- **Plugin**: `net.dungeondev.accurateblockplacement.AccurateBlockPlacement`
- **Runtime**: Spigot/Paper 1.19.x, Java 17
- **Dependencies**: ProtocolLib, Netty (provided by server), Spigot API
- **Client support**: Litematica (easyPlace v2), Tweakeroo (Flexible Block Placement)

---

### Overview

This plugin implements the Carpet Accurate Block Placement protocol (ABP) for Spigot/Paper servers via ProtocolLib. It intercepts specific client packets during block placement and adjusts the resulting `BlockData` to match the orientation and state intended by compatible client mods.

High-level flow:
- On player join, the server announces ABP support (`carpet:hello`).
- During placement, the client encodes a small protocol value in the click payload. The server extracts and caches it.
- On `BlockPlaceEvent`, the plugin transforms the placed block state based on the protocol value (facing, axis, repeater delay, comparator mode, stairs shape, chest merging, etc.).

---

### Public classes

#### `AccurateBlockPlacement` (extends `JavaPlugin`, implements `Listener`)
The main plugin class. Registers ProtocolLib listeners and Bukkit event handlers.

- **`onEnable()`**: Entry point. Registers:
  - ProtocolLib listeners for `PacketType.Play.Client.USE_ITEM` and `PacketType.Play.Client.CUSTOM_PAYLOAD`.
  - Bukkit listeners on the plugin instance.

- **`onPlayerJoin(PlayerJoinEvent event)`**: Announces ABP support to the client.
  - Sends `Serverbound CUSTOM_PAYLOAD` on channel `carpet:hello` with payload `VarInt(69)` and string `"SPIGOT-ABP"`.

- **`onPlayerQuit(PlayerQuitEvent event)`**: Cleans up any cached placement metadata for that player.

- **`onBuildEvent(BlockPlaceEvent event)`** (priority HIGH, `ignoreCancelled=true`):
  - If placement metadata for this player exists and matches the placed block position, applies accurate placement rules and updates the `BlockData` before finalizing the placement. Otherwise, discards the cache and does nothing.

Notes for developers:
- The transformation logic is applied before the event completes. If you listen at `MONITOR` or at `HIGH` after this plugin, you will see the post-transformation `BlockData`.

#### `PacketData` (record)
Immutable container for per-placement metadata extracted from the client’s packet.
- **`block`**: `com.comphenix.protocol.wrappers.BlockPosition` – absolute target block position used to correlate with `BlockPlaceEvent`.
- **`protocolValue`**: `int` – compact value influencing how the placement is adjusted (see Protocol details).

---

### Protocol details

#### Handshake (on join)
- Channel: `carpet:hello`
- Server → Client payload: `VarInt(69)` and the identifier string `"SPIGOT-ABP"`.
- Client → Server: Responds later on the same channel with a `VarInt(420)`. The server then replies with ABP rule advertisement.

#### ABP rule advertisement
- When the server receives `VarInt(420)` on `carpet:hello`, it responds with an NBT compound:
  - Root name: `Rules`
  - Fields: `{ Value: "true", Manager: "carpet", Rule: "accurateBlockPlacement" }`

#### Placement packet (`USE_ITEM`) interception
- The client encodes an integer in the click payload using the X component of the hit vector.
- Extraction logic:
  - Compute `relativeX = posVector.x - blockPosition.x`
  - If `relativeX < 2`, discard the cache (no ABP data).
  - Else set `protocolValue = ((int) relativeX - 2) / 2`
  - Normalize `posVector.x` back to the underlying hit position remainder and write it back to the packet.
  - Cache `{ blockPosition, protocolValue }` per player.

#### Block state transformation (on `BlockPlaceEvent`)
- Only runs if cached `blockPosition` equals the placed block’s XYZ.
- Examples of adjustments:
  - **Directional blocks** (`Directional`):
    - `facingIndex = protocolValue & 0xF`
    - `0..5` map to DOWN, UP, NORTH, SOUTH, WEST, EAST; `6` means invert current facing.
  - **Chests** (`Chest`):
    - Ensures merge logic with neighbor chests based on player sneak state and placement side; protects against rotating a half-double chest.
  - **Stairs** (`Stairs`):
    - Computes shape (STRAIGHT/INNER/OUTER corners) using surrounding stairs with the same half.
  - **Orientable blocks** (`Orientable`):
    - Axis derived from `protocolValue % 3` → X, Y, Z if supported by the block.
  - **Repeaters** (`Repeater`):
    - Delay set to `protocolValue / 16` if within min/max range.
  - **Comparator** (`Comparator`):
    - If `protocolValue == 16`, sets mode to `SUBTRACT`.
  - **Bisected blocks** (`Bisected`) such as slabs/doors where applicable:
    - If `protocolValue == 16`, sets half to `TOP`.
- If the adjusted `BlockData` cannot be placed (`block.canPlace(data)` is false), the event is cancelled.

---

### Usage

- **Server admin**:
  - Install ProtocolLib and this plugin.
  - For Litematica: set `easyPlaceProtocolVersion = Version 2` and build/place with EasyPlace.
  - For Tweakeroo: set `carpetAccuratePlacementProtocol = true` and use Flexible Block Placement.

- **Plugin developer**:
  - You can observe final block states after ABP by listening to `BlockPlaceEvent` at `EventPriority.MONITOR`:
```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void afterAbp(BlockPlaceEvent event) {
    BlockData placed = event.getBlock().getBlockData();
    // Work with the adjusted state, e.g., log repeater delay
    if (placed instanceof org.bukkit.block.data.type.Repeater rep) {
        int delay = rep.getDelay();
        // ...
    }
}
```
  - If you need to avoid ABP changes for a specific placement, cancel the event earlier or intervene at a higher priority before this plugin’s `HIGH` handler runs.

---

### Build and Javadoc

- Requirements: JDK 17+
- Build: `./gradlew build`
- Generate Javadoc: `./gradlew javadoc` (outputs under `build/docs/javadoc`)

---

### Compatibility

- Targeted for Spigot/Paper 1.19.x and ProtocolLib 4.8.x.
- Behavior depends on client mod support for the ABP protocol (Litematica EasyPlace v2, Tweakeroo Flexible Block Placement).