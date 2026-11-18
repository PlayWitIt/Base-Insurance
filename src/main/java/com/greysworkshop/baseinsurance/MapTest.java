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
import java.util.List;
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
	/** What the area picker handed back. */
	private static String pickerResult;

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
		if (t > 420 && t < 470 && !pictureViewChecked && !MapExport.busy() && MapExport.last != null) {
			pictureViewChecked = true;
			checkPicture(client, "this view", pictureView);
		}
		if (t > 470 && t < 1000 && !pictureAllChecked && !MapExport.busy() && MapExport.last != null && MapExport.last.getFileName().toString().contains("_whole_")) {
			pictureAllChecked = true;
			int[] b = map.exploredBounds();
			checkPicture(client, "the whole map", new int[] {b[0], b[1], b[2] - 1, b[3] - 1});
			check("very big worlds are scaled to fit (" + MapExport.scaleFor(3_000_000, 1000) + " blocks a pixel for 3 million across)",
					MapExport.scaleFor(3_000_000, 1000) == 256 && MapExport.scaleFor(16384, 16384) == 1 && MapExport.scaleFor(16385, 10) == 2);
		}
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
			case 360 -> {
				// Groundskeeper's area file for this world, with three tiles at different stages.
				try {
					Path f = GroundskeeperArea.file(client);
					Files.createDirectories(f.getParent());
					Files.writeString(f, "{\"tileChunks\":2,\"shapes\":[{\"op\":\"add\",\"type\":\"rect\",\"x1\":0,\"z1\":0,\"x2\":31,\"z2\":31}],"
							+ "\"tiles\":{\"0,0\":{\"state\":\"DONE\",\"coverage\":100},\"1,0\":{\"state\":\"WORKED\",\"coverage\":64},\"0,1\":{\"state\":\"QUEUED\"}}}");
				} catch (IOException e) {
					log("Couldn't write the Groundskeeper file: " + e);
				}
				GroundskeeperArea.refresh();
				GroundskeeperArea.Area a = GroundskeeperArea.get(client);
				check("reads Groundskeeper's tiles (" + (a == null ? "none" : a.tiles().size() + " tiles of " + a.span()) + ")", a != null && a.tiles().size() == 3 && a.span() == 32
						&& a.at(40, 5) != null && a.at(40, 5).state() == GroundskeeperArea.State.WORKED && a.at(40, 5).coverage() == 64 && a.shapes().size() == 1);
				// Opened just as Groundskeeper opens it: by reflection, with the shapes it has.
				try {
					java.util.function.Consumer<String> done = json -> pickerResult = json == null ? "CANCELLED" : json;
					Class.forName("com.greysworkshop.baseinsurance.AreaPicker").getMethod("open", String.class, String.class, java.util.function.Consumer.class)
							.invoke(null, "Groundskeeper area", "{\"version\":1,\"shapes\":[{\"op\":\"add\",\"type\":\"rect\",\"x1\":0,\"z1\":0,\"x2\":31,\"z2\":31}]}", done);
				} catch (ReflectiveOperationException e) {
					check("the area picker opens by reflection: " + e, false);
				}
			}
			case 365 -> {
				if (!(client.currentScreen instanceof AreaPicker picker)) {
					check("the area picker opened", false);
					break;
				}
				check("the area picker opened with the area's shape", picker.shapes.size() == 1);
				picker.testView(40, 16, 3);
				picker.pick(AreaPicker.Tool.RECT);
				picker.testDrag(40, 0, 79, 31);
				picker.pick(AreaPicker.Tool.CIRCLE);
				picker.setCutting(true);
				picker.testDrag(60, 16, 65, 16);
				picker.setCutting(false);
				// A triangle: three corners, then the first again.
				picker.pick(AreaPicker.Tool.POLYGON);
				picker.polygonClick(picker.screenX(0.5), picker.screenY(40.5), 0, 40, false);
				picker.polygonClick(picker.screenX(20.5), picker.screenY(40.5), 20, 40, false);
				picker.polygonClick(picker.screenX(0.5), picker.screenY(60.5), 0, 60, false);
				picker.polygonClick(picker.screenX(0.5), picker.screenY(40.5), 0, 40, false);
				picker.pick(AreaPicker.Tool.BRUSH);
				picker.brush = 3;
				picker.testDrag(90, 0, 90, 20);
				int before = picker.shapes.size();
				picker.undo();
				long expect = 0;
				for (int x = -10; x <= 100; x++) for (int z = -10; z <= 70; z++) {
					boolean in = (x >= 0 && x <= 31 || x >= 40 && x <= 79) && z >= 0 && z <= 31;
					if ((x - 60) * (x - 60) + (z - 16) * (z - 16) <= 5 * 5 + 5) in = false;
					// The triangle (0,40) (20,40) (0,60), on column centres.
					double cx = x + 0.5, cz = z + 0.5;
					if (cz >= 40.5 && cz < 60.5 && cx >= 0.5 && cx <= 20.5 - (cz - 40.5) + 1e-9) in = true;
					if (in) expect++;
				}
				check("draws rect, circle cut-out, polygon and brush; undo takes the brush off (" + before + " shapes, then " + picker.shapes.size() + ")",
						before == 5 && picker.shapes.size() == 4 && picker.shapes.get(2).cut());
				check("area size matches the shapes exactly (" + picker.columns() + " vs " + expect + ")", picker.columns() == expect);
			}
			case 370 -> screenshot(client, "map_10_area_picker");
			case 375 -> {
				if (client.currentScreen instanceof AreaPicker picker) picker.finish(true);
				java.util.List<AreaShapes.Shape> back = pickerResult == null ? java.util.List.of() : AreaShapes.fromJson(pickerResult);
				check("Done hands the shapes back (" + back.size() + ")", client.currentScreen == null && back.size() == 4 && back.get(2).cut() && "polygon".equals(back.get(3).type));
				pickerResult = null;
				AreaPicker.open("Groundskeeper area", "", json -> pickerResult = json == null ? "CANCELLED" : json);
			}
			case 378 -> {
				if (client.currentScreen instanceof AreaPicker picker) picker.close();
				check("Cancel hands back nothing", "CANCELLED".equals(pickerResult) && client.currentScreen == null);
				client.setScreen(new MapScreen());
				MapScreen.testView(32, 32, 4);
			}
			case 384 -> screenshot(client, "map_11_groundskeeper_tiles");
			case 388 -> {
				client.setScreen(null);
				try {
					Files.deleteIfExists(GroundskeeperArea.file(client));
				} catch (IOException ignored) {
					// Only a test file.
				}
			}
			// Saving the map as a picture: the view on screen, then everything explored.
			case 390 -> {
				MapScreen m = new MapScreen();
				client.setScreen(m);
			}
			case 393 -> {
				if (client.currentScreen instanceof MapScreen m) {
					m.testCenter();
					m.openImageMenu(m.width / 2.0, m.height - 40);
				}
			}
			case 412 -> screenshot(client, "map_12_picture_menu");
			case 420 -> {
				if (client.currentScreen instanceof MapScreen m) {
					pictureView = m.viewBounds();
					m.menuPick(5);
				} else check("the picture menu is open", false);
			}
			case 470 -> {
				// Everything explored: the trip plus the big old map.
				MapExport.exportAll(client);
			}
			case 1000 -> {
				if (!pictureAllChecked) check("the whole map saved as a picture within 26 s", false);
				MapScreen m = MapScreen.forCreate("heighttest");
				client.setScreen(m);
			}
			case 1005 -> {
				if (client.currentScreen instanceof MapScreen m) {
					int you = client.player.getBlockY();
					int[] r0 = m.heightRange();
					check("a new base starts 24 below you to 48 above (" + r0[0] + " to " + r0[1] + ", you at " + you + ")",
							r0[0] == Math.max(client.world.getBottomY(), you - 24) && r0[1] == Math.min(client.world.getTopYInclusive(), you + 48));
					m.setHeight(client.world.getBottomY(), client.world.getTopYInclusive());
					int[] r1 = m.heightRange();
					check("Full height reaches from the bottom of the world to the build limit (" + r1[0] + " to " + r1[1] + ")",
							r1[0] == client.world.getBottomY() && r1[1] == client.world.getTopYInclusive());
					m.setHeight(you - 10, you + 5);
					// Scrolling on the Bottom slider moves it one block.
					var bottom = m.children().stream().filter(c -> c instanceof net.minecraft.client.gui.widget.SliderWidget sw && sw.getY() == 88).findFirst().orElse(null);
					if (bottom != null) bottom.mouseScrolled(0, 0, 0, 1);
					int[] r2 = m.heightRange();
					check("the sliders go by Y level, and scrolling moves one block (" + r2[0] + " to " + r2[1] + ")", r2[0] == you - 9 && r2[1] == you + 5);
					m.setHeight(you + 20, you + 10);
					int[] r3 = m.heightRange();
					check("the top can't go under the bottom (" + r3[0] + " to " + r3[1] + ")", r3[0] <= r3[1]);
					m.setHeight(you - 24, you + 48);
				}
			}
			case 1008 -> screenshot(client, "map_13_new_base_height");
			case 1012 -> client.setScreen(null);
			case 1020 -> {
				log("Results: " + passed + " passed, " + failed + " failed");
				client.scheduleStop();
			}
			default -> {}
		}
	}

	private static int[] pictureView;
	private static boolean pictureViewChecked, pictureAllChecked;

	/**
	 * The saved PNG is the right size, and its pixels are the map's colours at those blocks. Only small patches are
	 * read back (along the trip, where the map has something), so even a huge picture needs little memory.
	 */
	private static void checkPicture(MinecraftClient client, String what, int[] b) {
		Path file = MapExport.last;
		long across = (long) b[2] - b[0] + 1, deep = (long) b[3] - b[1] + 1;
		int scale = MapExport.scaleFor(across, deep);
		int w = (int) ((across + scale - 1) / scale), h = (int) ((deep + scale - 1) / scale);
		try (var in = javax.imageio.ImageIO.createImageInputStream(file.toFile())) {
			var reader = javax.imageio.ImageIO.getImageReadersByFormatName("png").next();
			reader.setInput(in, false, true);
			int iw = reader.getWidth(0), ih = reader.getHeight(0);
			WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
			int same = 0, mapped = 0;
			// A small picture is checked all over; a big one in patches along the trip (east along z = 0, then south
			// along x = 960).
			List<int[]> spots = new java.util.ArrayList<>();
			if ((long) iw * ih <= 4_000_000) spots.add(new int[] {b[0], b[1], iw, ih});
			else for (int k = 0; k <= 6; k++) {
				spots.add(new int[] {k * STEP_BLOCKS, 0, 16, 16});
				spots.add(new int[] {6 * STEP_BLOCKS, k * STEP_BLOCKS, 16, 16});
			}
			{
				for (int[] at : spots) {
					int px = (int) Math.floorDiv(at[0] - b[0], scale), pz = (int) Math.floorDiv(at[1] - b[1], scale);
					if (px < 0 || pz < 0 || px >= iw || pz >= ih) continue;
					int rw = Math.min(at[2], iw - px), rh = Math.min(at[3], ih - pz);
					var param = reader.getDefaultReadParam();
					param.setSourceRegion(new java.awt.Rectangle(px, pz, rw, rh));
					var img = reader.read(0, param);
					for (int dz = 0; dz < rh; dz++) {
						for (int dx = 0; dx < rw; dx++) {
							int want = map.colorAt(b[0] + (px + dx) * scale, b[1] + (pz + dz) * scale);
							if ((want >>> 24) == 0) continue;
							mapped++;
							if ((want | 0xFF000000) == img.getRGB(dx, dz)) same++;
						}
					}
				}
			}
			reader.dispose();
			check(String.format("%s saved as a picture: %s, %d × %d (expected %d × %d, 1 px = %d blocks), %d of %d mapped pixels match the map",
					what, file.getFileName(), iw, ih, w, h, scale, same, mapped), iw == w && ih == h && mapped > 100 && same == mapped);
		} catch (IOException | RuntimeException e) {
			check(what + " saved as a picture: " + e, false);
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
