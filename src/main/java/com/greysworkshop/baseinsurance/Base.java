package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One protected area: its bounds, what has changed since the snapshot, and its history. Saved as JSON. */
public final class Base {
	public String name;
	public String dimension;
	public int minX, minY, minZ, maxX, maxY, maxZ;
	public long created;
	/** Last time any part of the base was loaded and scanned. */
	public long lastSeen;

	/** Blocks that differ from the snapshot, by {@link BlockPos#asLong()}. */
	public Map<Long, Change> changes = new HashMap<>();
	/** History, oldest first. */
	public List<Event> events = new ArrayList<>();
	/** Approved contents of containers, by {@link BlockPos#asLong()} (double chests use their lower-coordinate half). */
	public Map<Long, ContainerSnapshot> containers = new HashMap<>();

	/** The block snapshot; stored in its own file. */
	public transient Baseline baseline = new Baseline();
	public transient boolean dirty;
	public transient boolean baselineDirty;
	/** Sections that were loaded during the last scan pass; anything else is "returning" when it loads. */
	public transient Set<Long> loadedSections = new HashSet<>();
	/** Section cursor for the round-robin scanner. */
	public transient int scanCursor;
	public transient List<Long> sectionList;

	// State for the report shown once a returning visit's first full pass is done.
	public transient boolean arriving;
	public transient long awaySince;
	public transient long arrivalStart;
	public transient int arrivalChanges;
	/** When this visit's arrival report went out; later findings in the same visit become a follow-up. */
	public transient long lastArrivalReport;
	public transient List<Change> followUp = new ArrayList<>();
	public transient long followUpDue;
	private transient long progressAt;
	private transient int[] progress;

	/**
	 * {trusted, total} blocks: how much of the base has been seen up close. Cached for a second, as it walks
	 * the whole snapshot.
	 */
	public int[] snapshotProgress() {
		long now = System.currentTimeMillis();
		if (progress != null && now - progressAt < 1000) return progress;
		int trusted = 0, total = 0;
		for (long key : sections()) {
			int sx = ChunkSectionPos.unpackX(key), sy = ChunkSectionPos.unpackY(key), sz = ChunkSectionPos.unpackZ(key);
			int x0 = Math.max(sx << 4, minX), x1 = Math.min((sx << 4) + 15, maxX);
			int y0 = Math.max(sy << 4, minY), y1 = Math.min((sy << 4) + 15, maxY);
			int z0 = Math.max(sz << 4, minZ), z1 = Math.min((sz << 4) + 15, maxZ);
			total += (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
			Baseline.Section section = baseline.sections.get(key);
			if (section == null || section.trusted.isEmpty()) continue;
			if (x0 == sx << 4 && x1 == x0 + 15 && y0 == sy << 4 && y1 == y0 + 15 && z0 == sz << 4 && z1 == z0 + 15) {
				trusted += section.trusted.cardinality();
				continue;
			}
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						if (section.isTrusted(Baseline.index(x, y, z))) trusted++;
					}
				}
			}
		}
		progress = new int[]{trusted, total};
		progressAt = now;
		return progress;
	}

	public int snapshotPercent() {
		int[] p = snapshotProgress();
		return p[1] == 0 ? 100 : (int) Math.min(100, Math.floor(100.0 * p[0] / p[1]));
	}

	public static final class Change {
		/** Block state from the snapshot, as a block-state string like {@code minecraft:chest[facing=north]}. */
		public String was;
		/** What's there now. */
		public String now;
		public long firstSeen;
		/** True if it changed while you were nearby, false if found on returning. */
		public boolean live;
		/** Other players in render distance when a live change was spotted. */
		public List<String> suspects;

		public transient BlockState wasState;
		public transient BlockState nowState;

		public Kind kind() {
			boolean wasAir = BlockStates.isAirString(was);
			boolean nowAir = BlockStates.isAirString(now);
			if (nowAir) return Kind.REMOVED;
			if (wasAir) return Kind.ADDED;
			return Kind.REPLACED;
		}

		public BlockState wasState() {
			if (wasState == null) wasState = BlockStates.parse(was);
			return wasState;
		}

		public BlockState nowState() {
			if (nowState == null) nowState = BlockStates.parse(now);
			return nowState;
		}
	}

	public enum Kind { REMOVED, ADDED, REPLACED }

	public static final class Event {
		public long time;
		public EventType type;
		public String text;

		public Event() {}

		public Event(EventType type, String text) {
			this.time = System.currentTimeMillis();
			this.type = type;
			this.text = text;
		}
	}

	public enum EventType { CREATED, AWAY_DAMAGE, LIVE_DAMAGE, ALL_CLEAR, CONTAINER, PLAYER, ACCEPTED }

	public static final class ContainerSnapshot {
		public String block;
		public String name = "";
		/** Item id to total count. */
		public Map<String, Integer> items = new LinkedHashMap<>();
		public long updated;
	}

	public boolean contains(BlockPos pos) {
		return contains(pos.getX(), pos.getY(), pos.getZ());
	}

	public boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	/** Distance from the point to the nearest edge of the base, 0 if inside. */
	public double distanceTo(double x, double y, double z) {
		double dx = Math.max(0, Math.max(minX - x, x - (maxX + 1)));
		double dy = Math.max(0, Math.max(minY - y, y - (maxY + 1)));
		double dz = Math.max(0, Math.max(minZ - z, z - (maxZ + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public long volume() {
		return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
	}

	public List<Long> sections() {
		if (sectionList == null) {
			sectionList = new ArrayList<>();
			for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
				for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
					for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
						sectionList.add(ChunkSectionPos.asLong(sx, sy, sz));
					}
				}
			}
		}
		return sectionList;
	}

	public void log(EventType type, String text) {
		events.add(new Event(type, text));
		while (events.size() > Math.max(50, BIConfig.INSTANCE.maxEvents)) events.remove(0);
		dirty = true;
	}

	public int count(Kind kind) {
		int n = 0;
		for (Change change : changes.values()) {
			if (change.kind() == kind) n++;
		}
		return n;
	}
}
