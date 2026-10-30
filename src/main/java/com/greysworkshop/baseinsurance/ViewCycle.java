package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * The map key cycles what's on screen: the full map, then the minimap, then Naughty or Nice's trail radar (if it's
 * installed), then nothing, then the map again. Closing the map with Esc ends the cycle, so the key opens the map next.
 */
public final class ViewCycle {
	private enum Step { REST, MAP, MINIMAP, RADAR, OFF }

	private static Step step = Step.REST;

	private ViewCycle() {}

	/** The map key, pressed with no screen open. */
	public static void pressed(MinecraftClient client) {
		switch (step) {
			case MINIMAP -> show(client, Radar.present() ? Step.RADAR : Step.OFF);
			case RADAR -> show(client, Step.OFF);
			default -> {
				step = Step.MAP;
				client.setScreen(new MapScreen());
			}
		}
	}

	/** The map key, pressed on the map: on to the minimap. */
	public static void pressedOnMap(MinecraftClient client) {
		client.setScreen(null);
		show(client, Step.MINIMAP);
	}

	/** The map was closed some other way (Esc, Done): the cycle starts over. */
	public static void mapClosed() {
		if (step == Step.MAP) step = Step.REST;
	}

	private static void show(MinecraftClient client, Step next) {
		step = next;
		boolean radar = next == Step.RADAR, minimap = next == Step.MINIMAP;
		Radar.set(radar);
		BIConfig.INSTANCE.minimap = minimap;
		BIConfig.save();
		String key = BaseInsuranceClient.INSTANCE.mapKeyName();
		String text = switch (next) {
			case MINIMAP -> "Minimap · " + key + (Radar.present() ? ": trail radar next" : ": hide it next");
			case RADAR -> "Trail radar · " + key + ": hide both next";
			case OFF -> "Map and radar hidden · " + key + ": map next";
			default -> "";
		};
		if (client.player != null) client.player.sendMessage(Text.literal(text), true);
		if (next == Step.OFF) step = Step.REST;
	}

	/** Naughty or Nice's trail radar, switched through its settings (it has no API; this mod doesn't need it). */
	static final class Radar {
		private static Boolean present;

		static boolean present() {
			if (present == null) present = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("naughtyornice") && config() != null;
			return present;
		}

		private static Object config() {
			try {
				return Class.forName("com.greysworkshop.naughtyornice.NNConfig").getField("INSTANCE").get(null);
			} catch (ReflectiveOperationException | LinkageError e) {
				return null;
			}
		}

		static void set(boolean on) {
			if (!present()) return;
			try {
				Object config = config();
				Class<?> type = config.getClass();
				type.getField("trailRadar").setBoolean(config, on);
				if (on) type.getField("chunkTrails").setBoolean(config, true);
				type.getMethod("save").invoke(null);
			} catch (ReflectiveOperationException | LinkageError e) {
				BaseInsuranceClient.LOGGER.warn("Couldn't switch Naughty or Nice's radar", e);
			}
		}
	}
}
