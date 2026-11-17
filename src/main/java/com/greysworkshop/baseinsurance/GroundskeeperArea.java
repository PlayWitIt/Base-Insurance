package com.greysworkshop.baseinsurance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Groundskeeper's big area here, and how far it's got, read from the file it keeps
 * (config/groundskeeper/areas/&lt;world&gt;/&lt;dimension&gt;.json), so the map can show its tiles. Read-only: re-read
 * when the file changes, never written. Nothing to show without Groundskeeper.
 */
final class GroundskeeperArea {
	enum State {
		QUEUED, WORKED, DONE, SKIPPED
	}

	record Tile(int tx, int tz, State state, int coverage, String note) {}

	/** What's in the file: its tile size, tiles and shapes. */
	record Area(int tileChunks, List<Tile> tiles, List<AreaShapes.Shape> shapes) {
		int span() {
			return tileChunks * 16;
		}

		int count(State s) {
			int n = 0;
			for (Tile t : tiles) if (t.state == s) n++;
			return n;
		}

		Tile at(int x, int z) {
			int tx = Math.floorDiv(x, span()), tz = Math.floorDiv(z, span());
			for (Tile t : tiles) if (t.tx == tx && t.tz == tz) return t;
			return null;
		}
	}

	private static Path cachedFile;
	private static long cachedTime;
	private static Area cached;
	private static long checkedAt;

	private GroundskeeperArea() {}

	static boolean installed() {
		return FabricLoader.getInstance().isModLoaded("groundskeeper");
	}

	static String sanitize(String s) {
		return s.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	static Path file(MinecraftClient client) {
		String key = BaseManager.worldKey(client);
		if (key == null || client.world == null) return null;
		return FabricLoader.getInstance().getConfigDir().resolve("groundskeeper").resolve("areas").resolve(sanitize(key))
				.resolve(sanitize(BaseManager.dimensionKey(client.world)) + ".json");
	}

	/** Look at the file again on the next {@link #get}, whenever it was last looked at. */
	static void refresh() {
		checkedAt = 0;
	}

	/** The area here, or null. Looks at the file at most twice a second. */
	static Area get(MinecraftClient client) {
		Path f = file(client);
		if (f == null) return null;
		long now = System.currentTimeMillis();
		if (f.equals(cachedFile) && now - checkedAt < 500) return cached;
		checkedAt = now;
		try {
			if (!Files.exists(f)) {
				cachedFile = f;
				cached = null;
				return null;
			}
			long time = Files.getLastModifiedTime(f).toMillis();
			if (f.equals(cachedFile) && time == cachedTime) return cached;
			cachedFile = f;
			cachedTime = time;
			cached = parse(Files.readString(f));
		} catch (Exception e) {
			BaseInsuranceClient.LOGGER.warn("Couldn't read Groundskeeper's area {}: {}", f, e.toString());
			cached = null;
		}
		return cached;
	}

	static Area parse(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		int chunks = o.has("tileChunks") ? Math.max(1, Math.min(16, o.get("tileChunks").getAsInt())) : 4;
		List<Tile> tiles = new ArrayList<>();
		if (o.has("tiles") && o.get("tiles").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tiles").entrySet()) {
				String k = e.getKey();
				int c = k.indexOf(',');
				if (c < 0 || !e.getValue().isJsonObject()) continue;
				JsonObject t = e.getValue().getAsJsonObject();
				State state;
				try {
					state = State.valueOf(t.has("state") ? t.get("state").getAsString() : "QUEUED");
				} catch (IllegalArgumentException ex) {
					state = State.QUEUED;
				}
				tiles.add(new Tile(Integer.parseInt(k.substring(0, c).trim()), Integer.parseInt(k.substring(c + 1).trim()), state,
						t.has("coverage") ? t.get("coverage").getAsInt() : 0, t.has("note") ? t.get("note").getAsString() : ""));
			}
		}
		List<AreaShapes.Shape> shapes = new ArrayList<>();
		if (o.has("shapes") && o.get("shapes").isJsonArray()) {
			JsonObject wrap = new JsonObject();
			wrap.add("shapes", o.get("shapes"));
			shapes = AreaShapes.fromJson(wrap.toString());
		}
		return new Area(chunks, tiles, shapes);
	}

	/** Fill colours for a tile: mycelium purple when done, paler while it spreads, grey still to do, orange skipped. */
	static int fill(State s) {
		return switch (s) {
			case DONE -> 0x909A5FD0;
			case WORKED -> 0x70C49BE8;
			case QUEUED -> 0x20FFFFFF;
			case SKIPPED -> 0x70FF9A30;
		};
	}

	static int outline(State s) {
		return switch (s) {
			case DONE -> 0xFFB98AF0;
			case WORKED -> 0xFFD8BCF5;
			case QUEUED -> 0xA0FFFFFF;
			case SKIPPED -> 0xFFFF9A30;
		};
	}

	static String describe(Tile t) {
		return switch (t.state) {
			case DONE -> "done" + (t.note.isEmpty() ? "" : " (" + t.note + ")");
			case WORKED -> t.coverage + "% mycelium, spreading";
			case QUEUED -> "to do";
			case SKIPPED -> "skipped" + (t.note.isEmpty() ? "" : ": " + t.note);
		};
	}
}
