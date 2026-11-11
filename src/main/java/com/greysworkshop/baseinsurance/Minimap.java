package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.Locale;

/** The explored map in a corner of the screen while you play, centred on you, growing as you explore. */
public final class Minimap {
	public static final double MIN_ZOOM = 0.125;
	public static final double MAX_ZOOM = 4;
	private static final int MARGIN = 4;
	private static final int BACKGROUND = 0xFF0B0B12;
	private static final int BORDER = 0xFF33334D;

	/** Where the minimap (and its coordinates line) ended last frame, so the status text can go below it. */
	private static int bottom = -1;
	/** Its left edge, border included, last frame. */
	private static int left = -1;
	private static BIConfig.Corner corner = BIConfig.Corner.TOP_RIGHT;

	private Minimap() {}

	/** Where the minimap goes on a screen this big, at its current size: {x0, y0, size} (no potion-icon shift). */
	public static int[] spot(int screenWidth) {
		int size = BIConfig.INSTANCE.minimapSize;
		boolean right = BIConfig.INSTANCE.minimapCorner == BIConfig.Corner.TOP_RIGHT;
		return new int[] {right ? screenWidth - size - MARGIN : MARGIN, MARGIN, size};
	}

	/** The y just below the minimap in {@code side}, or -1 if it isn't there. */
	public static int bottomIn(BIConfig.Corner side) {
		return side == corner ? bottom : -1;
	}

	/** The x of the minimap's left edge in {@code side}, or -1 if it isn't there. */
	public static int leftIn(BIConfig.Corner side) {
		return side == corner && bottom >= 0 ? left : -1;
	}

	/** Frames drawn so far (for the self-test, which must wait for one after a change). */
	private static int frames;

	static int frames() {
		return frames;
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		BIConfig config = BIConfig.INSTANCE;
		frames++;
		bottom = -1;
		// Stream-safe mode (see ModsPanel): the map around you gives your location away to anyone watching.
		if (!config.enabled || !config.minimap || ModsPanel.streamSafe() || client.world == null || client.player == null || client.options.hudHidden
				|| client.getDebugHud().shouldShowDebugHud() || client.currentScreen instanceof MapScreen) {
			return;
		}
		corner = config.minimapCorner;
		boolean right = corner == BIConfig.Corner.TOP_RIGHT;
		int size = config.minimapSize;
		int x0 = right ? context.getScaledWindowWidth() - size - MARGIN : MARGIN;
		int y0 = MARGIN + (right ? effectRows(client) * 26 : 0);
		int x1 = x0 + size, y1 = y0 + size;
		float delta = tickCounter.getTickProgress(true);
		Vec3d you = client.player.getLerpedPos(delta);
		Camera camera = client.gameRenderer.getCamera();
		// In freecam the map follows the camera, so it shows what you're looking at; you get a marker where you really are.
		boolean freecam = detached(client, camera, delta);
		Vec3d me = freecam ? camera.getCameraPos() : you;
		double zoom = config.minimapZoom;
		boolean rotate = config.minimapRotate;
		float playerYaw = client.player.getYaw(delta);
		float yaw = freecam ? camera.getYaw() : playerYaw;
		// Rotating: turn the world so the way you face is up.
		float turn = rotate ? (float) Math.toRadians(-(yaw + 180)) : 0;

		left = x0 - 2;
		context.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, BORDER);
		context.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF000000);
		context.fill(x0, y0, x1, y1, BACKGROUND);
		context.enableScissor(x0, y0, x1, y1);
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(x0 + size / 2f, y0 + size / 2f);
		matrices.rotate(turn);
		// Rotated, the corners reach further out, so draw a little more than half the size each way.
		double reach = rotate ? size * 0.72 : size / 2.0;
		MapRender.drawTerrain(context, 0, 0, me.x, me.z, zoom, reach, reach);

		for (Base base : BaseInsuranceClient.INSTANCE.manager.inDimension(BaseManager.dimensionKey(client.world))) {
			int bx0 = (int) Math.floor((base.minX - me.x) * zoom), bx1 = (int) Math.ceil((base.maxX + 1 - me.x) * zoom);
			int bz0 = (int) Math.floor((base.minZ - me.z) * zoom), bz1 = (int) Math.ceil((base.maxZ + 1 - me.z) * zoom);
			int color = base.damageCount() > 0 ? 0xFFFF5050 : 0xFF50FF70;
			context.fill(bx0, bz0, bx1, bz1, (color & 0xFFFFFF) | 0x30000000);
			context.drawStrokedRectangle(bx0, bz0, Math.max(1, bx1 - bx0), Math.max(1, bz1 - bz0), color);
		}
		if (config.minimapPlayers) {
			for (PlayerEntity player : client.world.getPlayers()) {
				if (player == client.player) continue;
				Vec3d pos = player.getLerpedPos(delta);
				int px = (int) Math.round((pos.x - me.x) * zoom), pz = (int) Math.round((pos.z - me.z) * zoom);
				// Friends in green, everyone else white.
				int dot = config.isFriend(player.getGameProfile().name()) ? 0xFF50FF70 : 0xFFFFFFFF;
				context.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF000000);
				context.fill(px - 1, pz - 1, px + 1, pz + 1, dot);
			}
		}
		if (freecam) {
			// Where you really are, as a hollow arrow, pinned to the edge when it's off the map.
			double px = (you.x - me.x) * zoom, pz = (you.z - me.z) * zoom, lim = reach - 4;
			double over = Math.max(Math.abs(px), Math.abs(pz)) / lim;
			if (over > 1 && !rotate) {
				px /= over;
				pz /= over;
			}
			TextRenderer f = client.textRenderer;
			matrices.pushMatrix();
			matrices.translate((float) px, (float) pz);
			matrices.rotate((float) Math.toRadians(playerYaw + 180));
			context.drawText(f, "△", -f.getWidth("△") / 2, -4, 0xFFFFD050, true);
			matrices.popMatrix();
		}
		matrices.popMatrix();

		// You, in the middle: pointing the way you face (always up when the map turns instead).
		TextRenderer font = client.textRenderer;
		matrices.pushMatrix();
		matrices.translate(x0 + size / 2f, y0 + size / 2f);
		if (!rotate) matrices.rotate((float) Math.toRadians(yaw + 180));
		context.drawText(font, "▲", -font.getWidth("▲") / 2, -4, freecam ? 0xFF7FD8FF : 0xFFFFFFFF, true);
		matrices.popMatrix();
		context.disableScissor();

		// North, on the edge of the map.
		double northAngle = turn - Math.PI / 2;
		double half = size / 2.0 - 5;
		double nx = Math.cos(northAngle), nz = Math.sin(northAngle);
		double edge = half / Math.max(Math.abs(nx), Math.abs(nz));
		int lx = (int) Math.round(x0 + size / 2.0 + nx * edge), ly = (int) Math.round(y0 + size / 2.0 + nz * edge);
		context.fill(lx - 4, ly - 5, lx + 4, ly + 5, 0xC0000000);
		context.drawTextWithShadow(font, "N", lx - font.getWidth("N") / 2 + 1, ly - 4, 0xFFFF5050);

		bottom = y1 + 2;
		lastCoords = null;
		if (config.minimapCoords) {
			int screen = context.getScaledWindowWidth();
			int y = y1 + 3;
			if (freecam) {
				lastCoords = new String[] {"Cam " + coords(me), "You " + coords(you)};
				y = coordsLine(context, font, lastCoords[0], x0, size, screen, y, 0xFF7FD8FF);
				y = coordsLine(context, font, lastCoords[1], x0, size, screen, y, 0xFFFFD050);
			} else {
				lastCoords = new String[] {coords(you)};
				y = coordsLine(context, font, lastCoords[0], x0, size, screen, y, 0xFFE0E0E0);
			}
			bottom = y;
		}
	}

	/** What the coordinates under the map said last frame (camera then you in freecam), for the self-test; null when off. */
	static volatile String[] lastCoords;
	/** Where the last coordinates line was drawn: {left, right} in GUI pixels, for the self-test. */
	static volatile int[] lastCoordsSpan = {0, 0};

	private static String coords(Vec3d p) {
		return String.format(Locale.ROOT, "%d, %d, %d", (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
	}

	/**
	 * One line of coordinates under the map, centred on it but kept inside the screen: far-out numbers wider than the map
	 * grow in from the screen edge instead of running off it. Returns the y below it.
	 */
	private static int coordsLine(DrawContext context, TextRenderer font, String text, int x0, int size, int screen, int y, int color) {
		int w = font.getWidth(text);
		int cx = clampLine(x0 + size / 2 - w / 2, w, screen);
		context.fill(cx - 3, y, cx + w + 3, y + 11, 0xA0000000);
		context.drawTextWithShadow(font, text, cx, y + 2, color);
		lastCoordsSpan = new int[] {cx - 3, cx + w + 3};
		return y + 11;
	}

	/** The left edge of a line {@code w} wide, moved in so its backing stays MARGIN clear of both screen edges. */
	static int clampLine(int left, int w, int screen) {
		return Math.max(MARGIN + 3, Math.min(left, screen - MARGIN - 3 - w));
	}

	/**
	 * Whether the camera has left you: freecam (Meteor and the like move the camera on its own), or watching another
	 * entity. Third person's camera sits up to a few blocks behind you, so only further than that counts there.
	 */
	static boolean detached(MinecraftClient client, Camera camera, float delta) {
		if (camera.getFocusedEntity() != null && camera.getFocusedEntity() != client.player) return true;
		double off = camera.getCameraPos().distanceTo(client.player.getCameraPosVec(delta));
		return client.options.getPerspective().isFirstPerson() ? off > 0.75 : off > 8.5;
	}

	/** How many rows of status-effect icons vanilla draws in the top-right corner (0–2). */
	private static int effectRows(MinecraftClient client) {
		boolean good = false, bad = false;
		for (StatusEffectInstance effect : client.player.getStatusEffects()) {
			if (!effect.shouldShowIcon()) continue;
			if (effect.getEffectType().value().isBeneficial()) good = true;
			else bad = true;
		}
		return bad ? 2 : good ? 1 : 0;
	}

	/** Zooms in (positive) or out (negative) by doubling steps; returns the new zoom. */
	public static double zoom(int steps) {
		BIConfig config = BIConfig.INSTANCE;
		config.minimapZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, config.minimapZoom * Math.pow(2, steps)));
		BIConfig.save();
		return config.minimapZoom;
	}
}
