package com.greysworkshop.baseinsurance;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.util.function.Consumer;

/**
 * Development-only scripted test, run with {@code ./gradlew runSelfTest}. Not included in the release jar.
 * Creates a flat world, makes a base, griefs it from the server side, and screenshots the results.
 */
public final class SelfTest {
	private static final String WORLD = "bi-selftest";

	private static boolean started;
	private static int tick = -1;
	private static BlockPos origin;
	private static BlockPos chest;

	private SelfTest() {}

	public static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		// A fresh world every run: blocks a run leaves behind (lava poured, chests placed) would change what the next one sees.
		// The last run's world is kept beside it as "-previous".
		java.nio.file.Path saves = client.runDirectory.toPath().resolve("saves");
		java.nio.file.Path world = saves.resolve(WORLD), previous = saves.resolve(WORLD + "-previous");
		try {
			if (java.nio.file.Files.exists(world)) {
				if (java.nio.file.Files.exists(previous)) {
					try (var walk = java.nio.file.Files.walk(previous)) {
						for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
					}
				}
				java.nio.file.Files.move(world, previous);
			}
		} catch (java.io.IOException e) {
			log("Could not set the old test world aside: " + e);
		}
		if (client.getLevelStorage().levelExists(WORLD)) {
			log("Loading existing test world");
			client.createIntegratedServerLoader().start(WORLD, () -> {});
			return;
		}
		log("Creating flat test world");
		LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		if (AccuracyTest.finished()) return;
		if (AccuracyTest.running()) {
			AccuracyTest.tick(client);
			return;
		}
		// The window losing focus (you using the computer meanwhile) mustn't pause the world under the test.
		client.options.pauseOnLostFocus = false;
		tick++;
		BaseInsuranceClient mod = BaseInsuranceClient.INSTANCE;
		// -Dbaseinsurance.accuracytest=true: straight to the accuracy tests.
		if (Boolean.getBoolean("baseinsurance.accuracytest") && tick == 100) {
			AccuracyTest.begin(client.player.getBlockPos().add(100, 0, 0));
			return;
		}
		switch (tick) {
			case 100 -> {
				origin = client.player.getBlockPos();
				chest = origin.add(2, 0, 2);
				log("In world at " + Alerts.pos(origin) + "; creating base");
				// Bases a stopped run left behind (they'd overlap these and take their alerts).
				for (String old : new String[] {"Test", "Home", "Hide", "Big", "Legacy"}) client.player.networkHandler.sendChatCommand("bi delete " + old + " confirm");
				client.player.networkHandler.sendChatCommand("bi create Test 10 3 6");
				client.player.networkHandler.sendChatCommand("bi create");
			}
			case 106 -> {
				check("plain /bi create opens the map", client.currentScreen instanceof MapScreen);
				updaterChecks();
			}
			case 112 -> screenshot(client, "0_create_on_map");
			case 114 -> {
				if (client.currentScreen instanceof MapScreen map) map.confirm();
			}
			case 120 -> {
				check("Insure this area creates a base called Home", mod.manager.get("Home") != null);
				client.player.networkHandler.sendChatCommand("bi delete Home confirm");
			}
			case 180 -> {
				Base base = mod.manager.get("Test");
				check("base created", base != null);
				if (base != null) {
					check("snapshot captured (" + base.baseline.sections.size() + "/" + base.sections().size() + " sections)",
							base.baseline.sections.size() == base.sections().size());
					check("no false changes right after creating (" + base.changes.size() + ")", base.changes.isEmpty());
				}
				log("Server places a chest with diamonds next to the player");
				server(client, w -> {
					w.setBlockState(chest, Blocks.CHEST.getDefaultState());
					if (w.getBlockEntity(chest) instanceof ChestBlockEntity be) {
						be.setStack(0, new ItemStack(Items.DIAMOND, 32));
						be.setStack(1, new ItemStack(Items.GOLD_INGOT, 10));
					}
				});
			}
			case 240 -> {
				Base base = mod.manager.get("Test");
				check("server-placed chest detected as a live change", base != null && base.changes.containsKey(chest.asLong()));
				client.player.networkHandler.sendChatCommand("bi accept Test");
			}
			case 260 -> {
				check("accept cleared the changes", mod.manager.get("Test").changes.isEmpty());
				log("Opening the chest");
				openChest(client);
			}
			case 300 -> client.player.closeHandledScreen();
			case 320 -> {
				Base base = mod.manager.get("Test");
				check("chest contents recorded on close", base.containers.containsKey(chest.asLong()));
				log("Server steals 20 diamonds and griefs the floor + pours lava");
				server(client, w -> {
					if (w.getBlockEntity(chest) instanceof ChestBlockEntity be) {
						be.getStack(0).decrement(20);
						be.markDirty();
					}
					for (int x = -4; x <= -2; x++) {
						for (int z = -4; z <= -2; z++) w.setBlockState(origin.add(x, -1, z), Blocks.AIR.getDefaultState());
					}
					w.setBlockState(origin.add(5, 0, -4), Blocks.LAVA.getDefaultState());
				});
			}
			case 380 -> {
				Base base = mod.manager.get("Test");
				check("live griefing detected (" + base.changes.size() + " changes)", base.changes.size() >= 10);
				log("Reopening the chest");
				openChest(client);
			}
			case 420 -> {
				client.player.closeHandledScreen();
				boolean theft = mod.manager.get("Test").events.stream().anyMatch(e -> e.type == Base.EventType.CONTAINER);
				check("theft from chest reported", theft);
			}
			case 440 -> {
				log("Simulating being away: pause scanning, grief more, then 'return'");
				BIConfig.INSTANCE.enabled = false;
				server(client, w -> {
					for (int z = 2; z <= 6; z++) w.setBlockState(origin.add(-6, -1, z), Blocks.AIR.getDefaultState());
					w.setBlockState(origin.add(-6, 0, 0), Blocks.TNT.getDefaultState());
				});
			}
			case 480 -> {
				mod.scanner.reset();
				BIConfig.INSTANCE.enabled = true;
			}
			case 540 -> {
				boolean away = mod.manager.get("Test").events.stream().anyMatch(e -> e.type == Base.EventType.AWAY_DAMAGE);
				check("away damage reported on return", away);
				Overlay.enabled = true;
				client.player.setPitch(55);
				client.player.setYaw(135);
			}
			case 560 -> screenshot(client, "1_overlay_and_chat");
			case 570 -> aimAt(client, origin.add(-3, -1, -3));
			case 590 -> screenshot(client, "2_overlay_aim");
			case 591 -> {
				BIConfig.INSTANCE.overlayFill = false;
				aimAt(client, origin.add(-6, 0, 0));
			}
			case 598 -> screenshot(client, "2b_outlines_only_tnt");
			case 599 -> BIConfig.INSTANCE.overlayFill = true;
			case 600 -> client.setScreen(new ReportScreen(mod.manager.get("Test")));
			case 620 -> screenshot(client, "3_report_overview");
			case 630 -> showTab(client, "DAMAGE");
			case 650 -> screenshot(client, "4_report_blocks");
			case 660 -> showTab(client, "CONTAINERS");
			case 680 -> screenshot(client, "5_report_containers");
			case 690 -> showTab(client, "REBUILD");
			case 710 -> screenshot(client, "6_report_rebuild");
			case 720 -> showTab(client, "TIMELINE");
			case 740 -> screenshot(client, "7_report_timeline");
			case 750 -> client.setScreen(new MapScreen());
			case 770 -> screenshot(client, "8_map");
			case 780 -> {
				client.setScreen(null);
				Overlay.enabled = false;
				client.player.networkHandler.sendChatCommand("bi accept Test");
				server(client, w -> {
					var player = client.getServer().getPlayerManager().getPlayerList().get(0);
					player.getInventory().setStack(0, new ItemStack(Items.STONE, 64));
					player.getInventory().setSelectedSlot(0);
				});
			}
			case 790 -> {
				liveEventsBefore = countEvents(mod, Base.EventType.LIVE_DAMAGE);
				log("Player breaks a block and places one (own edits)");
				client.interactionManager.attackBlock(origin.add(1, -1, 0), Direction.UP);
			}
			case 796 -> {
				BlockPos below = origin.add(0, -1, 2);
				client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
						new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false));
			}
			case 800 -> {
				log("Server swaps a buried block, like anti-xray does");
				server(client, w -> w.setBlockState(origin.add(2, -3, 2), Blocks.GOLD_BLOCK.getDefaultState()));
			}
			case 880 -> {
				Base base = mod.manager.get("Test");
				check("own edits and buried swaps aren't reported (" + base.changes.size() + " changes, "
						+ (countEvents(mod, Base.EventType.LIVE_DAMAGE) - liveEventsBefore) + " new alerts)",
						base.changes.isEmpty() && countEvents(mod, Base.EventType.LIVE_DAMAGE) == liveEventsBefore);
				client.setScreen(new SettingsScreen(null));
			}
			case 895 -> screenshot(client, "9_settings_alerts");
			case 900 -> showSettingsTab(client, 1);
			case 910 -> screenshot(client, "9_settings_detection");
			case 915 -> showSettingsTab(client, 2);
			case 925 -> screenshot(client, "9_settings_overlay");
			case 930 -> showSettingsTab(client, 3);
			case 940 -> screenshot(client, "9_settings_map");
			case 945 -> showSettingsTab(client, 4);
			case 955 -> screenshot(client, "9_settings_data");
			case 965 -> {
				client.setScreen(null);
				client.player.networkHandler.sendChatCommand("bi delete Hide confirm");
				client.player.networkHandler.sendChatCommand("bi create Hide 20 4 40");
			}
			case 1000 -> {
				Base hide = mod.manager.get("Hide");
				check("Hide base snapshot started near the player (" + hide.snapshotPercent() + "%)", hide.snapshotPercent() > 0);
				log("Server reveals hidden terrain: a whole section of air becomes stone, deepslate and lava");
				BIConfig.INSTANCE.enabled = false;
				int baseX = origin.getX() >> 4 << 4, baseZ = origin.getZ() >> 4 << 4;
				server(client, w -> {
					for (int y = -48; y <= -33; y++) {
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								var state = (x * 7 + z * 3 + y) % 31 == 0 ? Blocks.LAVA.getDefaultState()
										: y < -40 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState();
								w.setBlockState(new BlockPos(baseX + x, y, baseZ + z), state, 2);
							}
						}
					}
				});
			}
			case 1020 -> {
				mod.scanner.reset();
				BIConfig.INSTANCE.enabled = true;
			}
			case 1080 -> {
				Base hide = mod.manager.get("Hide");
				check("terrain appearing where the server sent air isn't reported (" + hide.changes.size() + " changes)",
						hide.changes.isEmpty());
				log("A griefer places cobblestone and a lava source in the open");
				server(client, w -> {
					w.setBlockState(origin.add(-8, 0, 8), Blocks.COBBLESTONE.getDefaultState());
					w.setBlockState(origin.add(-8, 1, 8), Blocks.COBBLESTONE.getDefaultState());
					w.setBlockState(origin.add(-9, 0, 8), Blocks.COBBLESTONE.getDefaultState());
					// In plain view (not behind the cobblestone): only what you can see counts.
				w.setBlockState(origin.add(-12, 0, -12), Blocks.LAVA.getDefaultState());
				});
			}
			case 1130 -> {
				Base hide = mod.manager.get("Hide");
				check("real griefing (cobblestone, lava) is still reported (" + hide.changes.size() + " changes)",
						hide.changes.size() >= 4);
			}
			case 1140 -> {
				log("Trust radius: a big base, changed far from the player");
				client.player.networkHandler.sendChatCommand("bi delete Big confirm");
				client.player.networkHandler.sendChatCommand("bi create Big 64 2 4");
			}
			case 1180 -> {
				Base big = mod.manager.get("Big");
				int percent = big.snapshotPercent();
				check("snapshot progress is partial for a base bigger than the trust radius (" + percent + "%)", percent > 0 && percent < 100);
				server(client, w -> {
					w.setBlockState(origin.add(50, 0, 0), Blocks.COBBLESTONE.getDefaultState());
					w.setBlockState(origin.add(52, -1, 0), Blocks.AIR.getDefaultState());
				});
			}
			case 1240 -> {
				check("changes far outside the trust radius aren't reported (" + mod.manager.get("Big").changes.size() + ")",
						mod.manager.get("Big").changes.isEmpty());
				client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
						.requestTeleport(origin.getX() + 46.5, origin.getY(), origin.getZ() + 0.5));
			}
			case 1300 -> {
				check("first close look adopts what's there without alerts (" + mod.manager.get("Big").changes.size() + ")",
						mod.manager.get("Big").changes.isEmpty());
				server(client, w -> w.setBlockState(origin.add(50, -1, 3), Blocks.AIR.getDefaultState()));
			}
			case 1350 -> {
				check("once seen up close, a block is protected: change detected (" + mod.manager.get("Big").changes.size() + ")",
						mod.manager.get("Big").changes.size() == 1);
				aimAt(client, origin.add(50, -1, 3));
				Overlay.enabled = true;
			}
			case 1365 -> screenshot(client, "10_trust_hud");
			case 1370 -> {
				ReportScreen report = new ReportScreen(mod.manager.get("Big"));
				client.setScreen(report);
				report.showTab("DAMAGE");
			}
			case 1380 -> screenshot(client, "11_report_damage");
			case 1385 -> {
				if (client.currentScreen instanceof ReportScreen report) report.testAction("Never count");
			}
			case 1390 -> {
				Base big = mod.manager.get("Big");
				boolean ignored = BIConfig.INSTANCE.ignoredBlocks.contains("minecraft:grass_block");
				check("Not damage ignores the block type and clears its changes (ignored=" + ignored + ", " + big.changes.size() + " left)",
						ignored && big.changes.isEmpty());
			}
			case 1392 -> screenshot(client, "11b_report_after_not_damage");
			case 1395 -> {
				Base big = mod.manager.get("Big");
				BaseActions.undo(big);
				boolean ignored = BIConfig.INSTANCE.ignoredBlocks.contains("minecraft:grass_block");
				check("Undo restores the change and un-ignores the block (ignored=" + ignored + ", " + big.changes.size() + " changes)",
						!ignored && big.changes.size() == 1);
				if (client.currentScreen instanceof ReportScreen report) report.showTab("DAMAGE");
			}
			case 1400 -> {
				if (client.currentScreen instanceof ReportScreen report) report.testAction("Show me");
			}
			case 1404 -> {
				check("Find starts the damage locator and closes the report", DamageLocator.active() && client.currentScreen == null);
				client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
						.requestTeleport(origin.getX() + 38.5, origin.getY(), origin.getZ() - 8.5));
			}
			case 1412 -> aimAt(client, origin.add(20, 0, -20));
			case 1420 -> screenshot(client, "12_damage_locator");
			case 1430 -> {
				DamageLocator.stop();
				client.setScreen(new MapScreen());
			}
			case 1445 -> screenshot(client, "13_map_coverage");
			case 1450 -> client.setScreen(new GuideScreen(null));
			case 1460 -> screenshot(client, "14_guide");
			case 1465 -> {
				client.setScreen(null);
				BIConfig.INSTANCE.alertStyle = BIConfig.AlertStyle.TOAST;
				server(client, w -> w.setBlockState(origin.add(40, 0, -6), Blocks.COBBLESTONE.getDefaultState()));
			}
			case 1690 -> {
				// After the 10s live-alert cooldown since the last alert for this base.
				check("live change recorded while alerts are pop-ups", mod.manager.get("Big").changes.size() >= 2);
				screenshot(client, "15_toast_alert");
				BIConfig.INSTANCE.alertStyle = BIConfig.AlertStyle.CHAT;
			}
			case 1700 -> {
				log("Hopper test: a chest with a hopper underneath");
				BIConfig.INSTANCE.liveAlertCooldownSeconds = 2;
				client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
						.requestTeleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5));
				server(client, w -> {
					BlockPos chest2 = origin.add(-2, 0, 3);
					w.setBlockState(chest2.down(), Blocks.HOPPER.getDefaultState());
					w.setBlockState(chest2, Blocks.CHEST.getDefaultState());
					if (w.getBlockEntity(chest2) instanceof ChestBlockEntity be) be.setStack(0, new ItemStack(Items.DIAMOND, 10));
				});
			}
			case 1730 -> client.player.networkHandler.sendChatCommand("bi accept Test");
			case 1735 -> openChestAt(client, origin.add(-2, 0, 3));
			case 1755 -> client.player.closeHandledScreen();
			case 1758 -> check("container is recorded in the smallest base containing it (Test)",
					mod.manager.get("Test").containers.containsKey(origin.add(-2, 0, 3).asLong()));
			case 1760 -> server(client, w -> {
				if (w.getBlockEntity(origin.add(-2, 0, 3)) instanceof ChestBlockEntity be) {
					be.getStack(0).decrement(5);
					be.markDirty();
				}
			});
			case 1780 -> openChestAt(client, origin.add(-2, 0, 3));
			case 1800 -> {
				client.player.closeHandledScreen();
				String last = mod.manager.get("Test").events.stream().filter(e -> e.type == Base.EventType.CONTAINER)
						.reduce((a, b) -> b).map(e -> e.text).orElse("");
				check("missing items next to a hopper are noted, not alerted (" + last + ")",
						last.contains("hopper is attached"));
				log("Explosive test: primed TNT nearby when a block breaks");
				server(client, w -> {
					var tnt = new net.minecraft.entity.TntEntity(w, origin.getX() + 3.5, origin.getY(), origin.getZ() - 3.5, null);
					tnt.setFuse(2000);
					w.spawnEntity(tnt);
				});
			}
			case 1830 -> server(client, w -> w.setBlockState(origin.add(-5, -1, -6), Blocks.AIR.getDefaultState()));
			case 1850, 1870 -> log("  TNT hole: " + mod.scanner.debug(client, mod.manager.get("Test"), origin.add(-5, -1, -6)));
			case 1890 -> {
				var events = mod.manager.get("Test").events;
				String last = events.stream().filter(e -> e.type == Base.EventType.LIVE_DAMAGE).reduce((a, b) -> b).map(e -> e.text).orElse("");
				check("live alert names the explosive nearby (" + last + ")", last.contains("Primed TNT"));
				long bigLive = mod.manager.get("Big").events.stream().filter(e -> e.type == Base.EventType.LIVE_DAMAGE
						&& e.time > System.currentTimeMillis() - 5000).count();
				check("overlapping bases don't repeat the alert (Big logged " + bigLive + " in the last 5s)", bigLive == 0);
				server(client, w -> w.getEntitiesByType(net.minecraft.entity.EntityType.TNT, e -> true).forEach(net.minecraft.entity.Entity::discard));
				BIConfig.INSTANCE.liveAlertCooldownSeconds = 10;
			}
			case 1900 -> client.player.networkHandler.sendChatCommand("bi create Legacy 5 2 2");
			case 1930 -> {
				log("Simulating an old (v1) snapshot with a stale change, then reloading");
				Base legacy = mod.manager.get("Legacy");
				Base.Change fake = new Base.Change();
				fake.was = "minecraft:stone";
				fake.now = "minecraft:air";
				fake.firstSeen = System.currentTimeMillis();
				legacy.changes.put(origin.add(1, -1, 1).asLong(), fake);
				legacy.dirty = true;
				mod.manager.saveAll(true);
				String world = mod.manager.worldKey();
				java.nio.file.Path file = BIConfig.dir().resolve(world.replaceAll("[^A-Za-z0-9._-]", "_")).resolve("Legacy.snapshot.gz");
				try {
					writeV1(legacy.baseline, file);
				} catch (java.io.IOException e) {
					log("FAIL could not write v1 snapshot: " + e);
				}
				mod.manager.switchTo(null);
				mod.manager.switchTo(world);
				Base reloaded = mod.manager.get("Legacy");
				String last = reloaded.events.get(reloaded.events.size() - 1).text;
				check("old snapshots load untrusted and their stale changes are cleared (" + reloaded.changes.size() + " left; "
						+ last + ")", reloaded.changes.isEmpty() && last.startsWith("Upgraded") && reloaded.snapshotPercent() == 0);
			}
			case 1950 -> {
				log("Entity watch: an item frame with a diamond and an armor stand");
				client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
						.requestTeleport(origin.getX() + 3.5, origin.getY(), origin.getZ() + 6.5));
				server(client, w -> {
					// Well away from the lava poured earlier in the test.
					w.setBlockState(origin.add(7, 1, 8), Blocks.STONE.getDefaultState());
					var frame = new net.minecraft.entity.decoration.ItemFrameEntity(w, origin.add(6, 1, 8), Direction.WEST);
					frame.setHeldItemStack(new ItemStack(Items.DIAMOND));
					w.spawnEntity(frame);
					var stand = new net.minecraft.entity.decoration.ArmorStandEntity(w, origin.getX() + 4.5, origin.getY(), origin.getZ() + 8.5);
					stand.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
					w.spawnEntity(stand);
				});
			}
			case 2000 -> {
				for (var e : mod.manager.get("Test").entities.entrySet()) log("  entity record " + e.getKey() + ": " + e.getValue().detail());
				long frames = mod.manager.get("Test").entities.keySet().stream().filter(k -> k.startsWith("minecraft:item_frame")).count();
				long stands = mod.manager.get("Test").entities.keySet().stream().filter(k -> k.startsWith("minecraft:armor_stand")).count();
				// Nudge the stand: being pushed isn't a change.
				server(client, w -> w.getEntitiesByType(net.minecraft.entity.EntityType.ARMOR_STAND, e -> true)
						.forEach(stand -> stand.setPosition(stand.getX() + 1.2, stand.getY(), stand.getZ())));
				check("item frame and armor stand recorded (" + frames + " frame, " + stands + " stand)", frames == 1 && stands == 1);
				log("A griefer takes the diamond out of the frame");
				server(client, w -> w.getEntitiesByType(net.minecraft.entity.EntityType.ITEM_FRAME, e -> true)
						.forEach(f -> f.setHeldItemStack(ItemStack.EMPTY)));
			}
			case 2050 -> {
				for (var e : mod.manager.get("Test").entities.entrySet()) log("  entity record " + e.getKey() + ": " + e.getValue().detail());
				for (var e : mod.manager.get("Test").entityChanges.entrySet()) log("  entity change " + e.getKey() + ": " + e.getValue().describe());
				client.world.getEntitiesByClass(net.minecraft.entity.decoration.ItemFrameEntity.class,
						new net.minecraft.util.math.Box(origin).expand(12), e -> true)
						.forEach(f -> log("  client frame " + EntityWatch.key(f) + " holds " + f.getHeldItemStack()));
				var changes = mod.manager.get("Test").entityChanges.values();
				String text = changes.stream().map(Base.EntityChange::describe).reduce("", (a, b) -> a + b + "; ");
				check("emptied item frame reported (" + text + ")", changes.size() == 1 && text.contains("emptied"));
				log("A griefer breaks the armor stand");
				server(client, w -> w.getEntitiesByType(net.minecraft.entity.EntityType.ARMOR_STAND, e -> true)
						.forEach(net.minecraft.entity.Entity::discard));
			}
			case 2120 -> {
				var changes = mod.manager.get("Test").entityChanges.values();
				for (var e : mod.manager.get("Test").entityChanges.entrySet()) log("  entity change " + e.getKey() + ": " + e.getValue().describe());
				check("removed armor stand reported (" + changes.size() + " entity changes)",
						changes.size() == 2 && changes.stream().anyMatch(c -> c.now == null && c.was.type.equals("minecraft:armor_stand")));
				BaseActions.acceptAll(mod.manager.get("Test"));
			}
			case 2140 -> {
				log("The player breaks the (now empty) frame themselves");
				client.world.getEntitiesByClass(net.minecraft.entity.decoration.ItemFrameEntity.class,
						new net.minecraft.util.math.Box(origin.add(6, 1, 8)).expand(1), e -> true)
						.forEach(frame -> client.interactionManager.attackEntity(client.player, frame));
			}
			case 2220 -> check("own frame breaking isn't reported (" + mod.manager.get("Test").entityChanges.size() + " entity changes)",
					mod.manager.get("Test").entityChanges.isEmpty());
			case 2150 -> server(client, w -> {
				// Some fresh damage for the Rebuild tab (well before it opens, so a scan pass has seen it).
				for (int x = 2; x <= 4; x++) w.setBlockState(origin.add(x, -1, 6), Blocks.AIR.getDefaultState());
			});
			case 2210 -> {
				Base t = mod.manager.get("Test");
				check("a block replaced by flowing lava still counts as damage (" + t.changes.size() + " changes; block is "
						+ client.world.getBlockState(origin.add(3, -1, 6)).getBlock().getName().getString() + ")", t.changes.size() >= 3);
			}
			case 2225 -> {
				ReportScreen report = new ReportScreen(mod.manager.get("Test"));
				client.setScreen(report);
				report.showTab("REBUILD");
			}
			case 2230 -> {
				if (client.currentScreen instanceof ReportScreen report) {
					log("Rebuild tab: " + report.testDescribe() + " (changes: " + mod.manager.get("Test").changes.size() + ")");
					report.testAction("Fetch with Hoard Eye");
				}
			}
			case 2233 -> {
				if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("hoardeye")) {
					log("Hoard Eye hand-off: skipped (Hoard Eye isn't installed in this test)");
					break;
				}
				java.nio.file.Path handOff = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("hoardeye-fetch.json");
				check("Rebuild tab hands the materials to Hoard Eye", java.nio.file.Files.exists(handOff));
				try {
					client.setScreen((net.minecraft.client.gui.screen.Screen) Class.forName("com.greysworkshop.hoardeye.SearchScreen")
							.getConstructor().newInstance());
				} catch (ReflectiveOperationException e) {
					log("FAIL could not open Hoard Eye's search: " + e);
				}
			}
			case 2245 -> {
				if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("hoardeye")) break;
				java.nio.file.Path handOff = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("hoardeye-fetch.json");
				check("Hoard Eye picked up the handed-over fetch list", !java.nio.file.Files.exists(handOff));
				screenshot(client, "16_fetch_from_rebuild");
			}
			case 2260 -> {
				client.setScreen(null);
				Base base = mod.manager.get("Test");
				log("Final: " + base.changes.size() + " changes; events:");
				for (Base.Event event : base.events) log("  " + event.type + ": " + event.text);
				// Then the accuracy tests: what servers fake must never be reported.
				AccuracyTest.begin(origin.add(100, 0, 0));
			}
			default -> {}
		}
	}

	/** Points the camera at the centre of a block. */
	static void aimAt(MinecraftClient client, BlockPos target) {
		Vec3d eye = client.player.getEyePos();
		Vec3d d = Vec3d.ofCenter(target).subtract(eye);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		client.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		client.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, horizontal)));
	}

	private static int liveEventsBefore;

	/** Writes a snapshot in the pre-1.4.0 format (no trusted bits), to test upgrading. */
	private static void writeV1(Baseline baseline, java.nio.file.Path file) throws java.io.IOException {
		try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file)))) {
			out.writeInt(1);
			out.writeInt(baseline.sections.size());
			for (var entry : baseline.sections.entrySet()) {
				Baseline.Section section = entry.getValue();
				out.writeLong(entry.getKey());
				out.writeShort(section.palette.size());
				for (var state : section.palette) out.writeUTF(state == null ? "minecraft:air" : BlockStates.toString(state));
				out.writeBoolean(section.data != null);
				if (section.data != null) for (short v : section.data) out.writeShort(v);
			}
		}
	}

	private static int countEvents(BaseInsuranceClient mod, Base.EventType type) {
		return (int) mod.manager.get("Test").events.stream().filter(e -> e.type == type).count();
	}

	private static void showSettingsTab(MinecraftClient client, int tab) {
		if (client.currentScreen instanceof OptionsScreen options) options.showTab(tab);
	}

	private static void openChestAt(MinecraftClient client, BlockPos pos) {
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false);
		client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
	}

	private static void openChest(MinecraftClient client) {
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(chest).add(0, 0.5, 0), Direction.UP, chest, false);
		client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
	}

	private static void showTab(MinecraftClient client, String tab) {
		if (client.currentScreen instanceof ReportScreen report) report.showTab(tab);
	}

	static void server(MinecraftClient client, Consumer<ServerWorld> action) {
		IntegratedServer server = client.getServer();
		server.execute(() -> action.accept(server.getOverworld()));
	}

	static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "bi_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	static int passed, failed;

	/** Updating by itself, offline parts: the version in a file name, and the helper's swap (the repository is private, so no download). */
	private static void updaterChecks() {
		check("an update's version is read from its file name", "1.17.1".equals(UpdateCheck.versionOf("base-insurance-1.17.1+1.21.11.jar.update"))
				&& UpdateCheck.versionOf("naughty-or-nice-1.0.0+1.21.11.jar") == null && UpdateCheck.newer("1.10.0", "1.9.2"));
		try {
			java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bi-update-test");
			java.nio.file.Path old = dir.resolve("base-insurance-1.0.0+1.21.11.jar"), update = dir.resolve("base-insurance-1.1.0+1.21.11.jar.update"),
					jar = dir.resolve("base-insurance-1.1.0+1.21.11.jar");
			java.nio.file.Files.writeString(old, "old");
			java.nio.file.Files.writeString(update, "new");
			check("the helper swaps an update in", UpdateSwap.swap(old, update, jar, 2000) && !java.nio.file.Files.exists(old)
					&& !java.nio.file.Files.exists(update) && "new".equals(java.nio.file.Files.readString(jar)));
			check("a file that isn't the release is refused", !UpdateCheck.verify(jar, "00", BaseInsuranceClient.MOD_ID, "1.1.0"));
		} catch (Exception e) {
			check("updating by itself works (" + e + ")", false);
		}
	}

	static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	static void log(String message) {
		BaseInsuranceClient.LOGGER.info("[SELFTEST] {}", message);
	}
}
