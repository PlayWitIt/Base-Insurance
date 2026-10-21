package com.greysworkshop.baseinsurance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Pins other mods put on the map; Naughty or Nice uses it for where it last saw players. Uses plain Java types, so
 * a mod can add a provider by reflection without depending on this one.
 */
public final class MapMarkers {
	/**
	 * Each provider is given a dimension id ("minecraft:overworld") and returns its pins there, as maps with "x" and
	 * "z" (numbers), "color" (an ARGB number), "label" (text), and optionally "detail" (a second line of text).
	 */
	private static final List<Function<String, List<Map<String, Object>>>> PROVIDERS = new CopyOnWriteArrayList<>();

	public record Marker(double x, double z, int color, String label, String detail) {}

	private MapMarkers() {}

	public static void register(Function<String, List<Map<String, Object>>> provider) {
		PROVIDERS.add(provider);
	}

	/** Every pin in a dimension; a provider that fails or returns something odd is skipped. */
	public static List<Marker> in(String dimension) {
		List<Marker> markers = new ArrayList<>();
		for (Function<String, List<Map<String, Object>>> provider : PROVIDERS) {
			try {
				List<Map<String, Object>> pins = provider.apply(dimension);
				if (pins == null) continue;
				for (Map<String, Object> pin : pins) {
					if (!(pin.get("x") instanceof Number x) || !(pin.get("z") instanceof Number z)) continue;
					int color = pin.get("color") instanceof Number c ? c.intValue() : 0xFFFFFFFF;
					String label = pin.get("label") instanceof String s ? s : "";
					String detail = pin.get("detail") instanceof String s ? s : "";
					markers.add(new Marker(x.doubleValue(), z.doubleValue(), color, label, detail));
				}
			} catch (RuntimeException e) {
				BaseInsuranceClient.LOGGER.debug("Map pins failed", e);
			}
		}
		return markers;
	}
}
