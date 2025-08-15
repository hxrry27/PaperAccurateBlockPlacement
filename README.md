# SpigotAccurateBlockPlacement
An implementation of the Carpet Accurate Block Placement Protocol for Spigot/Paper-based servers.
Adds support for FlexibleBlockPlacement from Tweakeroo and easyPlace from Litematica. Never place pistons, observers, or stairs wrong again!

## Version Compatibility
- **Minecraft**: 1.21.x (tested on 1.21.7)
- **Java**: 21 or higher
- **Server Software**: Spigot/Paper/Pupur
- **ProtocolLib**: 5.4.0 or higher

## Installation

### Prerequisites
1. **Java 21** or higher installed
2. **ProtocolLib 5.4.0** or higher
   - Download from SpigotMC: https://www.spigotmc.org/resources/protocollib.1997/
   - Or from GitHub releases: https://github.com/dmulloy2/ProtocolLib/releases

### Building the Plugin
```bash
# Windows (PowerShell)
./gradlew clean build

# Linux/Mac
./gradlew clean build
```

The compiled JAR will be in `build/libs/`

### Server Installation
1. Download and install the ProtocolLib dev build to your server's `plugins` folder
2. Copy the compiled `SpigotAccurateBlockPlacement-1.1.0-SNAPSHOT.jar` to your `plugins` folder
3. Restart your server

## Configuration for Clients

### For Litematica
1. Set `easyPlaceProtocolVersion` to **"Version 2"**
2. Enable easyPlace mode
3. Build with easyPlace like normal

### For Tweakeroo
1. Set `carpetAccuratePlacementProtocol` to **"true"**
2. Enable and use Flexible Block Placement

## Features
- Accurate directional block placement (pistons, observers, etc.)
- Proper stair shape calculation
- Chest merging control
- Repeater delay control
- Comparator mode switching
- Support for all orientable blocks

## Changes in v1.1.0
- Updated for Minecraft 1.21.7 compatibility
- Requires Java 21
- Improved code structure and thread safety
- Better error handling and logging
- Performance optimizations

## Known Issues
- Requires ProtocolLib dev builds for MC 1.21