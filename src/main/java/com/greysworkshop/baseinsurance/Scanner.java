package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks through every base's chunk sections a few per tick, comparing the world to the snapshot.
 * <ul>
 *   <li>Sections seen for the first time become part of the snapshot.</li>
 *   <li>Differences you caused (blocks you just broke or placed, or anything in build mode) update the snapshot.</li>
 *   <li>Other differences are recorded as changes: "away" if found as the area loads in, "live" if it was
 *       already loaded, in which case nearby players are noted as suspects.</li>
 * </ul>
 */
public final class Scanner {
	/** Upper bound on sections looked at per tick, loaded or not. */
	private static final int MAX_VISITS_PER_TICK = 4000;
	/** For this long after you touch a block, its current state is copied straight into the snapshot. */
	private static final int APPLY_TICKS = 60;
	/** A freshly (re)loaded chunk isn't trusted until it has been loaded this long; servers may fill it in stages. */
	private static final int SETTLE_TICKS = 40;
	/** Build mode turns itself off once you're this far from every base (if enabled). */
	private static final int BUILD_MODE_RANGE = 64;

	private final BaseManager manager;
	private final Map<Long, Integer> selfEdits = new HashMap<>();
	/** When each chunk was last (re)loaded, by ChunkPos long. */
	private final Map<Long, Integer> chunkLoaded = new HashMap<>();
	/** Blocks you just touched, and the tick you touched them; applied to the snapshot directly. */
	private final Map<Long, Integer> pendingApply = new HashMap<>();
	private final Map<Base, List<Base.Change>> pendingLive = new HashMap<>();
	private final Map<Base, Long> lastLiveAlert = new HashMap<>();
	/** Sections that look hidden by the server, and the tick that started. */
	private final Map<Long, Integer> hiddenSince = new HashMap<>();
	private final Map<String, Long> lastPlayerAlert = new HashMap<>();
	private int tick;
	private int baseIndex;
	private List<String> suspectsThisTick;

	public boolean buildMode;

	public Scanner(BaseManager manager) {
		this.manager = manager;
	}

	/** Marks a block you broke or placed, so the change goes into the snapshot instead of the report. */
	public void markSelfEdit(BlockPos pos) {
		if (BIConfig.INSTANCE.countOwnChanges) return;
		selfEdits.put(pos.asLong(), tick + BIConfig.INSTANCE.selfEditSeconds * 20);
		pendingApply.put(pos.asLong(), tick);
	}

	public void onChunkLoad(net.minecraft.util.math.ChunkPos pos) {
		chunkLoaded.put(pos.toLong(), tick);
	}

	private boolean settled(int cx, int cz) {
		Integer loaded = chunkLoaded.get(net.minecraft.util.math.ChunkPos.toLong(cx, cz));
		return loaded == null || tick - loaded >= SETTLE_TICKS;
	}

	public void reset() {
		chunkLoaded.clear();
		hiddenSince.clear();
		selfEdits.clear();
		pendingApply.clear();
		pendingLive.clear();
		for (Base base : manager.all()) {
			base.loadedSections.clear();
			base.arriving = false;
		}
	}

	public void tick(MinecraftClient client) {
		tick++;
		suspectsThisTick = null;
		causesThisTick = null;
		if (tick % 20 == 0) selfEdits.values().removeIf(expiry -> expiry < tick);

		ClientWorld world = client.world;
		String dimension = BaseManager.dimensionKey(world);
		List<Base> active = new ArrayList<>();
		for (Base base : manager.all()) {
			if (base.dimension.equals(dimension)) {
				active.add(base);
			} else {
				// You're in another dimension, so everything there counts as "away".
				base.loadedSections.clear();
			}
		}
		if (active.isEmpty() || client.player == null) return;

		applySelfEdits(world, active);
		if (buildMode && BIConfig.INSTANCE.buildModeAutoOff && client.player != null) autoBuildModeOff(client, active);

		int budget = Math.max(1, BIConfig.INSTANCE.scanSpeed);
		for (int visits = 0; budget > 0 && visits < MAX_VISITS_PER_TICK; visits++) {
			Base base = active.get(Math.floorMod(baseIndex, active.size()));
			List<Long> sections = base.sections();
			if (base.scanCursor >= sections.size()) base.scanCursor = 0;
			if (scanSection(client, world, base, sections.get(base.scanCursor))) budget--;
			if (++base.scanCursor >= sections.size()) {
				base.scanCursor = 0;
				finishPass(base);
				baseIndex++;
			}
		}

		flushLiveAlerts();
		flushFollowUps(active);
		if (tick % 20 == 0) watchPlayers(client, active);
	}

	/**
	 * Compares one section; returns false if it was skipped for free (not loaded, or out of trust range).
	 * Only blocks within the trust radius of you are looked at: that's where servers send the real thing.
	 */
	private boolean scanSection(MinecraftClient client, ClientWorld world, Base base, long key) {
		int sx = ChunkSectionPos.unpackX(key);
		int sy = ChunkSectionPos.unpackY(key);
		int sz = ChunkSectionPos.unpackZ(key);
		int x0 = Math.max(sx << 4, base.minX), x1 = Math.min((sx << 4) + 15, base.maxX);
		int y0 = Math.max(sy << 4, base.minY), y1 = Math.min((sy << 4) + 15, base.maxY);
		int z0 = Math.max(sz << 4, base.minZ), z1 = Math.min((sz << 4) + 15, base.maxZ);
		Vec3d eye = client.player.getEyePos();
		double radius = BIConfig.INSTANCE.trustRadius;
		if (!world.isChunkLoaded(sx, sz) || !settled(sx, sz) || boxDistanceSq(eye, x0, y0, z0, x1, y1, z1) > radius * radius) {
			// Not loaded, only just (re)loaded, or too far to trust: leave it. Coming back counts as "returning".
			base.loadedSections.remove(key);
			hiddenSince.remove(key);
			return false;
		}

		Baseline.Section section = base.baseline.sections.get(key);
		if (section == null) {
			// First sight: take it as-is. The blocks are untrusted until the loop below sees them up close.
			section = Baseline.capture(world, key);
			base.baseline.sections.put(key, section);
			base.baselineDirty = true;
		}

		if (BIConfig.INSTANCE.smartTerrain) {
			switch (checkSection(world, section, eye, radius, x0, y0, z0, x1, y1, z1)) {
				case RECAPTURE -> {
					// The snapshot caught this section while the server was hiding it: take the real blocks.
					retrust(world, base, section, eye, radius, x0, y0, z0, x1, y1, z1);
					base.loadedSections.add(key);
					hiddenSince.remove(key);
					return true;
				}
				case HIDDEN -> {
					// Much of this section's terrain just turned to air: the server is hiding it. Wait, unless
					// you've been standing right next to it for a while, in which case it's real (say, TNT).
					int since = hiddenSince.computeIfAbsent(key, k -> tick);
					boolean close = boxDistanceSq(eye, x0, y0, z0, x1, y1, z1) <= HIDDEN_CONFIRM_DISTANCE * HIDDEN_CONFIRM_DISTANCE;
					if (!close || tick - since < HIDDEN_CONFIRM_TICKS) {
						base.loadedSections.remove(key);
						return true;
					}
				}
				case OK -> hiddenSince.remove(key);
			}
		}

		long now = System.currentTimeMillis();
		boolean wasLoaded = base.loadedSections.contains(key);
		if (!wasLoaded && !base.arriving) {
			base.arriving = true;
			base.awaySince = base.lastSeen;
			base.arrivalStart = now;
			base.arrivalChanges = 0;
		}

		double radiusSq = radius * radius;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
					if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
					int index = Baseline.index(x, y, z);
					BlockState was = section.get(index);
					if (was == null) continue;
					BlockState current = world.getBlockState(pos.set(x, y, z));
					long posKey = pos.asLong();
					Base.Change existing = base.changes.get(posKey);

					if (!section.isTrusted(index)) {
						// First close look at this block: whatever is here now is the truth.
						if (was != current) section.set(index, current);
						section.trust(index);
						base.baselineDirty = true;
						if (existing != null) {
							base.changes.remove(posKey);
							base.dirty = true;
						}
						continue;
					}

					if (ChangeFilter.same(was, current) || (BIConfig.INSTANCE.ignoreHidden && hidden(world, pos))) {
						// Unchanged, or repaired.
						if (existing != null) {
							base.changes.remove(posKey);
							base.dirty = true;
						}
						continue;
					}

					boolean nearMe = wasLoaded && BIConfig.INSTANCE.ignoreNearMe > 0
							&& dx * dx + dy * dy + dz * dz <= BIConfig.INSTANCE.ignoreNearMe * BIConfig.INSTANCE.ignoreNearMe;
					// Natural terrain where the snapshot had air is the real world arriving, not someone building.
					boolean terrainArrived = BIConfig.INSTANCE.smartTerrain && was.isAir() && ChangeFilter.isTerrain(current, false);
					if (buildMode || nearMe || terrainArrived || selfEdits.containsKey(posKey)) {
						section.set(index, current);
						base.baselineDirty = true;
						if (existing != null) base.changes.remove(posKey);
						if (base.containers.remove(posKey) != null || existing != null) base.dirty = true;
						continue;
					}

					if (existing != null) {
						if (existing.nowState != current) {
							existing.now = BlockStates.toString(current);
							existing.nowState = current;
							base.dirty = true;
						}
						continue;
					}

					Base.Change change = new Base.Change();
					change.was = BlockStates.toString(was);
					change.now = BlockStates.toString(current);
					change.wasState = was;
					change.nowState = current;
					change.firstSeen = now;
					change.live = wasLoaded;
					// Where bases overlap, only the smallest one announces it; the others just record it.
					boolean announce = manager.owner(base.dimension, pos) == base;
					if (wasLoaded) {
						change.suspects = suspects(client);
						change.causes = causes(client);
						if (announce) pendingLive.computeIfAbsent(base, b -> new ArrayList<>()).add(change);
					} else if (announce) {
						base.arrivalChanges++;
					}
					base.changes.put(posKey, change);
					base.dirty = true;
				}
			}
		}
		base.loadedSections.add(key);
		return true;
	}

	private static double boxDistanceSq(Vec3d p, int x0, int y0, int z0, int x1, int y1, int z1) {
		double dx = Math.max(0, Math.max(x0 - p.x, p.x - (x1 + 1)));
		double dy = Math.max(0, Math.max(y0 - p.y, p.y - (y1 + 1)));
		double dz = Math.max(0, Math.max(z0 - p.z, p.z - (z1 + 1)));
		return dx * dx + dy * dy + dz * dz;
	}

	private enum SectionCheck { OK, RECAPTURE, HIDDEN }

	/** A section that looks hidden is accepted as really changed after you've been this close for this long. */
	private static final double HIDDEN_CONFIRM_DISTANCE = 12;
	private static final int HIDDEN_CONFIRM_TICKS = 200;

	/**
	 * Spots servers that hide terrain by sending air. Among trusted, in-range blocks: if much of what was air in
	 * the snapshot is now natural terrain, the snapshot was taken while hidden; if much of what was solid is now
	 * air, the server is hiding it right now.
	 */
	private static SectionCheck checkSection(ClientWorld world, Baseline.Section section, Vec3d eye, double radius,
			int x0, int y0, int z0, int x1, int y1, int z1) {
		int total = 0, appeared = 0, solidBefore = 0, vanished = 0;
		double radiusSq = radius * radius;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
					if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
					int index = Baseline.index(x, y, z);
					if (!section.isTrusted(index)) continue;
					BlockState was = section.get(index);
					if (was == null) continue;
					total++;
					BlockState current = world.getBlockState(pos.set(x, y, z));
					if (was.isAir()) {
						if (!current.isAir() && ChangeFilter.isTerrain(current, true)) appeared++;
					} else {
						solidBefore++;
						if (current.isAir()) vanished++;
					}
				}
			}
		}
		if (total > 0 && appeared >= 64 && appeared * 4 >= total) return SectionCheck.RECAPTURE;
		if (solidBefore >= 64 && vanished * 2 >= solidBefore) return SectionCheck.HIDDEN;
		return SectionCheck.OK;
	}

	/** Takes the current blocks as the truth for every in-range block of a section, and clears their changes. */
	private static void retrust(ClientWorld world, Base base, Baseline.Section section, Vec3d eye, double radius,
			int x0, int y0, int z0, int x1, int y1, int z1) {
		double radiusSq = radius * radius;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
					if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
					int index = Baseline.index(x, y, z);
					section.set(index, world.getBlockState(pos.set(x, y, z)));
					section.trust(index);
					if (base.changes.remove(pos.asLong()) != null) base.dirty = true;
				}
			}
		}
		base.baselineDirty = true;
	}

	/**
	 * True if every neighbour is a solid, opaque block, so nobody can see this block. Anti-xray plugins swap and
	 * fake exactly these blocks, so their "changes" are noise. (Anything you dig into is exposed, so it still counts.)
	 */
	private static boolean hidden(ClientWorld world, BlockPos.Mutable pos) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		BlockPos.Mutable n = new BlockPos.Mutable();
		boolean result = world.getBlockState(n.set(x + 1, y, z)).isOpaqueFullCube()
				&& world.getBlockState(n.set(x - 1, y, z)).isOpaqueFullCube()
				&& world.getBlockState(n.set(x, y + 1, z)).isOpaqueFullCube()
				&& world.getBlockState(n.set(x, y - 1, z)).isOpaqueFullCube()
				&& world.getBlockState(n.set(x, y, z + 1)).isOpaqueFullCube()
				&& world.getBlockState(n.set(x, y, z - 1)).isOpaqueFullCube();
		pos.set(x, y, z);
		return result;
	}

	/** Arrival findings within this long of the last report are added to a follow-up instead of a new report. */
	private static final long VISIT_WINDOW = 120_000;
	/** A follow-up goes out once nothing new has been found for this long. */
	private static final long FOLLOW_UP_QUIET = 15_000;

	private void finishPass(Base base) {
		long now = System.currentTimeMillis();
		if (base.loadedSections.isEmpty()) return;
		// Saving lastSeen every second would rewrite the file constantly; once a minute is plenty.
		if (now - base.lastSeen > 60_000) base.dirty = true;
		long previousSeen = base.lastSeen;
		base.lastSeen = now;

		if (!base.arriving) return;
		base.arriving = false;
		long since = base.awaySince > 0 ? base.awaySince : base.created;

		if (base.arrivalChanges > 0) {
			List<Base.Change> found = new ArrayList<>();
			for (Base.Change change : base.changes.values()) {
				if (!change.live && change.firstSeen >= base.arrivalStart) found.add(change);
			}
			if (found.isEmpty()) return;
			if (now - base.lastArrivalReport < VISIT_WINDOW) {
				// Still the same visit: fold these into one follow-up rather than another big report.
				base.followUp.addAll(found);
				base.followUpDue = now + FOLLOW_UP_QUIET;
				base.lastArrivalReport = now;
				return;
			}
			base.lastArrivalReport = now;
			reportArrival(base, found, since);
		} else if (now - since >= BIConfig.INSTANCE.allClearMinAwayMinutes * 60_000L && base.changes.isEmpty()
				&& now - previousSeen > 5_000) {
			if (BIConfig.INSTANCE.allClearMessages) {
				Alerts.alert(Text.literal("✔ " + base.name + " looks untouched since " + Alerts.time(since) + ".").formatted(Formatting.GREEN), false);
			}
			base.log(Base.EventType.ALL_CLEAR, "Checked on return: untouched since " + Alerts.time(since));
		}
	}

	private void reportArrival(Base base, List<Base.Change> found, long since) {
		int destroyedContainers = 0;
		for (Map.Entry<Long, Base.Change> entry : base.changes.entrySet()) {
			if (found.contains(entry.getValue()) && base.containers.containsKey(entry.getKey())
					&& entry.getValue().kind() != Base.Kind.ADDED) destroyedContainers++;
		}
		String summary = Alerts.plural(found.size(), "block") + " changed: " + Alerts.summarize(found);
		if (destroyedContainers > 0) summary += ". " + Alerts.plural(destroyedContainers, "container") + " destroyed";
		// If you were at the base a moment ago, these were just found on a part you hadn't looked at yet.
		boolean away = System.currentTimeMillis() - since > 60_000;
		base.log(Base.EventType.AWAY_DAMAGE, (away ? "While you were away (since " + Alerts.time(since) + "): " : "Found: ") + summary);
		if (BIConfig.INSTANCE.arrivalReports) {
			String headline = away ? "⚠ " + base.name + " changed while you were away! " : "⚠ Changes found in " + base.name + ": ";
			Alerts.alert(Text.empty()
					.append(Text.literal(headline).formatted(Formatting.RED, Formatting.BOLD))
					.append(Text.literal(summary + ". ").formatted(Formatting.YELLOW))
					.append(Alerts.reportLink(base)), true);
			if (BIConfig.INSTANCE.autoOverlay) Overlay.enabled = true;
		}
	}

	/** Sends a visit's follow-up once things have gone quiet. */
	private void flushFollowUps(List<Base> active) {
		long now = System.currentTimeMillis();
		for (Base base : active) {
			if (base.followUp.isEmpty() || now < base.followUpDue) continue;
			List<Base.Change> found = new ArrayList<>(base.followUp);
			base.followUp.clear();
			// Some may have been repaired or turned out to be fake in the meantime.
			found.removeIf(change -> !base.changes.containsValue(change));
			if (found.isEmpty()) continue;
			String summary = found.size() + " more: " + Alerts.summarize(found);
			base.log(Base.EventType.AWAY_DAMAGE, "Found while looking around: " + summary);
			if (BIConfig.INSTANCE.arrivalReports) {
				Alerts.alert(Text.empty()
						.append(Text.literal("⚠ " + base.name + ": ").formatted(Formatting.RED))
						.append(Text.literal(summary + ". ").formatted(Formatting.YELLOW))
						.append(Alerts.reportLink(base)), false);
			}
		}
	}

	private void flushLiveAlerts() {
		if (pendingLive.isEmpty()) return;
		long now = System.currentTimeMillis();
		var it = pendingLive.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Base base = entry.getKey();
			if (now - lastLiveAlert.getOrDefault(base, 0L) < BIConfig.INSTANCE.liveAlertCooldownSeconds * 1000L) continue;
			List<Base.Change> changes = entry.getValue();
			it.remove();
			lastLiveAlert.put(base, now);

			Base.Change last = changes.get(changes.size() - 1);
			List<String> suspects = last.suspects, causes = last.causes;
			String who;
			if (suspects != null && !suspects.isEmpty()) {
				who = "nearby: " + String.join(", ", suspects);
			} else if (causes != null && !causes.isEmpty()) {
				who = "no players in sight; nearby: " + String.join(", ", causes);
			} else {
				who = "no players or mobs in sight (someone out of view, or something you did)";
			}
			String text = Alerts.plural(changes.size(), "block") + " changed: " + Alerts.summarize(changes) + " — " + who;
			base.log(Base.EventType.LIVE_DAMAGE, text);
			// Small batches below the threshold are only logged, not announced.
			if (BIConfig.INSTANCE.liveAlerts && changes.size() >= BIConfig.INSTANCE.liveAlertMinBlocks) {
				Alerts.alert(Text.empty()
						.append(Text.literal("⚠ " + base.name + ": ").formatted(Formatting.RED, Formatting.BOLD))
						.append(Text.literal(text).formatted(Formatting.YELLOW)), true);
			}
		}
	}

	/** Mobs and explosives that break or move blocks. */
	private static boolean isCause(Entity entity) {
		return entity instanceof CreeperEntity || entity instanceof EndermanEntity || entity instanceof WitherEntity
				|| entity instanceof GhastEntity || entity instanceof RavagerEntity || entity instanceof TntEntity
				|| entity instanceof EndCrystalEntity || entity instanceof WitherSkullEntity || entity instanceof FireballEntity;
	}

	private List<String> causesThisTick;

	/** Block-breaking mobs and explosives within 48 blocks, nearest first, e.g. "Creeper (5m)". */
	private List<String> causes(MinecraftClient client) {
		if (causesThisTick != null) return causesThisTick;
		List<Entity> found = client.world.getOtherEntities(client.player, client.player.getBoundingBox().expand(48), Scanner::isCause);
		found.sort((a, b) -> Double.compare(a.squaredDistanceTo(client.player), b.squaredDistanceTo(client.player)));
		List<String> result = new ArrayList<>();
		for (Entity entity : found.subList(0, Math.min(4, found.size()))) {
			result.add(entity.getName().getString() + " (" + (int) entity.distanceTo(client.player) + "m)");
		}
		causesThisTick = result;
		return result;
	}

	/** Other players in render distance, nearest first, e.g. "Steve (12m)". Friends are left out. */
	private List<String> suspects(MinecraftClient client) {
		if (suspectsThisTick != null) return suspectsThisTick;
		List<PlayerEntity> players = new ArrayList<>();
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player == client.player || BIConfig.INSTANCE.isFriend(player.getName().getString())) continue;
			players.add(player);
		}
		players.sort((a, b) -> Double.compare(a.squaredDistanceTo(client.player), b.squaredDistanceTo(client.player)));
		List<String> result = new ArrayList<>();
		for (PlayerEntity player : players) {
			result.add(player.getName().getString() + " (" + (int) player.distanceTo(client.player) + "m)");
		}
		suspectsThisTick = result;
		return result;
	}

	/**
	 * Copies the current state of blocks you just touched straight into the snapshot, so your own edits never
	 * show up as damage, however long the scanner takes to come round to them.
	 */
	private void applySelfEdits(ClientWorld world, List<Base> active) {
		if (pendingApply.isEmpty()) return;
		var it = pendingApply.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			int age = tick - entry.getValue();
			if (age > APPLY_TICKS) {
				it.remove();
				continue;
			}
			// Wait a couple of ticks for the block to actually change; keep re-applying in case the server corrects it.
			if (age < 2) continue;
			BlockPos pos = BlockPos.fromLong(entry.getKey());
			for (Base base : active) {
				if (!base.contains(pos) || base.baseline.get(pos) == null) continue;
				BlockState current = world.getBlockState(pos);
				if (base.baseline.get(pos) != current) {
					base.baseline.set(pos, current);
					base.baselineDirty = true;
				}
				if (base.changes.remove(entry.getKey()) != null) base.dirty = true;
				if (!ContainerWatcher.isStorage(world.getBlockEntity(pos)) && base.containers.remove(entry.getKey()) != null) base.dirty = true;
			}
		}
	}

	private void autoBuildModeOff(MinecraftClient client, List<Base> active) {
		if (tick % 20 != 0) return;
		for (Base base : active) {
			if (base.distanceTo(client.player.getX(), client.player.getY(), client.player.getZ()) <= BUILD_MODE_RANGE) return;
		}
		buildMode = false;
		Alerts.alert(Text.literal("Build mode turned off: you've left your bases.").formatted(Formatting.GREEN), false);
	}

	private void watchPlayers(MinecraftClient client, List<Base> active) {
		long now = System.currentTimeMillis();
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player == client.player) continue;
			String name = player.getName().getString();
			if (BIConfig.INSTANCE.isFriend(name)) continue;
			for (Base base : active) {
				double distance = base.distanceTo(player.getX(), player.getY(), player.getZ());
				if (distance > (BIConfig.INSTANCE.playerAlertsInsideOnly ? 0 : BIConfig.INSTANCE.playerAlertDistance)) continue;
				String key = name + "\n" + base.name;
				if (now - lastPlayerAlert.getOrDefault(key, 0L) < BIConfig.INSTANCE.playerAlertCooldownMinutes * 60_000L) continue;
				lastPlayerAlert.put(key, now);
				String where = distance == 0 ? "inside " + base.name : (int) distance + "m from " + base.name;
				base.log(Base.EventType.PLAYER, name + " seen " + where + " at " + Alerts.pos(player.getBlockPos()));
				if (BIConfig.INSTANCE.playerAlerts) {
					Alerts.alert(Text.literal("◉ " + name + " is " + where + "!").formatted(Formatting.LIGHT_PURPLE), true);
				}
			}
		}
	}
}
