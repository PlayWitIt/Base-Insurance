package com.greysworkshop.baseinsurance;

/**
 * What this server shows you, learned as you play: how far off it still sends chests (and the like), and item frames
 * (and the like), that you can't see. Most servers send everything in range, and once a chest behind a wall has turned
 * up at some distance, a chest that's gone that close, out of sight, really is gone. A server that hides them never
 * shows one out of sight, so there nothing is taken on trust and only proof counts (see {@link Breaks}).
 */
public final class ServerHabits {
	private ServerHabits() {}

	/** How far off, out of sight, this server has been seen sending chests and the like (0: never). */
	public static double containers() {
		return reach(false);
	}

	/** The same for item frames, armor stands and paintings. */
	public static double entities() {
		return reach(true);
	}

	/**
	 * One is there, fully out of sight, this far off. Hiders lag a little behind you as you move, so one sighting
	 * proves nothing: only one seen like this again and again, for a few seconds, shows the server doesn't hide them.
	 */
	public static void sawContainer(Object which, double distance) {
		sighted(false, which, distance);
	}

	public static void sawEntity(Object which, double distance) {
		sighted(true, which, distance);
	}

	/** Sightings must keep coming (no gap over this) for this long, and number at least three. */
	private static final long SIGHTING_GAP_MS = 10_000, SIGHTING_SPAN_MS = 5_000;
	private static final class Sighting {
		long first, last;
		int count;
	}
	private static final java.util.Map<java.util.List<Object>, Sighting> sightings = new java.util.HashMap<>();

	private static void sighted(boolean entities, Object which, double distance) {
		long now = System.currentTimeMillis();
		Sighting s = sightings.computeIfAbsent(java.util.List.of(entities, which), k -> new Sighting());
		if (now - s.last > SIGHTING_GAP_MS) {
			s.first = now;
			s.count = 0;
		}
		s.last = now;
		s.count++;
		if (sightings.size() > 4096) sightings.values().removeIf(x -> now - x.last > SIGHTING_GAP_MS);
		if (s.count >= 3 && now - s.first >= SIGHTING_SPAN_MS) saw(entities, distance);
	}

	/** Leaving the server (or the test starting over): sightings so far don't carry over. */
	public static void reset() {
		sightings.clear();
	}

	/**
	 * One counted as gone out of sight turned up again, unchanged: this server does hide them (it may have started to).
	 * Back to taking nothing on trust, until it shows otherwise.
	 */
	public static void containerCameBack() {
		forget(false);
	}

	public static void entityCameBack() {
		forget(true);
	}

	private static void forget(boolean entities) {
		// Sightings so far count for nothing either: it has to show them out of sight all over again.
		sightings.keySet().removeIf(k -> k.get(0).equals(entities));
		String key = key(entities);
		if (key == null || BIConfig.INSTANCE.serverReach.remove(key) == null) return;
		BaseInsuranceClient.LOGGER.info("This server hid {} out of sight after all: only trusting proof again", entities ? "entities" : "containers");
		BIConfig.save();
	}

	private static String key(boolean entities) {
		String world = BaseInsuranceClient.INSTANCE.manager.worldKey();
		return world == null ? null : world + (entities ? "#entities" : "#containers");
	}

	private static double reach(boolean entities) {
		String key = key(entities);
		double seen = key == null ? 0 : BIConfig.INSTANCE.serverReach.getOrDefault(key, 0.0);
		// Half a block past the furthest one seen: a server that sends one that far sends one a step further too.
		return seen > 0 ? seen + 0.5 : 0;
	}

	private static void saw(boolean entities, double distance) {
		String key = key(entities);
		if (key == null) return;
		double known = BIConfig.INSTANCE.serverReach.getOrDefault(key, 0.0);
		// Saved only when it grows by a quarter block or more, so it isn't written every frame.
		if (distance < known + 0.25) return;
		BIConfig.INSTANCE.serverReach.put(key, distance);
		BIConfig.save();
	}
}
