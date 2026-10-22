package com.greysworkshop.baseinsurance;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseInsuranceClient implements ClientModInitializer {
	public static final String MOD_ID = "baseinsurance";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Write changed snapshots to disk at most this often (ticks). */
	private static final int SAVE_INTERVAL = 600;

	public static BaseInsuranceClient INSTANCE;

	public final BaseManager manager = new BaseManager();
	public final Scanner scanner = new Scanner(manager);
	public final ContainerWatcher watcher = new ContainerWatcher(manager);
	public final WorldMap worldMap = new WorldMap();
	public final EntityWatch entityWatch = new EntityWatch(manager, scanner);

	private KeyBinding reportKey;
	private KeyBinding overlayKey;
	private KeyBinding buildKey;
	private KeyBinding mapKey;
	private KeyBinding minimapKey;
	private KeyBinding zoomInKey;
	private KeyBinding zoomOutKey;
	private Screen pendingScreen;
	private int saveTimer;
	private int welcomeTimer;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		BIConfig.load();
		ModsPanel.install(panelEntry());

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		reportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.report", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		overlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.overlay", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		buildKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.build", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.map", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
		minimapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.minimap", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		zoomInKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.minimap_in", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, category));
		zoomOutKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.minimap_out", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, category));

		// These run while the game handles a packet: an error escaping them would disconnect you, so none can.
		ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> safely("a chunk loading", () -> {
			worldMap.onChunkLoad(chunk.getPos());
			scanner.onChunkLoad(chunk.getPos());
		}));
		ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> safely("a chunk unloading", () -> worldMap.onChunkUnload(chunk.getPos())));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient() || !BIConfig.INSTANCE.enabled) return ActionResult.PASS;
			ClientWorld clientWorld = (ClientWorld) world;
			watcher.onUseBlock(clientWorld, hit.getBlockPos());
			// Right-clicks can place, strip, fill or light blocks: the clicked block and the one in front are yours.
			markAround(hit.getBlockPos());
			markAround(hit.getBlockPos().offset(hit.getSide()));
			return ActionResult.PASS;
		});

		// Hitting or using an item frame, armor stand or painting: its changes are yours.
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient() && EntityWatch.watched(entity)) entityWatch.markSelf(entity);
			return ActionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClient() && EntityWatch.watched(entity)) entityWatch.markSelf(entity);
			return ActionResult.PASS;
		});

		// Breaking a block can knock off what was attached to it (torches, doors), so neighbours count as yours too.
		ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> markAround(pos));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> watcher.onScreenOpened(screen));

		ClientTickEvents.END_CLIENT_TICK.register(client -> safely("ticking", () -> tick(client)));

		// Disconnect fires on the network thread; do the work on the game thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			manager.saveAll(true);
			scanner.reset();
			entityWatch.reset();
			manager.switchTo(null);
			worldMap.close();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			manager.saveAll(true);
			worldMap.save();
		});

		HudElementRegistry.addLast(Identifier.of(MOD_ID, "minimap"), Minimap::render);
		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), (context, tickCounter) -> Hud.render(context, tickCounter, manager, scanner));
		// After chat, so markers aren't hidden behind recent messages.
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(MOD_ID, "damage_locator"), DamageLocator::renderHud);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> Overlay.render(context, manager));

		// Scripted development test (./gradlew runSelfTest); the class isn't shipped in the release jar.
		if (Boolean.getBoolean("baseinsurance.selftest")) {
			try {
				Class.forName("com.greysworkshop.baseinsurance.SelfTest").getMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test not available", e);
			}
		}
		if (Boolean.getBoolean("baseinsurance.maptest")) {
			try {
				Class.forName("com.greysworkshop.baseinsurance.MapTest").getMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Map test not available", e);
			}
		}
	}

	public static void toggleMinimap(MinecraftClient client) {
		BIConfig.INSTANCE.minimap = !BIConfig.INSTANCE.minimap;
		BIConfig.save();
		if (client.player != null) client.player.sendMessage(Text.literal("Minimap " + (BIConfig.INSTANCE.minimap ? "ON" : "OFF")), true);
	}

	private static void zoomMinimap(MinecraftClient client, int steps) {
		if (!BIConfig.INSTANCE.minimap) return;
		double zoom = Minimap.zoom(steps);
		String text = zoom >= 1 ? (int) zoom + " px per block" : (int) Math.round(1 / zoom) + " blocks per px";
		if (client.player != null) client.player.sendMessage(Text.literal("Minimap zoom: " + text), true);
	}

	private void markAround(BlockPos pos) {
		scanner.markSelfEdit(pos);
		for (Direction direction : Direction.values()) scanner.markSelfEdit(pos.offset(direction));
	}

	/** Opens a screen on the next tick, so it isn't immediately closed by the chat screen a command came from. */
	public void openLater(Screen screen) {
		pendingScreen = screen;
	}

	/** The base you're in, or the nearest one in your dimension. */
	public Base currentBase() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) return null;
		Base best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Base base : manager.inDimension(BaseManager.dimensionKey(client.world))) {
			double distance = base.distanceTo(client.player.getX(), client.player.getY(), client.player.getZ());
			if (distance < bestDistance) {
				best = base;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Errors already logged, so each is logged once. */
	private final java.util.Set<String> logged = new java.util.HashSet<>();

	/** Runs a hook; an error in it is logged (once) and skipped, never passed on to the game. */
	private void safely(String what, Runnable work) {
		try {
			work.run();
		} catch (RuntimeException | LinkageError | StackOverflowError e) {
			if (logged.add(what + "|" + e)) LOGGER.error("Base Insurance caught an error in {} (skipped it, play goes on)", what, e);
		}
	}

	private void tick(MinecraftClient client) {
		UpdateCheck.tick(client);
		if (pendingScreen != null) {
			client.setScreen(pendingScreen);
			pendingScreen = null;
		}
		while (reportKey.wasPressed()) {
			Base base = currentBase();
			if (base != null) {
				client.setScreen(new ReportScreen(base));
			} else {
				Alerts.chat(Text.literal("No bases here yet. Create one with /bi create <name>.").formatted(Formatting.GRAY));
			}
		}
		while (overlayKey.wasPressed()) {
			Overlay.enabled = !Overlay.enabled;
			if (client.player != null) client.player.sendMessage(Text.literal("Damage overlay " + (Overlay.enabled ? "ON" : "OFF")), true);
		}
		while (mapKey.wasPressed()) {
			if (client.world != null) client.setScreen(new MapScreen());
		}
		while (minimapKey.wasPressed()) toggleMinimap(client);
		while (zoomInKey.wasPressed()) zoomMinimap(client, 1);
		while (zoomOutKey.wasPressed()) zoomMinimap(client, -1);
		while (buildKey.wasPressed()) {
			scanner.buildMode = !scanner.buildMode;
			if (client.player != null) client.player.sendMessage(Text.literal("Build mode " + (scanner.buildMode ? "ON" : "OFF")), true);
		}

		if (client.world == null || client.player == null) return;
		manager.switchTo(BaseManager.worldKey(client));
		if (!BIConfig.INSTANCE.enabled || manager.worldKey() == null) return;

		if (!BIConfig.INSTANCE.seenGuide && ++welcomeTimer == 200) {
			BIConfig.INSTANCE.seenGuide = true;
			BIConfig.save();
			Alerts.chat(Text.literal("New here? ").formatted(Formatting.GRAY).append(Text.literal("[Open the guide]").styled(s -> s
					.withColor(Formatting.AQUA).withUnderline(true).withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/bi guide"))))
					.append(Text.literal(" or type /bi guide.").formatted(Formatting.GRAY)));
		}
		scanner.tick(client);
		entityWatch.tick(client);
		watcher.tick(client);
		DamageLocator.tick(client);
		worldMap.tick(client);
		if (++saveTimer >= SAVE_INTERVAL) {
			saveTimer = 0;
			manager.saveAll(true);
		}
	}

	/** This mod's part of the mods panel (Right Shift on the title screen): the switches worth flipping before joining. */
	private static java.util.Map<String, Object> panelEntry() {
		BIConfig c = BIConfig.INSTANCE;
		String P = "baseinsurance.config";
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?").replaceAll("\\+.*", "");
		java.util.List<java.util.Map<String, Object>> switches = java.util.List.of(
				master(ModsPanel.toggle(Text.translatable(P + ".enabled"), Text.translatable(P + ".enabled.desc"), () -> c.enabled, v -> c.enabled = v)),
				ModsPanel.section("Alerts"),
				ModsPanel.toggle(Text.translatable(P + ".live_alerts"), Text.translatable(P + ".live_alerts.desc"), () -> c.liveAlerts, v -> c.liveAlerts = v),
				ModsPanel.toggle(Text.translatable(P + ".arrival_reports"), Text.translatable(P + ".arrival_reports.desc"), () -> c.arrivalReports, v -> c.arrivalReports = v),
				ModsPanel.toggle(Text.translatable(P + ".player_alerts"), Text.translatable(P + ".player_alerts.desc"), () -> c.playerAlerts, v -> c.playerAlerts = v),
				ModsPanel.toggle(Text.translatable(P + ".theft_alerts"), Text.translatable(P + ".theft_alerts.desc"), () -> c.theftAlerts, v -> c.theftAlerts = v),
				ModsPanel.toggle(Text.translatable(P + ".sounds"), Text.translatable(P + ".sounds.desc"), () -> c.sounds, v -> c.sounds = v),
				ModsPanel.section("Watching"),
				ModsPanel.toggle(Text.translatable(P + ".track_containers"), Text.translatable(P + ".track_containers.desc"), () -> c.trackContainers, v -> c.trackContainers = v),
				ModsPanel.toggle(Text.translatable(P + ".record_map"), Text.translatable(P + ".record_map.desc"), () -> c.recordMap, v -> c.recordMap = v),
				ModsPanel.section("On screen"),
				ModsPanel.toggle(Text.translatable(P + ".auto_overlay"), Text.translatable(P + ".auto_overlay.desc"), () -> c.autoOverlay, v -> c.autoOverlay = v),
				ModsPanel.toggle(Text.translatable(P + ".hud_status"), Text.translatable(P + ".hud_status.desc"), () -> c.hudStatus, v -> c.hudStatus = v),
				ModsPanel.toggle(Text.translatable(P + ".aim_info"), Text.translatable(P + ".aim_info.desc"), () -> c.aimInfo, v -> c.aimInfo = v),
				ModsPanel.toggle(Text.translatable(P + ".minimap"), Text.translatable(P + ".minimap.desc"), () -> c.minimap, v -> c.minimap = v),
				ModsPanel.section("Other"),
				ModsPanel.toggle(Text.translatable(P + ".check_updates"), Text.translatable(P + ".check_updates.desc"), () -> c.checkUpdates, v -> c.checkUpdates = v));
		return java.util.Map.of("id", MOD_ID, "name", "Base Insurance", "version", version, "icon", Identifier.of(MOD_ID, "icon.png"),
				"settings", (java.util.function.Function<net.minecraft.client.gui.screen.Screen, net.minecraft.client.gui.screen.Screen>) SettingsScreen::new,
				"save", (Runnable) BIConfig::save, "switches", switches);
	}

	private static java.util.Map<String, Object> master(java.util.Map<String, Object> toggle) {
		java.util.Map<String, Object> m = new java.util.HashMap<>(toggle);
		m.put("master", true);
		return m;
	}
}
