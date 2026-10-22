package com.greysworkshop.baseinsurance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Loads and saves the bases for the server or world you're currently in. */
public final class BaseManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private String worldKey;
	private final List<Base> bases = new ArrayList<>();

	/**
	 * Identifies the server (by address) or singleplayer world (by folder name). The address is tidied up, so
	 * "Constantiam.net", "constantiam.net:25565" and "constantiam.net." are the same server.
	 */
	public static String worldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) return "server:" + normalizeAddress(server.address);
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		return null;
	}

	/** The key as Hoard Eye (Hoard Eye then) spelled it before 3.0.0 (the address exactly as typed), for reading its data. */
	public static String rawWorldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) return "server:" + server.address;
		return worldKey(client);
	}

	static String normalizeAddress(String address) {
		String a = address.strip().toLowerCase(java.util.Locale.ROOT);
		if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
		while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
		return a;
	}

	/** A folder name as older versions spelled it, tidied up the same way as {@link #normalizeAddress}. */
	private static String normalizeFolder(String name) {
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (n.endsWith("_25565")) n = n.substring(0, n.length() - 6);
		while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
		return n;
	}

	/**
	 * Older versions kept a folder per address exactly as typed, so the same server could end up split across
	 * several. If this server's folder doesn't exist yet, the biggest older one is moved into place.
	 */
	static void migrateFolder(String key) {
		if (key == null || !key.startsWith("server:")) return;
		Path target = BIConfig.dir().resolve(sanitize(key));
		if (Files.exists(target) || !Files.isDirectory(BIConfig.dir())) return;
		Path best = null;
		long bestSize = -1;
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(BIConfig.dir(), Files::isDirectory)) {
			for (Path dir : dirs) {
				if (!normalizeFolder(dir.getFileName().toString()).equals(sanitize(key))) continue;
				long size;
				try (var walk = Files.walk(dir)) {
					size = walk.count();
				}
				if (size > bestSize) {
					best = dir;
					bestSize = size;
				}
			}
			if (best != null) {
				Files.move(best, target);
				BaseInsuranceClient.LOGGER.info("Moved {} to {} (same server, address spelled differently)", best, target);
			}
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not move older data for {}", key, e);
		}
	}

	public static String dimensionKey(ClientWorld world) {
		return world.getRegistryKey().getValue().toString();
	}

	public String worldKey() {
		return worldKey;
	}

	public List<Base> all() {
		return bases;
	}

	public List<Base> inDimension(String dimension) {
		List<Base> result = new ArrayList<>();
		for (Base base : bases) {
			if (base.dimension.equals(dimension)) result.add(base);
		}
		return result;
	}

	/** The smallest base in this dimension containing the position: the one that "owns" it. */
	public Base owner(String dimension, net.minecraft.util.math.BlockPos pos) {
		Base best = null;
		for (Base base : bases) {
			if (base.dimension.equals(dimension) && base.contains(pos) && (best == null || base.volume() < best.volume())) best = base;
		}
		return best;
	}

	public Base get(String name) {
		for (Base base : bases) {
			if (base.name.equalsIgnoreCase(name)) return base;
		}
		return null;
	}

	/** Switches to the given server/world, saving the previous one. */
	public void switchTo(String key) {
		if (key == null ? worldKey == null : key.equals(worldKey)) return;
		saveAll(true);
		bases.clear();
		worldKey = key;
		if (key == null) return;
		migrateFolder(key);
		Path dir = dir();
		if (!Files.isDirectory(dir)) return;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : files) {
				try (Reader reader = Files.newBufferedReader(file)) {
					Base base = GSON.fromJson(reader, Base.class);
					if (base == null || base.name == null) continue;
					fixNulls(base);
					base.baseline = Baseline.read(snapshotFile(base.name));
					if (base.baseline.loadedVersion < 2 && !base.changes.isEmpty()) {
						// Changes from before 1.4.0 were worked out against blocks seen from far away, which servers
						// often fake. Start the list afresh; the snapshot heals as you walk around.
						int cleared = base.changes.size();
						base.changes.clear();
						base.log(Base.EventType.ACCEPTED, "Upgraded to trusted snapshots: cleared " + Alerts.plural(cleared, "old change")
								+ " that came from blocks seen from far away");
						base.baselineDirty = true;
					}
					bases.add(base);
				} catch (IOException | RuntimeException e) {
					BaseInsuranceClient.LOGGER.error("Could not load base {}", file, e);
				}
			}
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.error("Could not list {}", dir, e);
		}
		bases.sort(Comparator.comparing(b -> b.name.toLowerCase(Locale.ROOT)));
	}

	private static void fixNulls(Base base) {
		if (base.changes == null) base.changes = new java.util.HashMap<>();
		if (base.events == null) base.events = new ArrayList<>();
		if (base.containers == null) base.containers = new java.util.HashMap<>();
		if (base.entities == null) base.entities = new java.util.HashMap<>();
		if (base.entityChanges == null) base.entityChanges = new java.util.HashMap<>();
		base.loadedSections = new java.util.HashSet<>();
		base.followUp = new ArrayList<>();
		base.baseline = new Baseline();
	}

	public void add(Base base) {
		bases.add(base);
		bases.sort(Comparator.comparing(b -> b.name.toLowerCase(Locale.ROOT)));
		base.dirty = true;
		base.baselineDirty = true;
		save(base, true);
	}

	public void delete(Base base) {
		bases.remove(base);
		try {
			Files.deleteIfExists(dataFile(base.name));
			Files.deleteIfExists(snapshotFile(base.name));
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.error("Could not delete files for base {}", base.name, e);
		}
	}

	/** Saves anything that changed. Snapshots are only written when {@code includeSnapshots} is set, as they're big. */
	public void saveAll(boolean includeSnapshots) {
		if (worldKey == null) return;
		for (Base base : bases) save(base, includeSnapshots);
	}

	private void save(Base base, boolean includeSnapshot) {
		try {
			Files.createDirectories(dir());
			if (base.dirty) {
				base.dirty = false;
				Path file = dataFile(base.name);
				Path temp = file.resolveSibling(file.getFileName() + ".tmp");
				try (Writer writer = Files.newBufferedWriter(temp)) {
					GSON.toJson(base, writer);
				}
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			if (includeSnapshot && base.baselineDirty) {
				base.baselineDirty = false;
				base.baseline.write(snapshotFile(base.name));
			}
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.error("Could not save base {}", base.name, e);
		}
	}

	private Path dir() {
		return BIConfig.dir().resolve(sanitize(worldKey));
	}

	private Path dataFile(String name) {
		return dir().resolve(sanitize(name) + ".json");
	}

	private Path snapshotFile(String name) {
		return dir().resolve(sanitize(name) + ".snapshot.gz");
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	public static boolean isValidName(String name) {
		return name.matches("[A-Za-z0-9_-]{1,32}");
	}
}
