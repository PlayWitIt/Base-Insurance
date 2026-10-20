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

	/** Identifies the server (by address) or singleplayer world (by folder name), like Container Peek does. */
	public static String worldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) return "server:" + server.address;
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		return null;
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
		Path dir = dir();
		if (!Files.isDirectory(dir)) return;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : files) {
				try (Reader reader = Files.newBufferedReader(file)) {
					Base base = GSON.fromJson(reader, Base.class);
					if (base == null || base.name == null) continue;
					fixNulls(base);
					base.baseline = Baseline.read(snapshotFile(base.name));
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
		base.loadedSections = new java.util.HashSet<>();
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
