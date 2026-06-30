# PaperAccurateBlockPlacement

> A maintained fork of [DungeonDev's SpigotAccurateBlockPlacement](https://github.com/DungeonDev/SpigotAccurateBlockPlacement)

Check us out on [Modrinth :)](https://modrinth.com/plugin/paper-accurate-block-placement)

## Original Credit
Original implementation by DungeonDev. This fork maintains compatibility while adding support for newer Minecraft versions. 

## Plugin Features
Implements the EasyPlace protocol (V2 Carpet + V3 Servux) for Paper/Purpur-based servers, so
Litematica easyPlace and Tweakeroo Flexible/Accurate Block Placement reproduce the correct block
states: directional, open/closed, and multi-state placement (hinge, repeater delay,
comparator mode, slab/stair shape, etc.).

## Version Compatibility
- **Version**: Check Modrinth for all versions, current build is 26.2, and i'm not currently backporting
- **Server Software**: Paper/Pupur
- **ProtocolLib**: 5.4.0 or higher

## Installation

### Prerequisites
1. **Java 25** or higher installed
2. **ProtocolLib 5.4.0** or higher

### Building the Plugin
```
./gradlew clean build
```

The compiled JAR will be in `build/libs/`

## Configuration advice for Clients

With the default server config (`protocol.version: v3`), set the client's **Easy Place Protocol to
`V3`** — this works immediately with no extra setup:

### For Litematica
1. Set `Easy Place Protocol` to **V3**
2. Enable easyPlace mode and build like normal

### For Tweakeroo
1. Set `Easy Place Protocol` to **V3**
2. Enable and use Flexible Block Placement

**About "Auto":** Auto also resolves to V3, but only if you first enable **Server Data Sync**
(Tweakeroo) / **Entity Data Sync** (Litematica) in the mod's **Generic** config tab. These are OFF by
default; without them the client ignores the server's Servux handshake and Auto silently does nothing.
Setting the protocol to **V3** directly is the simpler, recommended path.

If the server uses `protocol.version: v2` (legacy Carpet protocol), set the client to **V2** instead.
V3 unlocks open/closed (doors, gates, trapdoors) and multi-state placement (note pitch, hinge,
rail/stair shape, repeater delay, comparator mode); V2 carries directional only.

### What ABP/EPP can and cannot do
Some states are **never transmitted** by Litematica/Tweakeroo and therefore cannot be honoured by any
server plugin: stacking counts (snow layers, candle/sea-pickle/petal amounts — placed one at a time),
"on"/powered states (the clients force `powered=false`), and fence/wall/pane connection states. Use
WorldEdit (`//set ...`) or a debug stick for those.

## License
The original project had no license. This fork's modifications are released under MIT License for the community benefit.
