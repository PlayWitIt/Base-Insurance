package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

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
	/** Loaded sections compared per tick (4096 blocks each). */
	private static final int SECTIONS_PER_TICK = 6;
	/** Upper bound on sections looked at per tick, loaded or not. */
	private static final int MAX_VISITS_PER_TICK = 4000;
	/** How long a block you touched counts as your own edit. */
	private static final int SELF_EDIT_TICKS = 100;
	private static final long LIVE_ALERT_INTERVAL = 10_000;
	private static final long PLAYER_ALERT_INTERVAL = 5 * 60_000;
	/** Coming back after less than this doesn't get an "all clear" message. */
	private static final long ALL_CLEAR_MIN_AWAY = 5 * 60_000;

	private final BaseManager manager;
	private final Map<Long, Integer> selfEdits = new HashMap<>();
	private final Map<Base, List<Base.Change>> pendingLive = new HashMap<>();
	private final Map<Base, Long> lastLiveAlert = new HashMap<>();
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
		selfEdits.put(pos.asLong(), tick + SELF_EDIT_TICKS);
	}

	public void reset() {
		selfEdits.clear();
		pendingLive.clear();
		for (Base base : manager.all()) {
			base.loadedSections.clear();
			base.arriving = false;
		}
	}

	public void tick(MinecraftClient client) {
		tick++;
		suspectsThisTick = null;
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
		if (active.isEmpty()) return;

		int budget = SECTIONS_PER_TICK;
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
		if (tick % 20 == 0) watchPlayers(client, active);
	}

	/** Compares one section; returns false if it wasn't loaded (and so cost nothing). */
	private boolean scanSection(MinecraftClient client, ClientWorld world, Base base, long key) {
		int sx = ChunkSectionPos.unpackX(key);
		int sy = ChunkSectionPos.unpackY(key);
		int sz = ChunkSectionPos.unpackZ(key);
		if (!world.isChunkLoaded(sx, sz)) {
			base.loadedSections.remove(key);
			return false;
		}

		Baseline.Section section = base.baseline.sections.get(key);
		if (section == null) {
			base.baseline.sections.put(key, Baseline.capture(world, key));
			base.baselineDirty = true;
			base.loadedSections.add(key);
			return true;
		}

		long now = System.currentTimeMillis();
		boolean wasLoaded = base.loadedSections.contains(key);
		if (!wasLoaded && !base.arriving) {
			base.arriving = true;
			base.awaySince = base.lastSeen;
			base.arrivalStart = now;
			base.arrivalChanges = 0;
		}

		int x0 = Math.max(sx << 4, base.minX), x1 = Math.min((sx << 4) + 15, base.maxX);
		int y0 = Math.max(sy << 4, base.minY), y1 = Math.min((sy << 4) + 15, base.maxY);
		int z0 = Math.max(sz << 4, base.minZ), z1 = Math.min((sz << 4) + 15, base.maxZ);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					int index = Baseline.index(x, y, z);
					BlockState was = section.get(index);
					if (was == null) continue;
					BlockState current = world.getBlockState(pos.set(x, y, z));
					long posKey = pos.asLong();
					Base.Change existing = base.changes.get(posKey);

					if (ChangeFilter.same(was, current)) {
						// Unchanged, or repaired.
						if (existing != null) {
							base.changes.remove(posKey);
							base.dirty = true;
						}
						continue;
					}

					if (buildMode || selfEdits.containsKey(posKey)) {
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
					if (wasLoaded) {
						change.suspects = suspects(client);
						pendingLive.computeIfAbsent(base, b -> new ArrayList<>()).add(change);
					} else {
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

	private void finishPass(Base base) {
		long now = System.currentTimeMillis();
		if (base.loadedSections.isEmpty()) return;
		// Saving lastSeen every second would rewrite the file constantly; once a minute is plenty.
		if (now - base.lastSeen > 60_000) base.dirty = true;
		base.lastSeen = now;

		if (!base.arriving) return;
		base.arriving = false;
		long since = base.awaySince > 0 ? base.awaySince : base.created;

		if (base.arrivalChanges > 0) {
			List<Base.Change> found = new ArrayList<>();
			int destroyedContainers = 0;
			for (Map.Entry<Long, Base.Change> entry : base.changes.entrySet()) {
				Base.Change change = entry.getValue();
				if (change.live || change.firstSeen < base.arrivalStart) continue;
				found.add(change);
				if (base.containers.containsKey(entry.getKey()) && change.kind() != Base.Kind.ADDED) destroyedContainers++;
			}
			String summary = found.size() + " blocks changed: " + Alerts.summarize(found);
			if (destroyedContainers > 0) summary += ". " + destroyedContainers + " container(s) destroyed";
			base.log(Base.EventType.AWAY_DAMAGE, "While you were away (since " + Alerts.time(since) + "): " + summary);
			Alerts.chat(Text.empty()
					.append(Text.literal("⚠ " + base.name + " changed while you were away! ").formatted(Formatting.RED, Formatting.BOLD))
					.append(Text.literal(summary + ". ").formatted(Formatting.YELLOW))
					.append(Alerts.reportLink(base)));
			Alerts.sound(true);
			if (BIConfig.INSTANCE.autoOverlay) Overlay.enabled = true;
		} else if (now - since >= ALL_CLEAR_MIN_AWAY && base.changes.isEmpty()) {
			if (BIConfig.INSTANCE.allClearMessages) {
				Alerts.chat(Text.literal("✔ " + base.name + " is untouched since " + Alerts.time(since) + ".").formatted(Formatting.GREEN));
				Alerts.sound(false);
			}
			base.log(Base.EventType.ALL_CLEAR, "Checked on return: untouched since " + Alerts.time(since));
		}
	}

	private void flushLiveAlerts() {
		if (pendingLive.isEmpty()) return;
		long now = System.currentTimeMillis();
		var it = pendingLive.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Base base = entry.getKey();
			if (now - lastLiveAlert.getOrDefault(base, 0L) < LIVE_ALERT_INTERVAL) continue;
			List<Base.Change> changes = entry.getValue();
			it.remove();
			lastLiveAlert.put(base, now);

			List<String> suspects = changes.get(changes.size() - 1).suspects;
			String who = suspects == null || suspects.isEmpty()
					? "no other players in sight (mobs, explosions, or someone out of view)"
					: "nearby: " + String.join(", ", suspects);
			String text = changes.size() + " block(s) changed: " + Alerts.summarize(changes) + " — " + who;
			base.log(Base.EventType.LIVE_DAMAGE, text);
			if (BIConfig.INSTANCE.liveAlerts) {
				Alerts.chat(Text.empty()
						.append(Text.literal("⚠ " + base.name + ": ").formatted(Formatting.RED, Formatting.BOLD))
						.append(Text.literal(text).formatted(Formatting.YELLOW)));
				Alerts.sound(true);
			}
		}
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

	private void watchPlayers(MinecraftClient client, List<Base> active) {
		long now = System.currentTimeMillis();
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player == client.player) continue;
			String name = player.getName().getString();
			if (BIConfig.INSTANCE.isFriend(name)) continue;
			for (Base base : active) {
				double distance = base.distanceTo(player.getX(), player.getY(), player.getZ());
				if (distance > BIConfig.INSTANCE.playerAlertDistance) continue;
				String key = name + "\n" + base.name;
				if (now - lastPlayerAlert.getOrDefault(key, 0L) < PLAYER_ALERT_INTERVAL) continue;
				lastPlayerAlert.put(key, now);
				String where = distance == 0 ? "inside " + base.name : (int) distance + "m from " + base.name;
				base.log(Base.EventType.PLAYER, name + " seen " + where + " at " + Alerts.pos(player.getBlockPos()));
				if (BIConfig.INSTANCE.playerAlerts) {
					Alerts.chat(Text.literal("◉ " + name + " is " + where + "!").formatted(Formatting.LIGHT_PURPLE));
					Alerts.sound(true);
				}
			}
		}
	}
}
