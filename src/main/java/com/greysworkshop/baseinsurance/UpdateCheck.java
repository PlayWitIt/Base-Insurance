package com.greysworkshop.baseinsurance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Tells you once per game session, a little after you join a world, when a newer release of Base Insurance is out on
 * GitHub, with a link to download it. Nothing is downloaded or installed by itself. Off in the settings.
 */
public final class UpdateCheck {
	private UpdateCheck() {}

	private static final String REPO = "PlayWitIt/Base-Insurance";
	/** Wait this long in a world before saying anything, so it doesn't land on top of join messages. */
	private static final int DELAY_TICKS = 300;

	private record Release(String version, String url) {}

	private static boolean started;
	private static boolean told;
	private static volatile Release found;
	private static int ticksInWorld;

	/** The version running, e.g. "1.5.0" (without the "+1.21.11"). */
	public static String current() {
		String v = FabricLoader.getInstance().getModContainer("baseinsurance").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
		int plus = v.indexOf('+');
		return plus > 0 ? v.substring(0, plus) : v;
	}

	/** Every tick: starts the check once, then says so once if there's something newer. */
	public static void tick(MinecraftClient client) {
		if (!BIConfig.INSTANCE.checkUpdates || told) return;
		if (!started) {
			started = true;
			CompletableFuture.runAsync(UpdateCheck::check);
		}
		if (client.player == null) {
			ticksInWorld = 0;
			return;
		}
		Release release = found;
		if (release == null || ++ticksInWorld < DELAY_TICKS) return;
		told = true;
		if (release.version.equals(BIConfig.INSTANCE.skippedVersion)) return;
		Alerts.chat(Text.literal("Base Insurance " + release.version + " is out (you have " + current() + "). ").formatted(Formatting.YELLOW)
				.append(Text.literal("[Download]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.OpenUrl(URI.create(release.url)))))
				.append(Text.literal(" "))
				.append(Text.literal("[Skip this version]").styled(s -> s.withColor(Formatting.GRAY).withUnderline(true)
						.withClickEvent(new ClickEvent.RunCommand("/bi update skip " + release.version)))));
	}

	private static void check() {
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
					.timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
					.header("User-Agent", "Base Insurance/" + current() + " (Minecraft mod by PlayWit Creation)").GET().build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			// 404: no releases yet, or the repository isn't public.
			if (response.statusCode() != 200) return;
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			String tag = json.has("tag_name") ? json.get("tag_name").getAsString().replaceFirst("^[vV]", "") : "";
			String url = json.has("html_url") ? json.get("html_url").getAsString() : "https://github.com/" + REPO + "/releases";
			if (!tag.isEmpty() && newer(tag, current()) && url.startsWith("https://github.com/")) found = new Release(tag, url);
		} catch (Exception e) {
			// No internet, or GitHub is down: try again next time the game starts.
			BaseInsuranceClient.LOGGER.debug("Update check failed: {}", e.toString());
		}
	}

	/** Whether version a is newer than b, comparing the numbers in order ("1.10.0" is newer than "1.9.2"). */
	static boolean newer(String a, String b) {
		String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
			int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
			if (p != q) return p > q;
		}
		return false;
	}
}
