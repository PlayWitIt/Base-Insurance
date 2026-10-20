package com.greysworkshop.baseinsurance;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Base Insurance settings, in tabs. */
public class SettingsScreen extends OptionsScreen {
	private static final String P = "baseinsurance.config";

	public SettingsScreen(Screen parent) {
		super(parent, Text.translatable(P + ".title"), P);
	}

	@Override
	protected List<Tab> tabs() {
		BIConfig c = BIConfig.INSTANCE;
		return List.of(
				new Tab("alerts", r -> {
					r.toggle("enabled", () -> c.enabled, v -> c.enabled = v);
					r.header("header.live");
					r.toggle("live_alerts", () -> c.liveAlerts, v -> c.liveAlerts = v);
					r.toggle("count_own", () -> c.countOwnChanges, v -> c.countOwnChanges = v);
					r.slider("ignore_near", 0, 16, () -> c.ignoreNearMe, v -> c.ignoreNearMe = v,
							v -> v == 0 ? Text.translatable(P + ".off").getString() : Text.translatable(P + ".blocks", v).getString());
					r.slider("live_min", 1, 50, () -> c.liveAlertMinBlocks, v -> c.liveAlertMinBlocks = v,
							v -> Text.translatable(P + ".blocks", v).getString());
					r.slider("live_cooldown", 2, 120, () -> c.liveAlertCooldownSeconds, v -> c.liveAlertCooldownSeconds = v,
							v -> Text.translatable(P + ".seconds", v).getString());
					r.header("header.return");
					r.toggle("arrival_reports", () -> c.arrivalReports, v -> c.arrivalReports = v);
					r.toggle("all_clear", () -> c.allClearMessages, v -> c.allClearMessages = v);
					r.slider("all_clear_away", 1, 120, () -> c.allClearMinAwayMinutes, v -> c.allClearMinAwayMinutes = v,
							v -> Text.translatable(P + ".minutes", v).getString());
					r.header("header.players");
					r.toggle("player_alerts", () -> c.playerAlerts, v -> c.playerAlerts = v);
					r.toggle("player_inside_only", () -> c.playerAlertsInsideOnly, v -> c.playerAlertsInsideOnly = v);
					r.slider("player_distance", 0, 128, () -> c.playerAlertDistance, v -> c.playerAlertDistance = v,
							v -> Text.translatable(P + ".blocks", v).getString());
					r.slider("player_cooldown", 1, 60, () -> c.playerAlertCooldownMinutes, v -> c.playerAlertCooldownMinutes = v,
							v -> Text.translatable(P + ".minutes", v).getString());
					r.header("header.containers");
					r.toggle("theft_alerts", () -> c.theftAlerts, v -> c.theftAlerts = v);
					r.header("header.style");
					r.cycle("alert_style", BIConfig.AlertStyle.values(), () -> c.alertStyle, v -> c.alertStyle = v,
							v -> Text.translatable(P + ".alert_style." + v.name().toLowerCase(Locale.ROOT)));
					r.toggle("sounds", () -> c.sounds, v -> c.sounds = v);
					r.slider("sound_volume", 0, 100, () -> c.soundVolume, v -> c.soundVolume = v, v -> v + "%");
				}),
				new Tab("detection", r -> {
					r.slider("trust_radius", 8, 96, () -> c.trustRadius, v -> c.trustRadius = v,
							v -> Text.translatable(P + ".blocks", v).getString());
					r.slider("self_edit", 1, 60, () -> c.selfEditSeconds, v -> c.selfEditSeconds = v,
							v -> Text.translatable(P + ".seconds", v).getString());
					r.header("header.natural");
					r.note(Text.translatable(P + ".natural_note"));
					r.toggle("ignore_plants", () -> c.ignorePlants, v -> c.ignorePlants = v);
					r.toggle("ignore_weather", () -> c.ignoreWeather, v -> c.ignoreWeather = v);
					r.toggle("ignore_fluids", () -> c.ignoreFlowingFluids, v -> c.ignoreFlowingFluids = v);
					r.toggle("smart_terrain", () -> c.smartTerrain, v -> c.smartTerrain = v);
					r.toggle("ignore_hidden", () -> c.ignoreHidden, v -> c.ignoreHidden = v);
					r.toggle("ignore_ores", () -> c.ignoreOreSwaps, v -> c.ignoreOreSwaps = v);
					r.toggle("ignore_pistons", () -> c.ignorePistons, v -> c.ignorePistons = v);
					r.header("header.scanning");
					r.toggle("track_containers", () -> c.trackContainers, v -> c.trackContainers = v);
					r.slider("scan_speed", 1, 24, () -> c.scanSpeed, v -> c.scanSpeed = v,
							v -> Text.translatable(P + ".scan_speed.value", v * 4096 * 20 / 1000).getString());
					r.toggle("build_auto_off", () -> c.buildModeAutoOff, v -> c.buildModeAutoOff = v);
				}),
				new Tab("overlay", r -> {
					r.header("header.overlay");
					r.toggle("auto_overlay", () -> c.autoOverlay, v -> c.autoOverlay = v);
					r.slider("overlay_range", 16, 128, () -> c.overlayRange, v -> c.overlayRange = v,
							v -> Text.translatable(P + ".blocks", v).getString());
					r.toggle("overlay_fill", () -> c.overlayFill, v -> c.overlayFill = v);
					r.slider("overlay_opacity", 10, 90, () -> c.overlayOpacity, v -> c.overlayOpacity = v, v -> v + "%");
					r.slider("outline_width", 1, 6, () -> c.outlineWidth, v -> c.outlineWidth = v, v -> v + " px");
					r.toggle("show_removed", () -> c.showRemoved, v -> c.showRemoved = v);
					r.toggle("show_added", () -> c.showAdded, v -> c.showAdded = v);
					r.toggle("show_replaced", () -> c.showReplaced, v -> c.showReplaced = v);
					r.header("header.hud");
					r.toggle("aim_info", () -> c.aimInfo, v -> c.aimInfo = v);
					r.toggle("hud_status", () -> c.hudStatus, v -> c.hudStatus = v);
					r.cycle("hud_corner", BIConfig.Corner.values(), () -> c.hudCorner, v -> c.hudCorner = v,
							v -> Text.translatable(P + ".hud_corner." + v.name().toLowerCase(Locale.ROOT)));
				}),
				new Tab("map", r -> {
					r.toggle("record_map", () -> c.recordMap, v -> c.recordMap = v);
					r.slider("map_speed", 1, 24, () -> c.mapSpeed, v -> c.mapSpeed = v,
							v -> Text.translatable(P + ".map_speed.value", v * 20).getString());
					r.toggle("map_damage", () -> c.mapDamageDots, v -> c.mapDamageDots = v);
					r.toggle("map_grid", () -> c.mapGrid, v -> c.mapGrid = v);
				}),
				new Tab("data", r -> {
					r.header("header.friends");
					r.adder("friend_add", name -> {
						if (!name.matches("[A-Za-z0-9_]{1,16}") || c.isFriend(name)) return false;
						c.friends.add(name);
						return true;
					});
					if (c.friends.isEmpty()) r.note(Text.translatable(P + ".no_friends"));
					for (String friend : new ArrayList<>(c.friends)) r.removable(Text.literal(friend), () -> c.friends.remove(friend));
					r.header("header.ignored");
					r.adder("ignored_add", id -> {
						Identifier parsed = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
						if (parsed == null || !Registries.BLOCK.containsId(parsed) || c.ignoredBlocks.contains(parsed.toString())) return false;
						c.ignoredBlocks.add(parsed.toString());
						return true;
					});
					if (c.ignoredBlocks.isEmpty()) r.note(Text.translatable(P + ".no_ignored"));
					for (String id : new ArrayList<>(c.ignoredBlocks)) {
						Identifier parsed = Identifier.tryParse(id);
						Text name = parsed != null && Registries.BLOCK.containsId(parsed) ? Registries.BLOCK.get(parsed).getName() : Text.literal(id);
						r.removable(name, () -> c.ignoredBlocks.remove(id));
					}
					r.header("header.data");
					r.slider("max_events", 100, 2000, () -> c.maxEvents, v -> c.maxEvents = v / 50 * 50,
							v -> Text.translatable(P + ".events", v / 50 * 50).getString());
					r.button("open_folder", () -> Util.getOperatingSystem().open(BIConfig.dir()));
				}));
	}

	@Override
	protected void save() {
		BIConfig.save();
	}
}
