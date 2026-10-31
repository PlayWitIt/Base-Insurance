package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;

/**
 * Walking to a spot picked on the map, with Baritone. With Naughty or Nice installed it goes as one of its trips
 * (nether highways, flying when it can, its trip bar and stop key); otherwise Baritone walks straight there. Both are
 * optional and reached by reflection: this mod needs neither.
 */
final class Travel {
	private static Boolean baritone;

	private Travel() {}

	/** Whether Baritone (on its own, or inside a client that bundles it) is installed. */
	static boolean available() {
		if (baritone == null) {
			try {
				Class.forName("baritone.api.BaritoneAPI");
				baritone = true;
			} catch (ClassNotFoundException | LinkageError e) {
				baritone = false;
			}
		}
		return baritone;
	}

	/** Sets off for x, z in this dimension. Returns what it's doing, or why it can't: starts with "Going" when it went. */
	static String go(MinecraftClient client, int x, int z) {
		if (!available()) return "Walking there needs Baritone";
		if (client.player == null || client.world == null) return "Join a world first";
		String name = x + ", " + z;
		try {
			Class<?> kind = Class.forName("com.greysworkshop.naughtyornice.Destinations$Kind");
			Class<?> destination = Class.forName("com.greysworkshop.naughtyornice.Destinations$Destination");
			Object waypoint = kind.getField("WAYPOINT").get(null);
			Object d = destination.getConstructor(kind, String.class, String.class, int.class, int.class, int.class, String.class, long.class)
					.newInstance(waypoint, name, "From the map", x, Integer.MIN_VALUE, z, BaseManager.dimensionKey(client.world), System.currentTimeMillis());
			return (String) Class.forName("com.greysworkshop.naughtyornice.SmartTravel")
					.getMethod("go", MinecraftClient.class, destination).invoke(null, client, d);
		} catch (ClassNotFoundException e) {
			// No Naughty or Nice: Baritone on its own.
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			BaseInsuranceClient.LOGGER.warn("Couldn't plan the trip with Naughty or Nice; asking Baritone directly", e);
		}
		try {
			Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
			Object b = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
			Object manager = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(b);
			Object ok = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class).invoke(manager, "goto " + x + " " + z);
			if (!Boolean.TRUE.equals(ok)) return "Baritone didn't take it (see its message in chat)";
			int distance = (int) Math.hypot(x - client.player.getX(), z - client.player.getZ());
			return String.format(java.util.Locale.ROOT, "Going to %s, walking, %,d blocks (Baritone: #stop to stop)", name, distance);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			BaseInsuranceClient.LOGGER.warn("Baritone goto failed", e);
			return "Baritone couldn't go there (see the log)";
		}
	}
}
