package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Chat messages, sounds and text formatting. Everything here stays on your client; nothing is sent to the server. */
public final class Alerts {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

	private Alerts() {}

	public static void chat(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		client.inGameHud.getChatHud().addMessage(Text.empty()
				.append(Text.literal("[Base Insurance] ").formatted(Formatting.GOLD))
				.append(message));
	}

	/** An alert, shown where the settings say (chat, action bar or both), with a sound if enabled. */
	public static void alert(Text message, boolean urgent) {
		BIConfig config = BIConfig.INSTANCE;
		MinecraftClient client = MinecraftClient.getInstance();
		if (config.alertStyle != BIConfig.AlertStyle.ACTION_BAR) chat(message);
		if (config.alertStyle != BIConfig.AlertStyle.CHAT) client.inGameHud.setOverlayMessage(message, false);
		sound(urgent);
	}

	public static void sound(boolean urgent) {
		BIConfig config = BIConfig.INSTANCE;
		if (!config.sounds || config.soundVolume <= 0) return;
		float volume = config.soundVolume / 100f;
		MinecraftClient client = MinecraftClient.getInstance();
		if (urgent) {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.6f, volume));
		} else {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.2f, volume));
		}
	}

	public static MutableText reportLink(Base base) {
		return Text.literal("[Open report]").styled(style -> style
				.withColor(Formatting.AQUA)
				.withUnderline(true)
				.withClickEvent(new ClickEvent.RunCommand("/bi report " + base.name)));
	}

	/** "1 block", "3 blocks". */
	public static String plural(int count, String word) {
		return count + " " + word + (count == 1 ? "" : "s");
	}

	public static String time(long millis) {
		return TIME.format(Instant.ofEpochMilli(millis));
	}

	public static String ago(long millis) {
		long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
		if (seconds < 60) return seconds + "s ago";
		if (seconds < 3600) return seconds / 60 + "m ago";
		if (seconds < 86400) return seconds / 3600 + "h " + (seconds % 3600) / 60 + "m ago";
		return seconds / 86400 + "d " + (seconds % 86400) / 3600 + "h ago";
	}

	public static String pos(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** "obsidian ×200, chest ×4, glass ×3 +5 more" from block names. */
	public static String topCounts(Map<String, Integer> counts, int limit) {
		List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.toList();
		String shown = sorted.stream().limit(limit)
				.map(e -> e.getKey() + " ×" + e.getValue())
				.collect(Collectors.joining(", "));
		return sorted.size() > limit ? shown + " +" + (sorted.size() - limit) + " more" : shown;
	}

	/** One-line summary of a set of changes, e.g. "320 removed (obsidian ×200, …), 15 placed (lava ×10)". */
	public static String summarize(Iterable<Base.Change> changes) {
		Map<String, Integer> removed = new LinkedHashMap<>();
		Map<String, Integer> added = new LinkedHashMap<>();
		Map<String, Integer> replaced = new LinkedHashMap<>();
		int r = 0, a = 0, p = 0;
		for (Base.Change change : changes) {
			switch (change.kind()) {
				case REMOVED -> { r++; removed.merge(BlockStates.name(change.was).getString(), 1, Integer::sum); }
				case ADDED -> { a++; added.merge(BlockStates.name(change.now).getString(), 1, Integer::sum); }
				case REPLACED -> {
					p++;
					replaced.merge(BlockStates.name(change.was).getString() + "→" + BlockStates.name(change.now).getString(), 1, Integer::sum);
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		if (r > 0) sb.append(r).append(" removed (").append(topCounts(removed, 3)).append(")");
		if (a > 0) sb.append(sb.isEmpty() ? "" : ", ").append(a).append(" placed (").append(topCounts(added, 3)).append(")");
		if (p > 0) sb.append(sb.isEmpty() ? "" : ", ").append(p).append(" replaced (").append(topCounts(replaced, 2)).append(")");
		return sb.toString();
	}
}
