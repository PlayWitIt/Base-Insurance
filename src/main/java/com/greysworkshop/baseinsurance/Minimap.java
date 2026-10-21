package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
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
	private static BIConfig.Corner corner = BIConfig.Corner.TOP_RIGHT;

	private Minimap() {}

	/** The y just below the minimap in {@code side}, or -1 if it isn't there. */
	public static int bottomIn(BIConfig.Corner side) {
		return side == corner ? bottom : -1;
	}

	public static void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		BIConfig config = BIConfig.INSTANCE;
		bottom = -1;
		if (!config.enabled || !config.minimap || client.world == null || client.player == null || client.options.hudHidden
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
		Vec3d me = client.player.getLerpedPos(delta);
		double zoom = config.minimapZoom;
		boolean rotate = config.minimapRotate;
		float yaw = client.player.getYaw(delta);
		// Rotating: turn the world so the way you face is up.
		float turn = rotate ? (float) Math.toRadians(-(yaw + 180)) : 0;

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
		matrices.popMatrix();

		// You, in the middle: pointing the way you face (always up when the map turns instead).
		TextRenderer font = client.textRenderer;
		matrices.pushMatrix();
		matrices.translate(x0 + size / 2f, y0 + size / 2f);
		if (!rotate) matrices.rotate((float) Math.toRadians(yaw + 180));
		context.drawText(font, "▲", -font.getWidth("▲") / 2, -4, 0xFFFFFFFF, true);
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
		if (config.minimapCoords) {
			var block = client.player.getBlockPos();
			String coords = String.format(Locale.ROOT, "%d, %d, %d", block.getX(), block.getY(), block.getZ());
			int w = font.getWidth(coords);
			int cx = x0 + size / 2 - w / 2;
			context.fill(cx - 3, y1 + 3, cx + w + 3, y1 + 14, 0xA0000000);
			context.drawTextWithShadow(font, coords, cx, y1 + 5, 0xFFE0E0E0);
			bottom = y1 + 14;
		}
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
