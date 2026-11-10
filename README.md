# Base Insurance

A client-side Fabric mod for Minecraft 1.21.11 that snapshots your bases and tells you exactly what changed:
griefed blocks, raided chests, emptied item frames, and who was nearby. It also helps you rebuild.

By **PlayWit Creations**.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Get `base-insurance-<version>+1.21.11.jar` from the
   [Releases](https://github.com/PlayWitIt/Base-Insurance/releases) page (or build it, see below).
3. Put it in your `.minecraft/mods` folder and start the game with the Fabric profile. `/bi guide` explains the rest.

Works well with [Naughty or Nice](https://github.com/PlayWitIt/Naughty-or-Nice): its friends become Base Insurance
friends, and where it last saw players shows on the map.

**PlayWit Hub:** press **Right Shift** on the title screen, the server or world list, or the pause menu, or click
**PlayWit Hub** at the top of each PlayWit mod's settings (from Mod Menu or in game). Without Mod Menu there's also a
**PlayWit Hub** button at the top left of the title screen. It isn't a list of every mod (that's Mod Menu); it shows the main switches of every PlayWit mod you have (Naughty or Nice,
Base Insurance, Hoard Eye), so you can turn something off before you join a world, and opens each one's full settings.
Its **Backups** page saves everything the mods keep, and can keep a copy off the computer: in a folder you pick (one
that Google Drive, OneDrive or Dropbox syncs puts it in the cloud), or uploaded to **Google Drive** or **OneDrive**
after you connect them. One goes every day you play; on a new computer, connect the same place and restore from it.

## How it works
1. **Insure a base.** Run `/bi create` and draw the area on the map. Change it any time with **Resize** in its report
   (or `/bi resize <name>`): drag the edges or corners, move it, set its bottom and top.
2. **Walk around it once.** Everything within 48 blocks of you is checked, walls and rooms out of sight included. The
   report and the map show how much of the base has been checked; the HUD points to the parts still to do.
3. **Open your chests** so their contents are on record.
4. **Get told what changed,** live or when you come back. In the report: **Show me** marks a change through walls,
   **It's fine** stops reporting it, **Never count** ignores that kind of block from now on. Everything can be undone.

It also has a map of everything you've explored, a rebuild materials planner, a player tripwire, hopper-aware theft checks, and
tracking of item frames, armor stands and paintings. `/bi guide` explains everything in the game.

## Only real changes
Servers lie to clients, and Base Insurance knows how, on any server:
- **Anti-xray** (Paper, Orebfuscator and the like) swaps blocks that are sealed in on every side for stone, ores,
  obsidian or chests. Sealed-in blocks are never judged, and a block is only put in the snapshot while it has an
  open side, so a fake never gets in.
- **Chest and entity hiders** (anti-ESP) hide chests, item frames and the like when you can't see them or are far off.
  Those only count as gone when they're within 16 blocks and in plain view, and a chest that comes into view where it
  was hidden isn't "new".
- **Chunks arrive in stages** and blocks get corrected: a difference has to still be there a moment later.
- **Your own doing** isn't damage: blocks you break or place (and what falls or drops off because of it), buckets,
  piston doors, plants growing, crops, coral dying, concrete setting, ice and snow, copper ageing.
- **Chests** are only compared like for like: the screen has to be that chest's (not a server menu that opened
  instead), its contents must have fully arrived, and a double chest that's now single is just recorded afresh.

The self-test proves each of these: it shows the client what a lying server would and checks nothing is reported,
while real griefing (in view or behind a wall) still is.

## The map
Everything you explore is drawn into a map as chunks load, in vanilla map colours with hill shading, and saved per
server and dimension, so it keeps growing every time you play.
- **Minimap** in the top-right corner while you play: your bases (green intact, red damaged), nearby players and your
  coordinates. Zoom it with **-** and **=**, show or hide it with **I**, the Minimap button on the full map, or `/bi minimap`. It can turn with you, change corner and size
  in Settings → Map, and moves down out of the way of potion effect icons.
- **M** opens the full map, and M or Esc closes it. It opens at once, however big your world is: a coarse picture of
  everything shows straight away and the detail sharpens on top. The big picture is built in the background as soon as
  you join and saved, so it's ready before you press M.
- At the bottom of the map: **Center on me**, **Show all** (zooms out to everything you've ever explored there),
  **Go to…** (type coordinates, or press **G**), **New base**, **Hide minimap / Show minimap**, the minimap's **Size**
  (an outline shows how big it will be), and **Done**. On a narrow screen they're shortened ("Me", "Minimap ✔"; hover
  for what they do). The minimap setting, its size and how far you zoomed are saved and kept in every game after.
  Naughty or Nice's trail radar has its own key (**K**).
- **Full map**: follows you as you move, drag to look around, scroll to zoom from 16 pixels per block out to 4,096
  blocks per pixel, enough for a world millions of blocks across. A scale bar shows how far things are, and the top
  says how much you've explored. Click a base for its report.
- **Right-click** anywhere: how far it is and which way, the same spot in the Nether (or the Overworld from the Nether),
  **Walk here** (needs Baritone; with Naughty or Nice it goes as one of its trips, using nether highways and flying
  when it can), **Copy coordinates**, **Pin here…** (name it and press Enter), and **Measure from me**.
- **Pins**: your own pins (gold flags) and the last 5 places you died (red crosses, added by themselves) are on the
  map and listed on the left under your bases. Click one to zoom to it; right-click it to remove it or walk there.
  They're kept per server.
- **Measure**: shift-drag on the map for a ruler: how many blocks and which way, how long sprinting or by elytra, and
  how far it would be through the Nether.
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
The mod keeps itself up to date. Once per game session it asks GitHub for the latest release. A newer jar for your
Minecraft version is downloaded into your mods folder and checked against the SHA-256 GitHub publishes. When you
close the game, it's swapped in for the old jar, and one line in chat says so next time. Nothing to click. Turn it
off under Settings → Data (**Update by itself**).

All `/bi` commands are client-side and never reach the server.

## Building
Requires JDK 21. Run `./gradlew build`; the jar goes to `build/libs/`.
`./gradlew runSelfTest` runs a scripted in-game test that saves screenshots to `run/screenshots/`, ending with the
accuracy tests (`./gradlew runAccuracyTest` runs those alone, in about a minute).
`./gradlew runMapTest` travels across a normal world and checks the map and minimap (10 checks, half the trip with the server's surface heights blanked out).
