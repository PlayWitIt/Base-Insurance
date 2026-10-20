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
 * Reads what Container Peek (the companion mod) remembers about container contents, if it's installed.
 * Used to seed a new base's container snapshots and to find rebuild materials in your other storage.
 */
public final class ContainerPeekBridge {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("containerpeek.json");

	private static JsonObject cached;
	private static FileTime cachedTime;

	private ContainerPeekBridge() {}

	/** Known container contents in one dimension, by {@link BlockPos#asLong()}, as item id to count. */
	public static Map<Long, Map<String, Integer>> containers(String worldKey, String dimension) {
		Map<Long, Map<String, Integer>> result = new HashMap<>();
		JsonObject root = load();
		if (root == null || !root.has(worldKey)) return result;
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
			if (!Files.exists(FILE)) return null;
			FileTime time = Files.getLastModifiedTime(FILE);
			if (cached != null && time.equals(cachedTime)) return cached;
			try (Reader reader = Files.newBufferedReader(FILE)) {
				cached = JsonParser.parseReader(reader).getAsJsonObject();
				cachedTime = time;
			}
			return cached;
		} catch (IOException | RuntimeException e) {
			BaseInsuranceClient.LOGGER.warn("Could not read Container Peek data", e);
			return null;
		}
	}
}
