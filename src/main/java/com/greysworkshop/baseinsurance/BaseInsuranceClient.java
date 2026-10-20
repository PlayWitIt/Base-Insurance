package com.greysworkshop.baseinsurance;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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

	private KeyBinding reportKey;
	private KeyBinding overlayKey;
	private KeyBinding buildKey;
	private Screen pendingScreen;
	private int saveTimer;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		BIConfig.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		reportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.report", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		overlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.overlay", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		buildKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.baseinsurance.build", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

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

		// Breaking a block can knock off what was attached to it (torches, doors), so neighbours count as yours too.
		ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> markAround(pos));

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> watcher.onScreenOpened(screen));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			manager.saveAll(true);
			scanner.reset();
			manager.switchTo(null);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> manager.saveAll(true));

		HudElementRegistry.addLast(Identifier.of(MOD_ID, "hud"), (context, tickCounter) -> Hud.render(context, tickCounter, manager, scanner));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> Overlay.render(context, manager));

		// Scripted development test (./gradlew runSelfTest); the class isn't shipped in the release jar.
		if (Boolean.getBoolean("baseinsurance.selftest")) {
			try {
				Class.forName("com.greysworkshop.baseinsurance.SelfTest").getMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test not available", e);
			}
		}
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

	private void tick(MinecraftClient client) {
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
		while (buildKey.wasPressed()) {
			scanner.buildMode = !scanner.buildMode;
			if (client.player != null) client.player.sendMessage(Text.literal("Build mode " + (scanner.buildMode ? "ON" : "OFF")), true);
		}

		if (client.world == null || client.player == null) return;
		manager.switchTo(BaseManager.worldKey(client));
		if (!BIConfig.INSTANCE.enabled || manager.worldKey() == null) return;

		scanner.tick(client);
		watcher.tick(client);
		if (++saveTimer >= SAVE_INTERVAL) {
			saveTimer = 0;
			manager.saveAll(true);
		}
	}
}
