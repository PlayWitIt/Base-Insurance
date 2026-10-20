package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

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
	/** Item frames, armor stands and paintings as last seen up close, by {@link EntityWatch#key}. */
	public Map<String, EntityRecord> entities = new HashMap<>();
	/** Entities that differ from their record (removed, or contents changed), by key. */
	public Map<String, EntityChange> entityChanges = new HashMap<>();
	/** Whether entities were being watched on the last entity scan (so new changes are "live"). */
	public transient boolean entitiesWatched;

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
	private transient Map<Long, Float> coverage;

	/**
	 * {trusted, total} blocks: how much of the base has been seen up close. Cached for a second, as it walks
	 * the whole snapshot.
	 */
	public int[] snapshotProgress() {
		long now = System.currentTimeMillis();
		if (progress != null && now - progressAt < 1000) return progress;
		int trusted = 0, total = 0;
		Map<Long, int[]> columns = new HashMap<>();
		for (long key : sections()) {
			int sx = ChunkSectionPos.unpackX(key), sy = ChunkSectionPos.unpackY(key), sz = ChunkSectionPos.unpackZ(key);
			int x0 = Math.max(sx << 4, minX), x1 = Math.min((sx << 4) + 15, maxX);
			int y0 = Math.max(sy << 4, minY), y1 = Math.min((sy << 4) + 15, maxY);
			int z0 = Math.max(sz << 4, minZ), z1 = Math.min((sz << 4) + 15, maxZ);
			int volume = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
			int seen = 0;
			Baseline.Section section = baseline.sections.get(key);
			if (section != null && !section.trusted.isEmpty()) {
				if (x0 == sx << 4 && x1 == x0 + 15 && y0 == sy << 4 && y1 == y0 + 15 && z0 == sz << 4 && z1 == z0 + 15) {
					seen = section.trusted.cardinality();
				} else {
					for (int y = y0; y <= y1; y++) {
						for (int z = z0; z <= z1; z++) {
							for (int x = x0; x <= x1; x++) {
								if (section.isTrusted(Baseline.index(x, y, z))) seen++;
							}
						}
					}
				}
			}
			total += volume;
			trusted += seen;
			int[] column = columns.computeIfAbsent(ChunkPos.toLong(sx, sz), k -> new int[2]);
			column[0] += seen;
			column[1] += volume;
		}
		progress = new int[]{trusted, total};
		coverage = new HashMap<>();
		for (Map.Entry<Long, int[]> e : columns.entrySet()) coverage.put(e.getKey(), e.getValue()[0] / (float) e.getValue()[1]);
		progressAt = now;
		return progress;
	}

	/** Fraction of each 16×16 column (by ChunkPos long) seen up close. */
	public Map<Long, Float> coverage() {
		snapshotProgress();
		return coverage;
	}

	/** The middle of the nearest column that's mostly unseen, or null if it's all been seen. */
	public Vec3d nearestUnseen(double px, double pz) {
		Vec3d best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<Long, Float> e : coverage().entrySet()) {
			if (e.getValue() >= 0.9f) continue;
			int cx = ChunkPos.getPackedX(e.getKey()), cz = ChunkPos.getPackedZ(e.getKey());
			double x = MathHelper.clamp((cx << 4) + 8, minX, maxX + 1), z = MathHelper.clamp((cz << 4) + 8, minZ, maxZ + 1);
			double d = (x - px) * (x - px) + (z - pz) * (z - pz);
			if (d < bestDistance) {
				bestDistance = d;
				best = new Vec3d(x, 0, z);
			}
		}
		return best;
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
		/** Mobs and explosives in view when a live change was spotted, e.g. "Creeper (5m)". */
		public List<String> causes;

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

	/** An item frame, armor stand or painting as last seen. */
	public static final class EntityRecord {
		/** Entity type id, e.g. "minecraft:item_frame". */
		public String type;
		/** Display name, e.g. "Item Frame". */
		public String name;
		public int x, y, z;
		/** Item frames: the item id inside ("" if empty), its map id (-1 if not a map) and rotation. */
		public String item = "";
		public int mapId = -1;
		public int rotation;
		/** Armor stands: equipment item ids, head to off hand. */
		public String equipment = "";
		/** Paintings: the variant id. */
		public String variant = "";

		/** Everything that counts as "the same": compared to spot changes. */
		public String detail() {
			return item + "#" + mapId + "@" + rotation + "|" + equipment + "|" + variant;
		}

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		/** What's in or on it, for messages: "Filled Map #12", "Diamond", "empty". */
		public String contents() {
			if (!variant.isEmpty()) return variant.replace("minecraft:", "").replace('_', ' ');
			if (!equipment.isEmpty()) {
				long pieces = java.util.Arrays.stream(equipment.split(",")).filter(s -> !s.isEmpty()).count();
				return pieces == 0 ? "no armor" : pieces + " piece" + (pieces == 1 ? "" : "s") + " of gear";
			}
			if (item.isEmpty()) return "empty";
			return ContainerWatcher.itemName(item) + (mapId >= 0 ? " #" + mapId : "");
		}
	}

	/** An entity that's gone or changed since it was recorded. */
	public static final class EntityChange {
		public EntityRecord was;
		/** What's there now, or null if it's gone. */
		public EntityRecord now;
		public long firstSeen;
		public boolean live;
		public List<String> suspects;

		public String describe() {
			if (now == null) return was.name + " removed (had " + was.contents() + ")";
			if (!was.item.equals(now.item) || was.mapId != now.mapId) {
				return now.item.isEmpty() ? was.name + " emptied (" + was.contents() + " taken)"
						: was.name + ": " + was.contents() + " → " + now.contents();
			}
			if (was.rotation != now.rotation) return was.name + " rotated";
			if (!was.equipment.equals(now.equipment)) return was.name + ": " + was.contents() + " → " + now.contents();
			return was.name + " changed";
		}
	}

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

	/** Changed blocks plus changed item frames, armor stands and paintings. */
	public int damageCount() {
		return changes.size() + entityChanges.size();
	}

	public int count(Kind kind) {
		int n = 0;
		for (Change change : changes.values()) {
			if (change.kind() == kind) n++;
		}
		return n;
	}
}
