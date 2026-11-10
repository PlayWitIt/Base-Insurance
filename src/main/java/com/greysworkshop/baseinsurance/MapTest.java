package com.greysworkshop.baseinsurance;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Heightmap;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Development-only map test, run with {@code ./gradlew runMapTest}: a normal world, a trip across it, then
 * checks and screenshots of the minimap and the full map. Not included in the release jar.
 */
public final class MapTest {
	private static final String WORLD = "bi-maptest";
	private static final int STEPS = 12;
	private static final int STEP_TICKS = 240;
	private static final int STEP_BLOCKS = 160;

	private static boolean started;
	private static int tick = -1;
	private static int passed;
	private static int failed;
	private static long slowestFrame;
	private static long lastFrame;
	private static boolean measuring;
	/** Acts like a server that sends empty surface heights (some do, through ViaVersion), for the second half of the trip. */
	private static boolean breakHeights;

	private MapTest() {}

	/** Fake "explored" tiles copied in to the west, to test a big map. */
	private static final int FAKE_FROM = -14, FAKE_TO = -3, FAKE_Z = 8;

	public static void start() {
		makeBigMap();
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof MapScreen) {
				// Time just the map's own drawing, so server lag doesn't count.
				ScreenEvents.beforeRender(screen).register((s, context, mx, my, delta) -> lastFrame = System.nanoTime());
				ScreenEvents.afterRender(screen).register((s, context, mx, my, delta) -> {
					if (measuring) slowestFrame = Math.max(slowestFrame, System.nanoTime() - lastFrame);
				});
			}
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> {
				if (client.getLevelStorage().levelExists(WORLD)) {
					client.createIntegratedServerLoader().start(WORLD, () -> {});
					return;
				}
				LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
						new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
				client.createIntegratedServerLoader().createAndStart(WORLD, info, new GeneratorOptions(20261020L, true, false),
						WorldPresets::createDemoOptions, new TitleScreen());
			});
		});
		ClientTickEvents.END_CLIENT_TICK.register(MapTest::tick);
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			if (!breakHeights) return;
			long[] data = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).asLongArray();
			chunk.setHeightmap(Heightmap.Type.WORLD_SURFACE, new long[data.length]);
		});
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
		BIConfig config = BIConfig.INSTANCE;
		int travelEnd = 100 + STEPS * STEP_TICKS;
		if (tick == 100) {
			config.minimap = true;
			config.minimapRotate = false;
			config.minimapZoom = 1;
			// New terrain is slow to make on a small test machine; keep the world around you small.
			client.options.getViewDistance().setValue(5);
			client.player.networkHandler.sendChatCommand("gamerule doDaylightCycle false");
			client.player.networkHandler.sendChatCommand("time set noon");
		}
		if (tick >= 100 && tick < travelEnd && (tick - 100) % STEP_TICKS == 0) {
			// An L-shaped trip: east, then south.
			int step = (tick - 100) / STEP_TICKS;
			breakHeights = step > STEPS / 2;
			int x = Math.min(step, STEPS / 2) * STEP_BLOCKS;
			int z = Math.max(0, step - STEPS / 2) * STEP_BLOCKS;
			client.player.getAbilities().flying = true;
			client.player.networkHandler.sendChatCommand("tp @s " + x + " 120 " + z + " 0 30");
			var here = client.player.getChunkPos();
			var chunk = client.world.isChunkLoaded(here.x, here.z) ? client.world.getChunk(here.x, here.z) : null;
			log("Step " + step + " to " + x + ", " + z + "; queue " + map.queueSize() + ", tiles " + map.loadedTiles()
					+ ", loaded chunks " + client.world.getChunkManager().getLoadedChunkCount() + "; was at chunk " + here
					+ (chunk == null ? " (not loaded)" : " top section " + chunk.getHighestNonEmptySectionYOffset()
					+ ", height " + chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, 8, 8))
					+ ", map colour " + Integer.toHexString(map.colorAt(here.getStartX() + 8, here.getStartZ() + 8))
					+ "; stats mapped " + map.stats.chunksMapped + " empty " + map.stats.emptyChunks + " fixed " + map.stats.heightFixed);
		}
		if (tick == 100 + 3 * STEP_TICKS - 10) screenshot(client, "map_0_minimap_travelling");

		int t = tick - travelEnd;
		switch (t) {
			case 60 -> {
				var p = client.player.getBlockPos();
				check("the ground under you is on the map", (map.colorAt(p.getX(), p.getZ()) >>> 24) != 0);
				check("the map grew along the trip (" + map.loadedTiles() + " tiles)", map.loadedTiles() >= 4);
				check("with empty surface heights from the server, the map finds the surface itself (" + map.stats.heightFixed + " columns)",
						map.stats.heightFixed > 1000);
				log("Map stats: mapped " + map.stats.chunksMapped + ", empty " + map.stats.emptyChunks + ", swept in " + map.stats.sweptIn);
				screenshot(client, "map_1_minimap");
			}
			case 70 -> config.minimapRotate = true;
			case 80 -> screenshot(client, "map_2_minimap_rotated");
			case 85 -> {
				config.minimapRotate = false;
				Minimap.zoom(-2);
			}
			case 95 -> {
				check("minimap zooms out", config.minimapZoom == 0.25);
				screenshot(client, "map_3_minimap_zoomed_out");
			}
			case 100 -> {
				Minimap.zoom(2);
				client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 2000));
				client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 2000));
			}
			case 110 -> screenshot(client, "map_4_minimap_below_effects");
			case 115 -> {
				client.player.clearStatusEffects();
				client.setScreen(new MapScreen());
			}
			case 130 -> screenshot(client, "map_5_full_map");
			case 135 -> client.player.networkHandler.sendChatCommand("tp @s ~-64 ~ ~");
			case 165 -> {
				double off = Math.abs(MapScreen.testCenterX() - client.player.getX());
				check("the full map follows you as you move (off by " + Math.round(off) + ")", off < 2);
				slowestFrame = 0;
				measuring = true;
				zoom(client, -12);
			}
			case 185 -> {
				measuring = false;
				check("zoomed right out uses overview tiles (" + map.loadedSmallTiles() + " loaded)", map.loadedSmallTiles() >= 4);
				check("zooming right out doesn't stutter (slowest frame " + slowestFrame / 1_000_000 + " ms)", slowestFrame < 50_000_000L);
				screenshot(client, "map_6_full_map_far_out");
			}
			case 190 -> zoom(client, 6);
			case 205 -> screenshot(client, "map_7_full_map_mid");
			case 206 -> {
				slowestFrame = 0;
				measuring = true;
				zoom(client, -40);
			}
			case 330 -> {
				measuring = false;
				// Every fake tile on screen should be in.
				int shown = 0, inView = 0;
				if (client.currentScreen instanceof MapScreen screen) {
					double zoom = MapScreen.testZoom();
					double x0 = MapScreen.testCenterX() - screen.width / 2.0 / zoom, x1 = MapScreen.testCenterX() + screen.width / 2.0 / zoom;
					double z0 = MapScreen.testCenterZ() - screen.height / 2.0 / zoom, z1 = MapScreen.testCenterZ() + screen.height / 2.0 / zoom;
					for (int rx = FAKE_FROM; rx <= FAKE_TO; rx++) {
						for (int rz = -FAKE_Z; rz <= FAKE_Z; rz++) {
							if ((rx + 1) * WorldMap.TILE < x0 || rx * WorldMap.TILE > x1 || (rz + 1) * WorldMap.TILE < z0 || rz * WorldMap.TILE > z1) continue;
							inView++;
							int level = MapRender.levelFor(zoom);
							if (level < 2 ? map.hasSmall(rx, rz) : map.drawableFar(level, Math.floorDiv(rx, (int) (WorldMap.farBlocks(level) / WorldMap.TILE)),
									Math.floorDiv(rz, (int) (WorldMap.farBlocks(level) / WorldMap.TILE))) != null) shown++;
						}
					}
				}
				check("a big old map fills in when zoomed right out (" + shown + " of " + inView + " tiles in view)", inView > 50 && shown == inView);
				check("...without stutter (slowest frame " + slowestFrame / 1_000_000 + " ms)", slowestFrame < 50_000_000L);
				check("...from the far levels, zoomed out to 1/" + Math.round(1 / MapScreen.testZoom()) + " (level " + MapRender.levelFor(MapScreen.testZoom()) + ")",
						MapRender.levelFor(MapScreen.testZoom()) >= 2);
				screenshot(client, "map_8_big_map");
			}
			case 331 -> {
				if (client.currentScreen instanceof MapScreen screen) {
					screen.showAll();
					int[] all = map.exploredBounds();
					double zoom = MapScreen.testZoom();
					double halfW = screen.width / 2.0 / zoom, halfH = screen.height / 2.0 / zoom;
					check("Show all fits everything explored on screen", all != null && MapScreen.testCenterX() - halfW <= all[0] && MapScreen.testCenterX() + halfW >= all[2]
							&& MapScreen.testCenterZ() - halfH <= all[1] && MapScreen.testCenterZ() + halfH >= all[3]);
				}
				int[] c1 = MapScreen.parseCoords("1200 -350"), c2 = MapScreen.parseCoords("x 10, y 64, z -20"), c3 = MapScreen.parseCoords("home");
				check("Go to reads coordinates", c1 != null && c1[0] == 1200 && c1[1] == -350 && c2 != null && c2[0] == 10 && c2[1] == -20 && c3 == null);
				int before = MapPins.count();
				MapPins.Pin pin = MapPins.add("Test pin", 5, 5, BaseManager.dimensionKey(client.world));
				boolean added = MapPins.count() == before + 1 && MapPins.in(BaseManager.dimensionKey(client.world)).contains(pin);
				MapPins.remove(pin);
				check("pins can be added and removed", added && MapPins.count() == before);
				check("the ruler gives a way and a time", MapScreen.compass(0, -100).equals("N") && MapScreen.compass(100, 0).equals("E")
						&& MapScreen.duration(90).equals("1m 30s"));
			}
			case 350 -> screenshot(client, "map_9_show_all");
			case 355 -> {
				client.setScreen(null);
				map.save();
				check("overview tiles are saved for next time", countOverviewFiles() >= 4);
			}
			case 380 -> {
				log("Results: " + passed + " passed, " + failed + " failed");
				client.scheduleStop();
			}
			default -> {}
		}
	}

	/** Copies a real tile all over the west of the map, without overview tiles, like a big map from an old version. */
	private static void makeBigMap() {
		Path dir = BIConfig.dir().resolve("local_" + WORLD).resolve("map").resolve("minecraft_overworld");
		Path source = dir.resolve("r.1.1.png");
		if (!Files.exists(source)) return;
		try {
			for (int rx = FAKE_FROM; rx <= FAKE_TO; rx++) {
				for (int rz = -FAKE_Z; rz <= FAKE_Z; rz++) {
					Files.copy(source, dir.resolve("r." + rx + "." + rz + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					Files.deleteIfExists(dir.resolve("overview").resolve("r." + rx + "." + rz + ".png"));
				}
			}
			log("Made a big map of " + (FAKE_TO - FAKE_FROM + 1) * (2 * FAKE_Z + 1) + " extra tiles");
		} catch (IOException e) {
			log("Could not make the big map: " + e);
		}
	}

	private static long countOverviewFiles() {
		Path dir = BIConfig.dir().resolve("local_" + WORLD).resolve("map").resolve("minecraft_overworld").resolve("overview");
		if (!Files.isDirectory(dir)) return 0;
		try (Stream<Path> files = Files.list(dir)) {
			return files.count();
		} catch (IOException e) {
			return 0;
		}
	}

	private static void zoom(MinecraftClient client, int clicks) {
		if (client.currentScreen instanceof MapScreen map) {
			map.mouseScrolled(map.width / 2.0, map.height / 2.0, 0, clicks);
		}
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png", client.getFramebuffer(), 1, text -> {});
	}

	private static void log(String message) {
		BaseInsuranceClient.LOGGER.info("[MAPTEST] {}", message);
	}
}
