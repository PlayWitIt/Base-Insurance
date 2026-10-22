package com.greysworkshop.baseinsurance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads what Hoard Eye (the companion mod, called Hoard Eye before 3.0.0) remembers about container contents, if it's installed.
 * Used to seed a new base's container snapshots and to find rebuild materials in your other storage.
 */
public final class HoardEyeBridge {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("hoardeye.json");
	/** Where it kept them when it was called Hoard Eye (before 3.0.0). */
	private static final Path OLD_FILE = FabricLoader.getInstance().getConfigDir().resolve("containerpeek.json");

	private static JsonObject cached;
	private static FileTime cachedTime;

	private HoardEyeBridge() {}

	/** Known container contents in one dimension, by {@link BlockPos#asLong()}, as item id to count. */
	public static Map<Long, Map<String, Integer>> containers(String worldKey, String dimension) {
		Map<Long, Map<String, Integer>> result = new HashMap<>();
		JsonObject root = load();
		if (root == null) return result;
		// Hoard Eye keeps servers under the tidied-up address, as Base Insurance does.
		if (!root.has(worldKey) && worldKey.startsWith("server:")) worldKey = "server:" + BaseManager.normalizeAddress(worldKey.substring(7));
		if (!root.has(worldKey)) return result;
		JsonObject world = root.getAsJsonObject(worldKey);
		if (!world.has(dimension)) return result;
		for (Map.Entry<String, JsonElement> entry : world.getAsJsonObject(dimension).entrySet()) {
			try {
				long pos = Long.parseLong(entry.getKey());
				JsonElement value = entry.getValue();
				if (!value.isJsonObject() || !value.getAsJsonObject().has("items")) continue;
				JsonElement items = value.getAsJsonObject().get("items");
				if (!items.isJsonArray()) continue;
				Map<String, Integer> totals = new LinkedHashMap<>();
				for (JsonElement item : items.getAsJsonArray()) {
					JsonObject stack = item.getAsJsonObject();
					int count = stack.has("count") ? stack.get("count").getAsInt() : 1;
					totals.merge(stack.get("id").getAsString(), count, Integer::sum);
				}
				result.put(pos, totals);
			} catch (RuntimeException ignored) {
				// Skip anything we don't understand.
			}
		}
		return result;
	}

	private static JsonObject load() {
		try {
			Path file = Files.exists(FILE) ? FILE : OLD_FILE;
			if (!Files.exists(file)) return null;
			FileTime time = Files.getLastModifiedTime(file);
			if (cached != null && time.equals(cachedTime)) return cached;
			try (Reader reader = Files.newBufferedReader(file)) {
				cached = JsonParser.parseReader(reader).getAsJsonObject();
				cachedTime = time;
			}
			return cached;
		} catch (IOException | RuntimeException e) {
			BaseInsuranceClient.LOGGER.warn("Could not read Hoard Eye data", e);
			return null;
		}
	}
}
