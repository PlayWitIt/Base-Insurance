package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.lwjgl.glfw.GLFW;
import java.util.HashMap;
import java.util.Map;

/**
 * Development-only, run at the end of the self-test (or alone with {@code ./gradlew runAccuracyTest}): the client is
 * shown what lying servers send, and nothing of it may be reported, while real griefing anywhere in the base must be.
 * <ul>
 *   <li>First a server that hides chests: every tick, any chest the player can't see (or that's over 16 blocks off)
 *       is turned to air in the client's world, as a chest hider does. Anti-xray fakes go inside a wall, a block
 *       flickers as if a chunk were still arriving, a menu opens in place of a chest.</li>
 *   <li>Then an ordinary server (the hider off): Base Insurance should notice it sends chests out of sight, and from
 *       then on report a chest gone behind a wall with no proof needed.</li>
 * </ul>
 * The fakes are set in the client's world only; the real world on the server stays as it is.
 */
final class AccuracyTest {
	private static BlockPos c;
	private static int step = -1;
	private static int wait;
	private static int containerEvents, changesBefore;
	/** Chests the simulated hider hides when out of sight, and what's really there. Null when the hider is off. */
	private static Map<BlockPos, BlockState> hider;
	/** What was there to start with. */
	private static Map<BlockPos, BlockState> hider0;

	private AccuracyTest() {}

	static void begin(BlockPos center) {
		c = center;
		step = 0;
		wait = 0;
		// What it learned about this test server last run doesn't count.
		BIConfig.INSTANCE.serverReach.clear();
		ServerHabits.reset();
	}

	static boolean running() {
		return step >= 0;
	}

	private static boolean finished;
	/** Naughty or Nice's radar setting before the map key test: the map must leave it as it was. */
	private static boolean radarBefore;
	private static int sizeBefore;
	/** Where the walk from the map started. */
	private static int walkFromX, walkFromZ;

	/** Done, and the game is closing: nothing else should run. */
	static boolean finished() {
		return finished;
	}

	private static BlockPos at(int x, int y, int z) {
		return c.add(x, y, z);
	}

	// The room: a thick obsidian wall east of you; chests in front of it and behind it, one far off, a double chest, a
	// chest that can't be opened, an ender chest, and item frames on both sides of the wall.
	private static final int[] K = {2, 0, 2}, H = {9, 0, 0}, S = {9, 0, -2}, R = {9, 0, 2}, FAR = {-20, 0, 0}, D1 = {-3, 0, 3},
			D2 = {-2, 0, 3}, B = {-4, 0, -3}, E = {-3, 0, -3}, HOLE = {4, 0, -1}, HOPPER = {1, 0, -3}, FLOOR = {1, -1, -2},
			BEHIND = {6, 2, -2};
	/** Sealed inside the wall: where anti-xray shows fakes. */
	private static final int[][] SEALED = {{5, 1, 0}, {5, 2, -1}, {5, 2, 1}, {5, 1, -2}, {5, 2, 0}};
	/** What the simulated hider hides: everything with a block entity, as real ones do. */
	private static final int[][] CHESTS = {K, H, S, R, FAR, D1, D2, B, E};

	private static BlockPos at(int[] p) {
		return at(p[0], p[1], p[2]);
	}

static void tick(MinecraftClient client) {
		if (hider != null) hide(client);
if (wait > 0) {
			wait--;
			return;
		}
		BaseInsuranceClient mod = BaseInsuranceClient.INSTANCE;
		Base base = mod.manager.get("Acc");
		switch (step) {
			case 0 -> {
				SelfTest.log("Accuracy: building the room at " + Alerts.pos(c) + "; the server hides chests you can't see");
				client.player.networkHandler.sendChatCommand("bi delete Acc confirm");
				SelfTest.server(client, w -> {
					for (int x = 4; x <= 6; x++) {
						for (int y = 0; y <= 3; y++) {
							for (int z = -3; z <= 3; z++) w.setBlockState(at(x, y, z), Blocks.OBSIDIAN.getDefaultState());
						}
					}
					chest(w, at(K), Items.DIAMOND, 10);
					chest(w, at(H), Items.GOLD_INGOT, 10);
					chest(w, at(S), Items.REDSTONE, 10);
					chest(w, at(R), Items.IRON_INGOT, 10);
					chest(w, at(FAR), Items.COAL, 10);
					w.setBlockState(at(D1), Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH).with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
					w.setBlockState(at(D2), Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH).with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
					if (w.getBlockEntity(at(D2)) instanceof ChestBlockEntity be) be.setStack(0, new ItemStack(Items.DIAMOND, 20));
					chest(w, at(B), Items.EMERALD, 5);
					w.setBlockState(at(E), Blocks.ENDER_CHEST.getDefaultState());
					var front = new ItemFrameEntity(w, at(3, 1, 0), Direction.WEST);
					front.setHeldItemStack(new ItemStack(Items.EMERALD));
					w.spawnEntity(front);
					var behind = new ItemFrameEntity(w, at(7, 1, 1), Direction.EAST);
					behind.setHeldItemStack(new ItemStack(Items.GOLD_INGOT));
					w.spawnEntity(behind);
				});
				teleport(client, 0.5, 0, 0.5);
				next(60);
			}
			case 1 -> {
				hider = new HashMap<>();
				for (int[] p : CHESTS) hider.put(at(p), client.world.getBlockState(at(p)));
				hider0 = new HashMap<>(hider);
				next(5);
			}
			case 2 -> {
				client.player.networkHandler.sendChatCommand("bi create Acc 24 3 8");
				next(140);
			}
			case 3 -> {
				SelfTest.check("accuracy: a new base starts with no changes (" + base.changes.size() + " blocks, " + base.entityChanges.size() + " entities)",
						base.changes.isEmpty() && base.entityChanges.isEmpty());
				SelfTest.check("accuracy: it hasn't taken this server for one that shows hidden chests (" + ServerHabits.containers() + ")",
						ServerHabits.containers() == 0);
				SelfTest.check("accuracy: both item frames are on record (" + base.entities.size() + ")", base.entities.size() >= 2);
				open(client, at(K));
				next(30);
			}
			case 4 -> {
				client.player.closeHandledScreen();
				open(client, at(D2));
				next(30);
			}
			case 5 -> {
				client.player.closeHandledScreen();
				next(10);
			}
			case 6 -> {
				Base.ContainerSnapshot k = base.containers.get(at(K).asLong()), d = base.containers.get(ContainerWatcher.canonical(client.world, at(D2)).asLong());
				SelfTest.check("accuracy: chests on record with their size (single " + (k == null ? "none" : k.size) + ", double " + (d == null ? "none" : d.size) + ")",
						k != null && k.size == 27 && d != null && d.size == 54);
				SelfTest.log("Accuracy: anti-xray shows stone, a chest, an ender chest and an ore inside the wall");
				fake(client, SEALED[0], Blocks.STONE.getDefaultState());
				fake(client, SEALED[1], Blocks.CHEST.getDefaultState());
				fake(client, SEALED[2], Blocks.ENDER_CHEST.getDefaultState());
				fake(client, SEALED[3], Blocks.DIAMOND_ORE.getDefaultState());
				fake(client, SEALED[4], Blocks.STONE.getDefaultState());
				next(80);
			}
			case 7 -> {
				SelfTest.check("accuracy: anti-xray fakes inside a wall aren't reported (" + describe(base) + ")", base.changes.isEmpty()
						&& mod.scanner.unconfirmed(base) == 0);
				for (int[] p : SEALED) fake(client, p, Blocks.OBSIDIAN.getDefaultState());
				SelfTest.log("Accuracy: a floor block flickers to air for a moment, as when a chunk arrives in parts");
				fake(client, FLOOR, Blocks.AIR.getDefaultState());
				next(4);
			}
			case 8 -> {
				fake(client, FLOOR, Blocks.GRASS_BLOCK.getDefaultState());
				next(60);
			}
			case 9 -> {
				SelfTest.check("accuracy: a block that flickers for a moment isn't reported (" + describe(base) + ")", base.changes.isEmpty());
				SelfTest.check("accuracy: chests hidden out of sight (and the one 20 blocks off) aren't reported (" + describe(base) + ")", base.changes.isEmpty());
				SelfTest.log("Accuracy: walking round the wall, the chests behind it come into view (the hider shows them)");
				teleport(client, 9.5, 0, -5.5);
				next(100);
			}
			case 10 -> {
				SelfTest.check("accuracy: chests that were hidden, coming into view, aren't reported as new (" + describe(base) + ")", base.changes.isEmpty());
				teleport(client, 0.5, 0, 0.5);
				next(80);
			}
			case 11 -> {
				SelfTest.check("accuracy: back out of sight, the hider hides them again: not reported (" + describe(base) + ")", base.changes.isEmpty());
				SelfTest.check("accuracy: …they're listed as ones to go and look at (" + mod.scanner.toCheck(base).size() + ")",
						mod.scanner.toCheck(base).containsKey(at(H).asLong()) && mod.scanner.toCheck(base).containsKey(at(S).asLong()));
				SelfTest.log("Accuracy: real griefing: a hole in the wall, damage on its far side, the chest in front broken, the frame emptied, "
						+ "a hopper placed; behind the wall a chest broken (the server says so) and one taken away silently");
				SelfTest.server(client, w -> {
					w.setBlockState(at(HOLE), Blocks.AIR.getDefaultState());
					w.setBlockState(at(BEHIND), Blocks.AIR.getDefaultState());
					w.setBlockState(at(K), Blocks.AIR.getDefaultState());
					w.setBlockState(at(HOPPER), Blocks.HOPPER.getDefaultState());
					w.breakBlock(at(H), true);
					w.setBlockState(at(S), Blocks.AIR.getDefaultState());
					for (ItemFrameEntity frame : w.getEntitiesByClass(ItemFrameEntity.class, new Box(at(3, 1, 0)).expand(0.6), e -> true)) {
						frame.setHeldItemStack(ItemStack.EMPTY);
					}
				});
				hider.put(at(K), Blocks.AIR.getDefaultState());
				hider.put(at(H), Blocks.AIR.getDefaultState());
				hider.put(at(S), Blocks.AIR.getDefaultState());
				hider.put(at(HOPPER), Blocks.HOPPER.getDefaultState());
				next(100);
			}
			case 12 -> {
				SelfTest.check("accuracy: the hole in the wall is reported", kind(base, HOLE) == Base.Kind.REMOVED);
				SelfTest.check("accuracy: damage on the far side of the wall, out of sight, is reported", kind(base, BEHIND) == Base.Kind.REMOVED);
				SelfTest.check("accuracy: the chest broken in front of you is reported", kind(base, K) == Base.Kind.REMOVED);
				SelfTest.check("accuracy: the hopper placed is reported", kind(base, HOPPER) == Base.Kind.ADDED);
				SelfTest.check("accuracy: the chest broken behind the wall is reported (the server said it broke)", kind(base, H) == Base.Kind.REMOVED);
				SelfTest.check("accuracy: the emptied item frame is reported",
						base.entityChanges.values().stream().anyMatch(ch -> ch.now != null && ch.now.item.isEmpty()));
				SelfTest.check("accuracy: on a server that hides chests, one gone out of sight with no proof isn't reported, but listed to check ("
						+ describe(base) + ")", kind(base, S) == null && mod.scanner.toCheck(base).containsKey(at(S).asLong()));
				SelfTest.check("accuracy: and nothing else (" + describe(base) + ")", base.changes.size() == 5);
				SelfTest.check("accuracy: walking about on a server that hides chests, it never took it for one that doesn't ("
						+ ServerHabits.containers() + ")", ServerHabits.containers() == 0);
				SelfTest.log("Accuracy: now an ordinary server, which sends chests out of sight");
				for (Map.Entry<BlockPos, BlockState> e : hider.entrySet()) client.world.setBlockState(e.getKey(), e.getValue());
				hider = null;
				// It takes a few seconds of chests showing out of sight to believe it.
				next(200);
			}
			case 13 -> {
				SelfTest.check("accuracy: it notices this server sends chests out of sight (" + ServerHabits.containers() + " blocks)",
						ServerHabits.containers() >= 8);
				SelfTest.check("accuracy: …so the chest taken away behind the wall is reported now (" + describe(base) + ")", kind(base, S) == Base.Kind.REMOVED);
				SelfTest.check("accuracy: and the far chest that showed up isn't (" + describe(base) + ")", kind(base, FAR) == null);
				SelfTest.log("Accuracy: on it, a chest behind the wall is taken away silently, and the frame behind the wall vanishes");
				SelfTest.server(client, w -> w.setBlockState(at(R), Blocks.AIR.getDefaultState()));
				for (ItemFrameEntity frame : client.world.getEntitiesByClass(ItemFrameEntity.class, new Box(at(7, 1, 1)).expand(0.6), e -> true)) {
					client.world.removeEntity(frame.getId(), Entity.RemovalReason.DISCARDED);
				}
				next(100);
			}
			case 14 -> {
				SelfTest.check("accuracy: a chest gone behind a wall is reported, no line of sight needed", kind(base, R) == Base.Kind.REMOVED);
				SelfTest.check("accuracy: an item frame gone behind a wall is reported, no line of sight needed",
						base.entityChanges.values().stream().anyMatch(ch -> ch.now == null && ch.was.item.contains("gold")));
				containerEvents = containerEvents(base);
				SelfTest.log("Accuracy: the chest counted as gone turns up again, as if the server had started hiding chests");
				client.world.setBlockState(at(R), hider0.get(at(R)));
				SelfTest.log("Accuracy: half the double chest is broken (by you, say), then the other half is opened");
				teleport(client, -2.5, 0, 0.5);
				mod.scanner.markSelfEdit(at(D1));
				SelfTest.server(client, w -> w.setBlockState(at(D1), Blocks.AIR.getDefaultState()));
				next(30);
			}
			case 15 -> {
				SelfTest.check("accuracy: …it's dropped from the report, and the server is no longer trusted to show hidden chests ("
						+ kind(base, R) + ", " + ServerHabits.containers() + ")", kind(base, R) == null && ServerHabits.containers() == 0);
				open(client, at(D2));
				next(30);
			}
			case 16 -> {
				client.player.closeHandledScreen();
				next(10);
			}
			case 17 -> {
				SelfTest.check("accuracy: a double chest that's now single isn't reported as robbed (" + (containerEvents(base) - containerEvents) + " reports)",
						containerEvents(base) == containerEvents);
				SelfTest.server(client, w -> {
					if (w.getBlockEntity(at(D2)) instanceof ChestBlockEntity be) {
						be.getStack(0).decrement(7);
						be.markDirty();
					}
				});
				next(10);
			}
			case 18 -> {
				open(client, at(D2));
				next(30);
			}
			case 19 -> {
				client.player.closeHandledScreen();
				next(10);
			}
			case 20 -> {
				SelfTest.check("accuracy: real theft from it is still reported (" + (containerEvents(base) - containerEvents) + ")",
						containerEvents(base) == containerEvents + 1);
				containerEvents = containerEvents(base);
				open(client, at(B));
				next(30);
			}
			case 21 -> {
				client.player.closeHandledScreen();
				SelfTest.server(client, w -> w.setBlockState(at(B).up(), Blocks.STONE.getDefaultState()));
				next(20);
			}
			case 22 -> {
				SelfTest.log("Accuracy: clicking a chest that can't open, then opening an ender chest");
				open(client, at(B));
				next(5);
			}
			case 23 -> {
				open(client, at(E));
				next(30);
			}
			case 24 -> {
				SelfTest.check("accuracy: the ender chest opened (" + client.currentScreen + ")", client.currentScreen != null);
				client.player.closeHandledScreen();
				next(10);
			}
			case 25 -> {
				SelfTest.check("accuracy: another screen isn't compared with the chest clicked before it (" + (containerEvents(base) - containerEvents) + " reports)",
						containerEvents(base) == containerEvents);
				teleport(client, 0.5, 0, 0.5);
				client.setScreen(new ReportScreen(base));
				next(20);
			}
			case 26 -> {
				SelfTest.screenshot(client, "20_report_summary");
				if (client.currentScreen instanceof ReportScreen report) report.showTab("DAMAGE");
				next(10);
			}
			case 27 -> {
				SelfTest.screenshot(client, "21_report_changes");
				client.setScreen(MapScreen.forEdit(base));
				next(20);
			}
			case 28 -> {
				SelfTest.screenshot(client, "22_resize_on_map");
				client.setScreen(null);
				changesBefore = base.changes.size();
				SelfTest.log("Resize: 12 blocks wider to the west, then back");
				Commands.resizeBase(base, base.minX - 12, base.maxX, base.minZ, base.maxZ, base.minY, base.maxY, t -> SelfTest.log("  " + t.getString()));
				next(140);
			}
			case 29 -> {
				SelfTest.check("resize: the base is 12 blocks wider (" + base.sizeText() + ")", base.maxX - base.minX + 1 == 61);
				SelfTest.check("resize: its changes are kept, and the new ground raises no false alarms (" + changesBefore + " → " + base.changes.size() + ")",
						base.changes.size() == changesBefore);
				Commands.resizeBase(base, c.getX() - 2, base.maxX, base.minZ, base.maxZ, base.minY, base.maxY, t -> SelfTest.log("  " + t.getString()));
				next(60);
			}
			case 30 -> {
				boolean inside = base.changes.keySet().stream().allMatch(k -> base.contains(BlockPos.fromLong(k)));
				SelfTest.check("resize: shrinking drops what's left outside (" + base.changes.size() + " changes, all inside: " + inside + ")",
						inside && base.containers.keySet().stream().allMatch(k -> base.contains(BlockPos.fromLong(k))));
				SelfTest.log("M key: the map; its Minimap button; M again closes it; the radar is left alone");
				radarBefore = radarOn();
				BIConfig.INSTANCE.minimap = true;
				BaseInsuranceClient.openMap(client);
				next(10);
			}
			case 31 -> {
				SelfTest.check("M opens the map", client.currentScreen instanceof MapScreen);
				if (client.currentScreen instanceof MapScreen map && map.minimapButton != null) {
					SelfTest.check("the map has a Minimap button that says on (" + map.minimapButton.getMessage().getString() + ")",
							java.util.Set.of("Hide minimap", "Minimap ✔").contains(map.minimapButton.getMessage().getString()));
					map.minimapButton.onPress(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
					SelfTest.check("pressing it hides the minimap and says off (" + map.minimapButton.getMessage().getString() + ")",
							!BIConfig.INSTANCE.minimap && java.util.Set.of("Show minimap", "Minimap ✖").contains(map.minimapButton.getMessage().getString()));
					SelfTest.screenshot(client, "23_map_minimap_button");
				} else {
					SelfTest.check("the map has a Minimap button", false);
				}
				next(10);
			}
			case 32 -> {
				if (client.currentScreen != null) client.currentScreen.keyPressed(new KeyInput(GLFW.GLFW_KEY_M, 0, 0));
				SelfTest.check("M on the map closes it, and the minimap stays hidden", client.currentScreen == null && !BIConfig.INSTANCE.minimap);
				SelfTest.check("the trail radar isn't touched (" + radarBefore + " → " + radarOn() + ")", radarOn() == radarBefore);
				BaseInsuranceClient.openMap(client);
				next(10);
			}
			case 33 -> {
				if (client.currentScreen instanceof MapScreen map && map.minimapButton != null) map.minimapButton.onPress(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
				SelfTest.check("pressing it again brings the minimap back", BIConfig.INSTANCE.minimap);
				if (client.currentScreen != null) client.currentScreen.keyPressed(new KeyInput(GLFW.GLFW_KEY_ESCAPE, 0, 0));
				next(20);
			}
			case 34 -> {
				SelfTest.check("Esc closes the map and the minimap shows", client.currentScreen == null && BIConfig.INSTANCE.minimap && radarOn() == radarBefore);
				SelfTest.screenshot(client, "24_minimap_back");
				if (radarInstalled()) {
					KeyBinding radarKey = KeyBinding.byId("key.naughtyornice.radar");
					SelfTest.check("Naughty or Nice's radar has its own key, O (" + (radarKey == null ? "none" : radarKey.getBoundKeyTranslationKey()) + ")",
							radarKey != null && radarKey.getBoundKeyTranslationKey().equals("key.keyboard.o"));
					setRadar(true);
				}
				next(20);
			}
			case 35 -> {
				if (radarInstalled()) {
					int top = radar("lastTop"), right = radar("lastRight");
					int bottom = Minimap.bottomIn(BIConfig.Corner.TOP_RIGHT), left = Minimap.leftIn(BIConfig.Corner.TOP_RIGHT);
					SelfTest.check("with both on, the radar is below or beside the minimap, not over it (radar from y " + top + " to x " + right
							+ ", minimap to y " + bottom + " from x " + left + ")", BIConfig.INSTANCE.minimap && bottom > 0 && (top >= bottom || right <= left));
					SelfTest.screenshot(client, "25_minimap_and_radar");
					sizeBefore = BIConfig.INSTANCE.minimapSize;
					BIConfig.INSTANCE.minimapSize = 64;
				}
				next(20);
			}
			case 36 -> {
				if (radarInstalled()) {
					int top = radar("lastTop"), bottom = Minimap.bottomIn(BIConfig.Corner.TOP_RIGHT);
					SelfTest.check("with a small minimap there's room, so the radar goes below it (radar from y " + top + ", minimap to y " + bottom + ")",
							bottom > 0 && top >= bottom);
					SelfTest.screenshot(client, "26_small_minimap_and_radar");
					BIConfig.INSTANCE.minimapSize = sizeBefore;
					setRadar(radarBefore);
				}
				BaseInsuranceClient.openMap(client);
				next(10);
			}
			case 37 -> {
				SelfTest.check("M opens the map again", client.currentScreen instanceof MapScreen);
				if (client.currentScreen instanceof MapScreen map) {
					walkFromX = client.player.getBlockX();
					walkFromZ = client.player.getBlockZ();
					map.testRightClickAt(walkFromX, walkFromZ + 20);
					SelfTest.check("right-clicking the map opens its menu, for that spot (" + (map.menu == null ? "none" : map.menu[2] + ", " + map.menu[3]) + ")",
							map.menu != null && map.menu[2] == walkFromX && map.menu[3] == walkFromZ + 20);
				}
				next(10);
			}
			case 38 -> {
				SelfTest.screenshot(client, "27_map_menu");
				if (client.currentScreen instanceof MapScreen map && map.menu != null) {
					map.menuPick(1);
					String copied = client.keyboard.getClipboard();
					SelfTest.check("Copy coordinates copies them (" + copied + ")", copied.equals(walkFromX + " " + (walkFromZ + 20)) && map.menu == null);
					map.testRightClickAt(walkFromX, walkFromZ + 20);
					SelfTest.check("Baritone is there to walk with", Travel.available());
					map.menuPick(0);
				}
				SelfTest.check("Walk here closes the map to watch it go", client.currentScreen == null);
				if (radarInstalled()) SelfTest.check("with Naughty or Nice, it goes as one of its trips (trip bar, stop key)", nnTripActive());
				next(20 * 25);
			}
			case 39 -> {
				double off = Math.hypot(client.player.getX() - (walkFromX + 0.5), client.player.getZ() - (walkFromZ + 20.5));
				SelfTest.check(String.format(java.util.Locale.ROOT, "…and walks there (%.1f blocks off, with Naughty or Nice: %s)", off, radarInstalled()), off < 3);
				client.setScreen(null);
				BIConfig.INSTANCE.minimap = true;
				SelfTest.log("RESULT " + SelfTest.passed + " passed, " + SelfTest.failed + " failed");
				step = -1;
				finished = true;
				client.scheduleStop();
				return;
			}
			default -> {}
		}
	}

	/** The simulated chest hider: what the player can't see (or is over 16 blocks off) is air, the rest as it is. */
	private static void hide(MinecraftClient client) {
		Vec3d eye = client.player.getEyePos();
		for (Map.Entry<BlockPos, BlockState> e : hider.entrySet()) {
			BlockPos pos = e.getKey();
			boolean shown = Vec3d.ofCenter(pos).distanceTo(eye) <= Sight.CONTAINER_RANGE && Sight.visible(client.world, client.player, eye, pos);
			BlockState want = shown ? e.getValue() : Blocks.AIR.getDefaultState();
			if (client.world.getBlockState(pos) != want) client.world.setBlockState(pos, want);
		}
	}

	private static boolean radarInstalled() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("naughtyornice");
	}

	private static void setRadar(boolean on) {
		try {
			Object config = Class.forName("com.greysworkshop.naughtyornice.NNConfig").getField("INSTANCE").get(null);
			config.getClass().getField("trailRadar").setBoolean(config, on);
			if (on) config.getClass().getField("chunkTrails").setBoolean(config, true);
		} catch (ReflectiveOperationException | LinkageError e) {
			SelfTest.check("switching Naughty or Nice's radar (" + e + ")", false);
		}
	}

	private static boolean nnTripActive() {
		try {
			return (boolean) Class.forName("com.greysworkshop.naughtyornice.SmartTravel").getMethod("active").invoke(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	/** Where Naughty or Nice's radar was drawn last frame ("lastTop", "lastRight"), or -1. */
	private static int radar(String edge) {
		try {
			return Class.forName("com.greysworkshop.naughtyornice.TrailRadar").getField(edge).getInt(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			return -1;
		}
	}

	/** Naughty or Nice's radar setting, if it's installed. */
	private static boolean radarOn() {
		try {
			Object config = Class.forName("com.greysworkshop.naughtyornice.NNConfig").getField("INSTANCE").get(null);
			return config.getClass().getField("trailRadar").getBoolean(config);
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	private static void next(int ticks) {
		step++;
		wait = ticks;
	}

	private static void chest(net.minecraft.server.world.ServerWorld w, BlockPos pos, net.minecraft.item.Item item, int count) {
		w.setBlockState(pos, Blocks.CHEST.getDefaultState());
		if (w.getBlockEntity(pos) instanceof ChestBlockEntity be) be.setStack(0, new ItemStack(item, count));
	}

	/** What a lying server would send: set in the client's world only. */
	private static void fake(MinecraftClient client, int[] p, BlockState state) {
		client.world.setBlockState(at(p), state);
	}

	private static void teleport(MinecraftClient client, double x, double y, double z) {
		client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
				.requestTeleport(c.getX() + x, c.getY() + y, c.getZ() + z));
	}

	private static void open(MinecraftClient client, BlockPos pos) {
		client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
				new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false));
	}

	private static Base.Kind kind(Base base, int[] p) {
		Base.Change change = base.changes.get(at(p).asLong());
		return change == null ? null : change.kind();
	}

	private static int containerEvents(Base base) {
		return (int) base.events.stream().filter(e -> e.type == Base.EventType.CONTAINER).count();
	}

	private static String describe(Base base) {
		StringBuilder sb = new StringBuilder(base.changes.size() + " changes");
		base.changes.forEach((k, ch) -> sb.append("; ").append(Alerts.pos(BlockPos.fromLong(k))).append(" ").append(ch.was).append(" -> ").append(ch.now));
		return sb.toString();
	}
}
