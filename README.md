# Base Insurance

A client-side Fabric mod for Minecraft 1.21.11 that snapshots your bases and tells you exactly what changed:
griefed blocks, raided chests, emptied item frames, and who was nearby. It also helps you rebuild.

By **PlayWit Creation**.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Get `base-insurance-<version>+1.21.11.jar` from the
   [Releases](https://github.com/PlayWitIt/Base-Insurance/releases) page (or build it, see below).
3. Put it in your `.minecraft/mods` folder and start the game with the Fabric profile. `/bi guide` explains the rest.

Works well with [Naughty or Nice](https://github.com/PlayWitIt/Naughty-or-Nice): its friends become Base Insurance
friends, and where it last saw players shows on the map.

**Mods panel:** press **Right Shift** on the title screen, the server or world list, or the pause menu, or click
**Mods panel** at the top left of the title screen. It shows the main switches of every PlayWit mod you have (Naughty or Nice,
Base Insurance, Container Peek), so you can turn something off before you join a world, and opens each one's full settings.

## How it works
1. **Insure a base.** Run `/bi create` and draw the area on the map.
2. **Walk around it once.** Servers (especially anarchy servers with anti-xray or anti-freecam) fake blocks far away,
   so only blocks within the trust radius of you are trusted. The HUD shows snapshot progress and points to the parts you haven't seen yet.
3. **Open your chests** so their contents are on record.
4. **Get told what changed,** live or when you come back. In `/bi report` you can **Find** changes (markers through walls), **Accept** them, or mark a block type as **Not damage**. Everything can be undone.

It also has a map of everything you've explored, a rebuild materials planner, a player tripwire, hopper-aware theft checks, and
tracking of item frames, armor stands and paintings. `/bi guide` explains everything in the game.

## The map
Everything you explore is drawn into a map as chunks load, in vanilla map colours with hill shading, and saved per
server and dimension, so it keeps growing every time you play.
- **Minimap** in the top-right corner while you play: your bases (green intact, red damaged), nearby players and your
  coordinates. Zoom it with **-** and **=**, hide it with `/bi minimap`. It can turn with you, change corner and size
  in Settings → Map, and moves down out of the way of potion effect icons.
- **Full map** with **M**: follows you as you move, drag to look around (**Center on me** to follow again), scroll to
  zoom from 16 pixels per block out to 32 blocks per pixel. Click a base for its report.
- Works on servers that send wrong surface heights with their chunks (common on older servers joined through
  ViaVersion): the map checks them and finds the ground itself. Chunks missed while joining are picked up a moment later.
- **Player sightings:** with Naughty or Nice, diamonds where it last saw the players on your lists (hover for who,
  when and their gear). Turn off under Settings → Map.
- The server address is tidied up (`Constantiam.net` and `constantiam.net:25565` are the same server), and a folder an
  older version saved under another spelling is moved into place, so your bases and map follow you.
- **Map blank?** `/bi map status` shows what the map is doing: whether it's recording, how many chunks it has drawn,
  and a link to the folder its tiles are saved in.
- Big maps stay smooth: tiles load on a background thread, and zoomed out it draws from quarter-size overview tiles
  (made automatically for maps recorded by older versions).
## Friends
Friends never set off player alerts and aren't named as suspects when something in a base breaks; on the minimap
they're green dots. Settings → **Friends** lists them: add anyone by name, remove with ✖. With Naughty or Nice, its
friends whose friend type is marked "Trusted at my bases" (and its Nice list, unless that's turned off there) are added by
themselves, marked with where they came from. `/bi friend add|remove|list` works too.

Everything Base Insurance does while the game handles a packet (chunks loading and unloading) is guarded, so an error
in it is logged and skipped instead of disconnecting you.

## Updates
Once per game session the mod says in chat if a newer version is out on GitHub, with a download link. Nothing
installs by itself; turn it off under Settings → Data.

All `/bi` commands are client-side and never reach the server.

## Building
Requires JDK 21. Run `./gradlew build`; the jar goes to `build/libs/`.
`./gradlew runSelfTest` runs a scripted in-game test (35 checks) that saves screenshots to `run/screenshots/`.
`./gradlew runMapTest` travels across a normal world and checks the map and minimap (10 checks, half the trip with the server's surface heights blanked out).
