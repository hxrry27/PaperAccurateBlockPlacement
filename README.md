## what it do
implements the easyPlace and accurateBlockPlacement protocols (v2, v3 and auto) for paper based servers, so that 
litematica's easyPlace and tweakeroo's flexible/accurate block placement reproduce the correct block
states: directional, open/closed, and multi-state placement (hinge, repeater delay,
comparator mode, slab/stair shape, etc.).

## what it don't do
some states are simply never transmitted by litematica/tweakeroo and therefore they cannot be honoured
in this, or any other server plugin claiming to. things such as stacking counts (like snow layers, candles, 
sea pickles, petals etc.), on/off powered states (client will always force powered = false) and fence / wall / pane 
connection states

for that, you will need world edit, axiom, debug stick OR if you're in survival, maybe consider my
other project [tinker](https://modrinth.com/plugin/tinker)

## how it do
- **version**: check [modrinth](https://modrinth.com/plugin/paper-accurate-block-placement) for all versions, current 
build is 26.2, and i'm not currently backporting (considering it though as the downloads are picking up!)
- **server software**: paper, purpur and any other paper derivative ones like pufferfish etc.
- **[packetevents](https://modrinth.com/plugin/packetevents)**: make sure you pick the compatible version 
for your mc version, the abp version will work with that (26.2, 26.1 etc.)

provided you leave the config as 'v3' everything you want will be available to you, should you then leave your mod
settings on their default 'Auto' mode 

## credit
the original implementation by [DungeonDev](https://github.com/DungeonDev/SpigotAccurateBlockPlacement) gets full credit 
for this mod, where due. it stopped being updated, and so i just decided to take on the job :) 

the packetevents migration and the `carpet:hello` handshake reply are ported from
[mdw19873's fork](https://github.com/mdw19873/PaperAccurateBlockPlacement) — thanks for the work, really helped :)

## license
[MIT](LICENSE), covering this fork's modifications. The original project by DungeonDev carried no
license of its own, so that upstream code is used here on the same informal terms it was published
under — the MIT grant above is not a relicensing of it
