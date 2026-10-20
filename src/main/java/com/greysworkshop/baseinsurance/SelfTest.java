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
			case 630 -> showTab(client, "BLOCKS");
			case 650 -> screenshot(client, "4_report_blocks");
			case 660 -> showTab(client, "CONTAINERS");
			case 680 -> screenshot(client, "5_report_containers");
			case 690 -> showTab(client, "REBUILD");
			case 710 -> screenshot(client, "6_report_rebuild");
			case 720 -> showTab(client, "TIMELINE");
			case 740 -> screenshot(client, "7_report_timeline");
			case 750 -> client.setScreen(new MapScreen());
			case 770 -> screenshot(client, "8_map");
			case 790 -> {
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
