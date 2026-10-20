package com.greysworkshop.baseinsurance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** User settings, saved to config/baseinsurance/settings.json. */
public final class BIConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static BIConfig INSTANCE = new BIConfig();

	public boolean enabled = true;
	/** Play a sound with alerts. */
	public boolean sounds = true;
	/** Alert when blocks change while you're nearby. */
	public boolean liveAlerts = true;
	/** Alert when another player comes near a base. */
	public boolean playerAlerts = true;
	/** Say so when you return to a base and nothing changed. */
	public boolean allClearMessages = true;
	/** Fill damage boxes with colour, not just outlines. */
	public boolean overlayFill = true;
	/** Show the overlay automatically when you return to damage. */
	public boolean autoOverlay = true;
	public int overlayRange = 64;
	/** How close (in blocks) another player must get to a base to count as near it. */
	public int playerAlertDistance = 32;

	/** Players who never trigger alerts or count as suspects (case-insensitive). */
	public List<String> friends = new ArrayList<>();
	/** Extra block ids to ignore changes to, e.g. "minecraft:cobblestone". */
	public List<String> ignoredBlocks = new ArrayList<>();

	public boolean isFriend(String name) {
		for (String friend : friends) {
			if (friend.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("baseinsurance");
	}

	private static Path file() {
		return dir().resolve("settings.json");
	}

	public static void load() {
		if (!Files.exists(file())) return;
		try (Reader reader = Files.newBufferedReader(file())) {
			BIConfig loaded = GSON.fromJson(reader, BIConfig.class);
			if (loaded != null) {
				if (loaded.friends == null) loaded.friends = new ArrayList<>();
				if (loaded.ignoredBlocks == null) loaded.ignoredBlocks = new ArrayList<>();
				INSTANCE = loaded;
			}
		} catch (IOException | RuntimeException e) {
			BaseInsuranceClient.LOGGER.error("Could not read {}", file(), e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(dir());
			try (Writer writer = Files.newBufferedWriter(file())) {
				GSON.toJson(INSTANCE, writer);
			}
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.error("Could not write {}", file(), e);
		}
	}
}
