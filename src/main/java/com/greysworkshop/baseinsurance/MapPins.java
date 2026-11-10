package com.greysworkshop.baseinsurance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Your own pins on the map, and where you died, kept per server in pins.json beside its bases and map. Deaths are
 * recorded by themselves (the last {@value #DEATHS_KEPT}), so you can always find your way back to your things.
 */
public final class MapPins {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static final int DEATHS_KEPT = 5;
	public static final int PIN = 0xFFFFC040, DEATH = 0xFFFF5050;

	public static final class Pin {
		public String name;
		public int x, z;
		public String dimension;
		/** True for a spot you died at. */
		public boolean death;
		public long time;

		Pin() {}

		Pin(String name, int x, int z, String dimension, boolean death) {
			this.name = name;
			this.x = x;
			this.z = z;
			this.dimension = dimension;
			this.death = death;
			this.time = System.currentTimeMillis();
		}

		public int color() {
			return death ? DEATH : PIN;
		}
	}

	private static String loadedFor;
	private static List<Pin> pins = new ArrayList<>();
	private static boolean wasDead;

	private MapPins() {}

	private static Path file(String worldKey) {
		return BIConfig.dir().resolve(worldKey.replaceAll("[^A-Za-z0-9._-]", "_")).resolve("pins.json");
	}

	/** The pins of the server you're on, read the first time they're wanted. */
	private static List<Pin> current() {
		String key = BaseManager.worldKey(MinecraftClient.getInstance());
		if (key == null) return List.of();
		if (!key.equals(loadedFor)) {
			loadedFor = key;
			pins = new ArrayList<>();
			Path file = file(key);
			if (Files.exists(file)) {
				try {
					List<Pin> read = GSON.fromJson(Files.readString(file), new TypeToken<List<Pin>>() {}.getType());
					if (read != null) for (Pin p : read) if (p != null && p.dimension != null) pins.add(p);
				} catch (IOException | RuntimeException e) {
					BaseInsuranceClient.LOGGER.warn("Could not read map pins {}", file, e);
				}
			}
		}
		return pins;
	}

	private static void save() {
		if (loadedFor == null) return;
		Path file = file(loadedFor);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(pins));
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not save map pins {}", file, e);
		}
	}

	public static List<Pin> in(String dimension) {
		List<Pin> out = new ArrayList<>();
		for (Pin p : current()) if (p.dimension.equals(dimension)) out.add(p);
		return out;
	}

	public static Pin add(String name, int x, int z, String dimension) {
		current();
		Pin pin = new Pin(name, x, z, dimension, false);
		pins.add(pin);
		save();
		return pin;
	}

	public static void remove(Pin pin) {
		current();
		if (pins.remove(pin)) save();
	}

	public static int count() {
		int n = 0;
		for (Pin p : current()) if (!p.death) n++;
		return n;
	}

	/** Every tick: when you die, where is pinned (only the last few deaths are kept). */
	public static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			wasDead = false;
			return;
		}
		boolean dead = client.player.isDead();
		if (dead && !wasDead) {
			current();
			String when = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ROOT));
			pins.add(new Pin("Died " + when, client.player.getBlockX(), client.player.getBlockZ(), BaseManager.dimensionKey(client.world), true));
			List<Pin> deaths = new ArrayList<>();
			for (Pin p : pins) if (p.death) deaths.add(p);
			for (int i = 0; i < deaths.size() - DEATHS_KEPT; i++) pins.remove(deaths.get(i));
			save();
		}
		wasDead = dead;
	}
}
