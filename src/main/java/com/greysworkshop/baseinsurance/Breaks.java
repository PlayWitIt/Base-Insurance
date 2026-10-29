package com.greysworkshop.baseinsurance;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Proof from the server that blocks really went: it sends a "block broken" effect to players nearby whenever one is
 * broken (not to the one breaking it), and an explosion with its centre and size. A server hiding a chest just sends
 * air, with neither. So a chest gone out of your sight, with this proof, really is gone.
 */
public final class Breaks {
	/** Proof counts for this long. */
	private static final long KEEP_MS = 60_000;

	private static final Map<Long, Long> broken = new HashMap<>();
	private record Explosion(Vec3d center, double reach, long at) {}
	private static final ArrayDeque<Explosion> explosions = new ArrayDeque<>();

	private Breaks() {}

	public static void broken(BlockPos pos) {
		long now = System.currentTimeMillis();
		broken.put(pos.asLong(), now);
		if (broken.size() > 4096) broken.values().removeIf(t -> now - t > KEEP_MS);
	}

	public static void exploded(Vec3d center, float radius) {
		long now = System.currentTimeMillis();
		// An explosion breaks blocks out to about twice its power from the centre.
		explosions.addLast(new Explosion(center, radius * 2.0 + 1, now));
		while (!explosions.isEmpty() && (explosions.size() > 256 || now - explosions.peekFirst().at > KEEP_MS)) explosions.removeFirst();
	}

	/** Whether the server said, lately, that this block was broken or blown up. */
	public static boolean proven(BlockPos pos) {
		long now = System.currentTimeMillis();
		Long at = broken.get(pos.asLong());
		if (at != null && now - at <= KEEP_MS) return true;
		Vec3d c = Vec3d.ofCenter(pos);
		for (Explosion e : explosions) {
			if (now - e.at <= KEEP_MS && e.center.distanceTo(c) <= e.reach) return true;
		}
		return false;
	}

	private record Sound(Vec3d at, long time) {}
	private static final ArrayDeque<Sound> entitySounds = new ArrayDeque<>();
	private static final java.util.Set<net.minecraft.sound.SoundEvent> ENTITY_SOUNDS = java.util.Set.of(
			net.minecraft.sound.SoundEvents.ENTITY_ITEM_FRAME_BREAK, net.minecraft.sound.SoundEvents.ENTITY_ITEM_FRAME_REMOVE_ITEM,
			net.minecraft.sound.SoundEvents.ENTITY_GLOW_ITEM_FRAME_BREAK, net.minecraft.sound.SoundEvents.ENTITY_GLOW_ITEM_FRAME_REMOVE_ITEM,
			net.minecraft.sound.SoundEvents.ENTITY_ARMOR_STAND_BREAK, net.minecraft.sound.SoundEvents.ENTITY_PAINTING_BREAK);

	/** A sound the server played: an item frame, armor stand or painting broken (or a frame emptied) there. */
	public static void sound(net.minecraft.registry.entry.RegistryEntry<net.minecraft.sound.SoundEvent> sound, double x, double y, double z) {
		if (!ENTITY_SOUNDS.contains(sound.value())) return;
		long now = System.currentTimeMillis();
		entitySounds.addLast(new Sound(new Vec3d(x, y, z), now));
		while (!entitySounds.isEmpty() && (entitySounds.size() > 256 || now - entitySounds.peekFirst().time > KEEP_MS)) entitySounds.removeFirst();
	}

	/** Whether the server said, lately, that an item frame, armor stand or painting at this block was broken or emptied. */
	public static boolean entityProven(BlockPos pos) {
		long now = System.currentTimeMillis();
		Vec3d c = Vec3d.ofCenter(pos);
		for (Sound s : entitySounds) {
			if (now - s.time <= KEEP_MS && s.at.distanceTo(c) <= 1.5) return true;
		}
		return false;
	}

	public static void clear() {
		broken.clear();
		explosions.clear();
		entitySounds.clear();
	}
}
