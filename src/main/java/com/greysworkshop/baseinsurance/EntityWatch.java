package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches item frames (map art!), armor stands and paintings in bases, within the trust radius like blocks.
 * Reports ones that disappear or whose contents change, ignoring ones you just hit or used yourself.
 */
public final class EntityWatch {
	private static final int SCAN_INTERVAL = 20;
	/** An entity you hit or used counts as yours for this long. */
	private static final int SELF_TICKS = 300;
	/** A recorded entity must be missing on this many scans in a row: entities load in a little after blocks. */
	private static final int MISSING_SCANS = 2;

	private final BaseManager manager;
	private final Scanner scanner;
	private final Map<String, Integer> selfTouched = new HashMap<>();
	private final Map<String, Integer> missing = new HashMap<>();
	private final Map<Base, Long> lastAlert = new HashMap<>();
	private int tick;

	public EntityWatch(BaseManager manager, Scanner scanner) {
		this.manager = manager;
		this.scanner = scanner;
	}

	public static boolean watched(Entity entity) {
		return entity instanceof ItemFrameEntity || entity instanceof ArmorStandEntity || entity instanceof PaintingEntity;
	}

	/**
	 * Identifies an entity. Frames and paintings by type, block and facing (a frame swapped for another in the
	 * same spot is "the same"); armor stands by their unique id, since they can be pushed around.
	 */
	public static String key(Entity entity) {
		String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
		if (entity instanceof ArmorStandEntity) return type + "#" + entity.getUuidAsString();
		String facing = entity instanceof ItemFrameEntity frame ? "/" + frame.getHorizontalFacing().asString()
				: entity instanceof PaintingEntity painting ? "/" + painting.getHorizontalFacing().asString() : "";
		return type + "@" + entity.getBlockX() + "," + entity.getBlockY() + "," + entity.getBlockZ() + facing;
	}

	public static Base.EntityRecord record(Entity entity) {
		Base.EntityRecord record = new Base.EntityRecord();
		record.type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
		record.name = entity.getType().getName().getString();
		record.x = entity.getBlockX();
		record.y = entity.getBlockY();
		record.z = entity.getBlockZ();
		if (entity instanceof ItemFrameEntity frame) {
			ItemStack held = frame.getHeldItemStack();
			record.item = held.isEmpty() ? "" : Registries.ITEM.getId(held.getItem()).toString();
			MapIdComponent map = held.get(DataComponentTypes.MAP_ID);
			record.mapId = map == null ? -1 : map.id();
			record.rotation = frame.getRotation();
		} else if (entity instanceof ArmorStandEntity stand) {
			List<String> gear = new ArrayList<>();
			for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
					EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
				ItemStack stack = stand.getEquippedStack(slot);
				gear.add(stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString());
			}
			record.equipment = String.join(",", gear);
		} else if (entity instanceof PaintingEntity painting) {
			record.variant = painting.getVariant().getKey().map(k -> k.getValue().toString()).orElse("?");
		}
		return record;
	}

	/** You hit or used this entity: changes to it for a while are yours. */
	public void markSelf(Entity entity) {
		if (!BIConfig.INSTANCE.countOwnChanges) selfTouched.put(key(entity), tick + SELF_TICKS);
	}

	private boolean isSelf(String key) {
		Integer expiry = selfTouched.get(key);
		return expiry != null && expiry >= tick;
	}

	public void tick(MinecraftClient client) {
		tick++;
		if (tick % SCAN_INTERVAL != 0 || client.player == null || client.world == null) return;
		flush();
		selfTouched.values().removeIf(expiry -> expiry < tick);
		ClientWorld world = client.world;
		Vec3d eye = client.player.getEyePos();
		double radius = BIConfig.INSTANCE.trustRadius;
		for (Base base : manager.inDimension(BaseManager.dimensionKey(world))) {
			if (base.distanceTo(eye.x, eye.y, eye.z) > radius) {
				base.entitiesWatched = false;
				continue;
			}
			scan(client, world, base, eye, radius);
			base.entitiesWatched = true;
		}
	}

	private void scan(MinecraftClient client, ClientWorld world, Base base, Vec3d eye, double radius) {
		boolean live = base.entitiesWatched;
		Box box = new Box(base.minX, base.minY, base.minZ, base.maxX + 1, base.maxY + 1, base.maxZ + 1);
		Set<String> seen = new HashSet<>();
		List<String> found = new ArrayList<>();

		for (Entity entity : world.getOtherEntities(null, box, EntityWatch::watched)) {
			if (entity.getEyePos().distanceTo(eye) > radius || !scanner.settled(entity.getBlockX() >> 4, entity.getBlockZ() >> 4)) continue;
			String key = key(entity);
			seen.add(key);
			missing.remove(base.name + key);
			Base.EntityRecord now = record(entity);
			Base.EntityRecord was = base.entities.get(key);
			if (was == null || isSelf(key)) {
				// New ones (you putting up frames, mostly) and ones you touched just become the record.
				if (was == null || !was.detail().equals(now.detail())) {
					base.entities.put(key, now);
					base.dirty = true;
				}
				if (base.entityChanges.remove(key) != null) base.dirty = true;
				continue;
			}
			Base.EntityChange change = base.entityChanges.get(key);
			if (was.detail().equals(now.detail())) {
				// Same contents; an armor stand may just have been pushed, so keep its position current.
				if (was.x != now.x || was.y != now.y || was.z != now.z) {
					base.entities.put(key, now);
					base.dirty = true;
				}
				if (change != null) {
					base.entityChanges.remove(key);
					base.dirty = true;
				}
				continue;
			}
			if (change != null) {
				if (change.now == null || !change.now.detail().equals(now.detail())) {
					change.now = now;
					base.dirty = true;
				}
				continue;
			}
			found.add(key);
			base.entityChanges.put(key, newChange(client, was, now, live));
			base.dirty = true;
		}

		// Recorded ones that aren't there any more.
		for (Map.Entry<String, Base.EntityRecord> entry : new ArrayList<>(base.entities.entrySet())) {
			String key = entry.getKey();
			Base.EntityRecord was = entry.getValue();
			if (seen.contains(key)) continue;
			if (Vec3d.ofCenter(was.pos()).distanceTo(eye) > radius || !world.isChunkLoaded(was.x >> 4, was.z >> 4)
					|| !scanner.settled(was.x >> 4, was.z >> 4)) continue;
			if (isSelf(key)) {
				base.entities.remove(key);
				base.entityChanges.remove(key);
				base.dirty = true;
				continue;
			}
			if (missing.merge(base.name + key, 1, Integer::sum) < MISSING_SCANS) continue;
			Base.EntityChange change = base.entityChanges.get(key);
			if (change != null && change.now == null) continue;
			if (change != null) {
				change.now = null;
			} else {
				found.add(key);
				base.entityChanges.put(key, newChange(client, was, null, live));
			}
			base.dirty = true;
		}

		if (!found.isEmpty()) announce(base, found, live);
	}

	private Base.EntityChange newChange(MinecraftClient client, Base.EntityRecord was, Base.EntityRecord now, boolean live) {
		Base.EntityChange change = new Base.EntityChange();
		change.was = was;
		change.now = now;
		change.firstSeen = System.currentTimeMillis();
		change.live = live;
		if (live) {
			change.suspects = new ArrayList<>();
			for (PlayerEntity player : client.world.getPlayers()) {
				if (player == client.player || BIConfig.INSTANCE.isFriend(player.getName().getString())) continue;
				change.suspects.add(player.getName().getString() + " (" + (int) player.distanceTo(client.player) + "m)");
			}
		}
		return change;
	}

	private void announce(Base base, List<String> keys, boolean live) {
		List<String> lines = new ArrayList<>();
		List<String> suspects = null;
		for (String key : keys) {
			Base.EntityChange change = base.entityChanges.get(key);
			// Where bases overlap, only the smallest one announces it.
			if (change == null || manager.owner(base.dimension, change.was.pos()) != base) continue;
			lines.add(change.describe() + " at " + Alerts.pos(change.was.pos()));
			if (change.suspects != null && !change.suspects.isEmpty()) suspects = change.suspects;
		}
		if (lines.isEmpty()) return;
		String where = !live ? "" : suspects == null ? " — no other players in sight" : " — nearby: " + String.join(", ", suspects);
		base.log(live ? Base.EventType.LIVE_DAMAGE : Base.EventType.AWAY_DAMAGE, String.join("; ", lines) + where);
		boolean wanted = live ? BIConfig.INSTANCE.liveAlerts : BIConfig.INSTANCE.arrivalReports;
		if (!wanted) return;
		// Queue it; changes within the cooldown are sent together once it's over.
		Pending queued = pending.computeIfAbsent(base, b -> new Pending());
		queued.lines.addAll(lines);
		if (!where.isEmpty()) queued.where = where;
		flush();
	}

	private static final class Pending {
		final List<String> lines = new ArrayList<>();
		String where = "";
	}

	private final Map<Base, Pending> pending = new HashMap<>();

	private void flush() {
		long now = System.currentTimeMillis();
		var it = pending.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Base base = entry.getKey();
			if (now - lastAlert.getOrDefault(base, 0L) < BIConfig.INSTANCE.liveAlertCooldownSeconds * 1000L) continue;
			Pending queued = entry.getValue();
			it.remove();
			lastAlert.put(base, now);
			List<String> lines = queued.lines;
			String text = String.join("; ", lines.subList(0, Math.min(3, lines.size())))
					+ (lines.size() > 3 ? " +" + (lines.size() - 3) + " more" : "") + queued.where;
			Alerts.alert(Text.empty()
					.append(Text.literal("⚠ " + base.name + ": ").formatted(Formatting.RED, Formatting.BOLD))
					.append(Text.literal(text + ". ").formatted(Formatting.YELLOW))
					.append(Alerts.reportLink(base)), true);
		}
	}

	public void reset() {
		missing.clear();
		pending.clear();
		selfTouched.clear();
		for (Base base : manager.all()) base.entitiesWatched = false;
	}
}
