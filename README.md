# Base Insurance

A client-side Fabric mod for Minecraft 1.21.11 that snapshots your bases and tells you exactly what changed:
griefed blocks, raided chests, emptied item frames, and who was nearby. It also helps you rebuild.

## How it works
1. **Insure a base.** Run `/bi create` and draw the area on the map.
2. **Walk around it once.** Servers (especially anarchy servers with anti-xray or anti-freecam) fake blocks far away,
   so only blocks within the trust radius of you are trusted. The HUD shows snapshot progress and points to the parts you haven't seen yet.
3. **Open your chests** so their contents are on record.
4. **Get told what changed,** live or when you come back. In `/bi report` you can **Find** changes (markers through walls), **Accept** them, or mark a block type as **Not damage**. Everything can be undone.

It also has an explored-world map (**M**), a rebuild materials planner, a player tripwire, hopper-aware theft checks, and
tracking of item frames, armor stands and paintings. `/bi guide` explains everything in the game.
All `/bi` commands are client-side and never reach the server.

## Building
Requires JDK 21. Run `./gradlew build`; the jar goes to `build/libs/`.
`./gradlew runSelfTest` runs a scripted in-game test (32 checks) that saves screenshots to `run/screenshots/`.
