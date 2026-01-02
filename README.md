# PaperAccurateBlockPlacement

> A maintained fork of [DungeonDev's SpigotAccurateBlockPlacement](https://github.com/DungeonDev/SpigotAccurateBlockPlacement)

## Original Credit
Original implementation by DungeonDev. This fork maintains compatibility while adding support for newer Minecraft versions. 

## Plugin Features
Implements Carpet's Accurate Block Placement Protocol for Paper/Pupur-based servers.
Similarly adds support for FlexibleBlockPlacement from Tweakeroo and easyPlace from Litematica.

## Version Compatibility
- **Minecraft**: 1.21.7-1.21.11
- **Server Software**: Paper/Pupur
- **ProtocolLib**: 5.4.0 or higher

## Installation

### Prerequisites
1. **Java 21** or higher installed
2. **ProtocolLib 5.4.0** or higher

### Building the Plugin
```
./gradlew clean build
```

The compiled JAR will be in `build/libs/`

## Configuration advice for Clients

### For Litematica
1. Set `easyPlaceProtocolVersion` to **"Version 2"**
2. Enable easyPlace mode
3. Build with easyPlace like normal

### For Tweakeroo
1. Set `carpetAccuratePlacementProtocol` to **"true"**
2. Enable and use Flexible Block Placement

## License
The original project had no license. This fork's modifications are released under MIT License for the community benefit.
