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
		tick++;
		BaseInsuranceClient mod = BaseInsuranceClient.INSTANCE;
		switch (tick) {
			case 100 -> {
				origin = client.player.getBlockPos();
				chest = origin.add(2, 0, 2);
				log("In world at " + Alerts.pos(origin) + "; creating base");
				client.player.networkHandler.sendChatCommand("bi delete Test confirm");
				client.player.networkHandler.sendChatCommand("bi create Test 10 3 6");
				client.player.networkHandler.sendChatCommand("bi create");
			}
			case 106 -> check("plain /bi create opens the map", client.currentScreen instanceof MapScreen);
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
				client.player.networkHandler.sendChatCommand("bi accept");
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
				check("Hide base snapshotted (" + hide.baseline.sections.size() + "/" + hide.sections().size() + ")",
						hide.baseline.sections.size() == hide.sections().size());
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
					w.setBlockState(origin.add(-12, 0, 12), Blocks.LAVA.getDefaultState());
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
				if (client.currentScreen instanceof ReportScreen report) report.testAction("Not damage");
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
				if (client.currentScreen instanceof ReportScreen report) report.testAction("Find");
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
				client.setScreen(null);
				Base base = mod.manager.get("Test");
				log("Final: " + base.changes.size() + " changes; events:");
				for (Base.Event event : base.events) log("  " + event.type + ": " + event.text);
				log("DONE");
				client.scheduleStop();
			}
			default -> {}
		}
	}

	/** Points the camera at the centre of a block. */
	private static void aimAt(MinecraftClient client, BlockPos target) {
		Vec3d eye = client.player.getEyePos();
		Vec3d d = Vec3d.ofCenter(target).subtract(eye);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		client.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		client.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, horizontal)));
	}

	private static int liveEventsBefore;

	private static int countEvents(BaseInsuranceClient mod, Base.EventType type) {
		return (int) mod.manager.get("Test").events.stream().filter(e -> e.type == type).count();
	}

	private static void showSettingsTab(MinecraftClient client, int tab) {
		if (client.currentScreen instanceof OptionsScreen options) options.showTab(tab);
	}

	private static void openChest(MinecraftClient client) {
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(chest).add(0, 0.5, 0), Direction.UP, chest, false);
		client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
	}

	private static void showTab(MinecraftClient client, String tab) {
		if (client.currentScreen instanceof ReportScreen report) report.showTab(tab);
	}

	private static void server(MinecraftClient client, Consumer<ServerWorld> action) {
		IntegratedServer server = client.getServer();
		server.execute(() -> action.accept(server.getOverworld()));
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "bi_" + name + ".png", client.getFramebuffer(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		BaseInsuranceClient.LOGGER.info("[SELFTEST] {}", message);
	}
}
