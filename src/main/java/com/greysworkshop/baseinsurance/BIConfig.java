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

/** User settings, saved to config/baseinsurance/settings.json. Missing fields keep their defaults. */
public final class BIConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static BIConfig INSTANCE = new BIConfig();

	public enum AlertStyle {
		CHAT, ACTION_BAR, BOTH, TOAST, TOAST_AND_CHAT;

		boolean chat() {
			return this == CHAT || this == BOTH || this == TOAST_AND_CHAT;
		}

		boolean actionBar() {
			return this == ACTION_BAR || this == BOTH;
		}

		boolean toast() {
			return this == TOAST || this == TOAST_AND_CHAT;
		}
	}

	public enum Corner { TOP_LEFT, TOP_RIGHT }

	public boolean enabled = true;

	// ---- Alerts ----
	/** Alert when blocks change while you're nearby. */
	public boolean liveAlerts = true;
	/** Alert (and record) changes you made yourself. Off: your own edits go straight into the snapshot. */
	public boolean countOwnChanges = false;
	/** Live changes this close to you are treated as yours (0 = off). */
	public int ignoreNearMe = 0;
	/** Fewer live changes than this are logged quietly instead of alerting. */
	public int liveAlertMinBlocks = 1;
	public int liveAlertCooldownSeconds = 10;
	/** Report what changed when you come back to a base. */
	public boolean arrivalReports = true;
	/** Say so when you come back and nothing changed. */
	public boolean allClearMessages = true;
	public int allClearMinAwayMinutes = 5;
	/** Alert when another player comes near a base. */
	public boolean playerAlerts = true;
	/** Only alert for players actually inside a base, not just near it. */
	public boolean playerAlertsInsideOnly = false;
	/** How close (in blocks) another player must get to a base to count as near it. */
	public int playerAlertDistance = 32;
	public int playerAlertCooldownMinutes = 5;
	/** Alert when a container is missing items. */
	public boolean theftAlerts = true;
	public AlertStyle alertStyle = AlertStyle.CHAT;
	public boolean sounds = true;
	public int soundVolume = 100;

	// ---- Detection ----
	/**
	 * Only blocks within this distance of you are snapshotted and compared. Servers often fake blocks further
	 * away (anti-xray, anti-freecam); up close they have to send the real ones.
	 */
	public int trustRadius = 32;
	/** How long a block you broke or placed counts as your own edit. */
	public int selfEditSeconds = 15;
	public boolean ignorePlants = true;
	public boolean ignoreWeather = true;
	public boolean ignoreFlowingFluids = true;
	public boolean ignoreOreSwaps = true;
	/** Recognise servers hiding underground terrain (sending air) and don't report it as changes. */
	public boolean smartTerrain = true;
	/** Ignore blocks completely surrounded by solid blocks (nobody can see them; anti-xray fakes them). */
	public boolean ignoreHidden = true;
	public boolean ignorePistons = true;
	public boolean trackContainers = true;
	/** Chunk sections compared per tick (4096 blocks each). */
	public int scanSpeed = 6;
	/** Turn build mode off automatically once you're well away from all bases. */
	public boolean buildModeAutoOff = true;

	// ---- Overlay & HUD ----
	/** Show the overlay automatically when you return to damage. */
	public boolean autoOverlay = true;
	public int overlayRange = 64;
	/** Fill damage boxes with colour, not just outlines. */
	public boolean overlayFill = true;
	public int overlayOpacity = 30;
	public int outlineWidth = 2;
	public boolean showRemoved = true;
	public boolean showAdded = true;
	public boolean showReplaced = true;
	/** "Missing: Obsidian" when you aim at a changed block. */
	public boolean aimInfo = true;
	/** The "Home: 12 blocks to fix" line. */
	public boolean hudStatus = true;
	public Corner hudCorner = Corner.TOP_LEFT;

	// ---- Map ----
	/** The explored map in a corner of the screen while you play. */
	public boolean minimap = true;
	/** Minimap width and height, in GUI pixels. */
	public int minimapSize = 112;
	/** Minimap screen pixels per block. */
	public double minimapZoom = 1;
	/** Turn the minimap so the way you face is always up (otherwise north is up). */
	public boolean minimapRotate = false;
	public Corner minimapCorner = Corner.TOP_RIGHT;
	public boolean minimapCoords = true;
	public boolean minimapPlayers = true;
	public boolean recordMap = true;
	public int mapSpeed = 6;
	public boolean mapDamageDots = true;
	public boolean mapGrid = true;
	/** Pins other mods put on the map, such as where Naughty or Nice last saw players. */
	public boolean mapPins = true;

	// ---- Friends & data ----
	/** Players who never trigger alerts or count as suspects (case-insensitive). */
	public List<String> friends = new ArrayList<>();
	/** Where friends came from, by lower-case name, when another mod added them ("Naughty or Nice · Close friends"). */
	public java.util.Map<String, String> friendNotes = new java.util.HashMap<>();
	/** Extra block ids to ignore changes to, e.g. "minecraft:cobblestone". */
	public List<String> ignoredBlocks = new ArrayList<>();
	public int maxEvents = 500;
	/** Whether the "new here? open the guide" message has been shown. */
	public boolean seenGuide = false;

	/** Notes where a friend came from (another mod), or clears it with null. Called by Naughty or Nice. */
	public static void noteFriend(String name, String note) {
		if (note == null) INSTANCE.friendNotes.remove(name.toLowerCase(java.util.Locale.ROOT));
		else INSTANCE.friendNotes.put(name.toLowerCase(java.util.Locale.ROOT), note);
	}

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
				if (loaded.friendNotes == null) loaded.friendNotes = new java.util.HashMap<>();
				if (loaded.ignoredBlocks == null) loaded.ignoredBlocks = new ArrayList<>();
				if (loaded.alertStyle == null) loaded.alertStyle = AlertStyle.CHAT;
				if (loaded.hudCorner == null) loaded.hudCorner = Corner.TOP_LEFT;
				if (loaded.minimapCorner == null) loaded.minimapCorner = Corner.TOP_RIGHT;
				if (!(loaded.minimapZoom >= Minimap.MIN_ZOOM && loaded.minimapZoom <= Minimap.MAX_ZOOM)) loaded.minimapZoom = 1;
				loaded.minimapSize = Math.max(64, Math.min(256, loaded.minimapSize));
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
