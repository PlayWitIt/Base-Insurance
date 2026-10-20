package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Walks you to changed blocks picked in the report: on-screen markers drawn over everything (so they show
 * through walls), pinned to the screen edge with an arrow when off-screen, and a strip with the nearest one's
 * direction and distance. Markers clear as you reach them.
 */
public final class DamageLocator {
	private static final int COLOR = 0xFFFF5050;
	private static final double REACHED = 4.5;
	/** Only the nearest few get a marker, so a big crater doesn't fill the screen. */
	private static final int MAX_MARKERS = 12;
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private static final List<BlockPos> targets = new ArrayList<>();
	private static String dimension;
	private static String what = "";
	private static ItemStack icon = ItemStack.EMPTY;
	private static long doneUntil;

	private DamageLocator() {}

	public static void guide(String dimension, List<BlockPos> positions, String what, ItemStack icon) {
		targets.clear();
		targets.addAll(positions);
		DamageLocator.dimension = dimension;
		DamageLocator.what = what;
		DamageLocator.icon = icon;
		doneUntil = 0;
	}

	public static void stop() {
		targets.clear();
	}

	public static boolean active() {
		return !targets.isEmpty();
	}

	public static void tick(MinecraftClient client) {
		if (targets.isEmpty() || client.player == null || client.world == null) return;
		if (!BaseManager.dimensionKey(client.world).equals(dimension)) return;
		Vec3d eye = client.player.getEyePos();
		if (targets.removeIf(pos -> eye.distanceTo(Vec3d.ofCenter(pos)) <= REACHED) && targets.isEmpty()) {
			doneUntil = System.currentTimeMillis() + 3000;
		}
	}

	public static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.options.hudHidden) return;
		TextRenderer font = client.textRenderer;
		int centerX = context.getScaledWindowWidth() / 2;
		if (targets.isEmpty()) {
			if (System.currentTimeMillis() < doneUntil) pill(context, font, Text.literal("✔ All visited"), centerX, 0xFF55FF55);
			return;
		}
		if (!BaseManager.dimensionKey(client.world).equals(dimension)) {
			pill(context, font, Text.literal(what + " is in another dimension"), centerX, 0xFFFFAA00);
			return;
		}

		Vec3d eye = client.player.getEyePos();
		List<BlockPos> sorted = new ArrayList<>(targets);
		sorted.sort(Comparator.comparingDouble(pos -> eye.squaredDistanceTo(Vec3d.ofCenter(pos))));
		drawMarkers(context, client, font, sorted.subList(0, Math.min(MAX_MARKERS, sorted.size())));

		BlockPos nearest = sorted.get(0);
		Vec3d to = Vec3d.ofCenter(nearest).subtract(eye);
		float relative = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(-to.x, to.z)) - client.player.getYaw());
		String arrow = ARROWS[Math.floorMod(Math.round(relative / 45f), 8)];
		String vertical = to.y > 3 ? "  ▲" + (int) to.y : to.y < -3 ? "  ▼" + (int) -to.y : "";
		String more = targets.size() > 1 ? "  (" + targets.size() + " left)" : "";
		pill(context, font, Text.literal(arrow + "  ").append(Text.literal(what).styled(s -> s.withColor(0xFFFFFF)))
				.append(Text.literal("  " + (int) Math.round(to.length()) + "m" + vertical + more).styled(s -> s.withColor(0xBBBBBB))), centerX, COLOR);
	}

	private static void pill(DrawContext context, TextRenderer font, Text text, int centerX, int accent) {
		int iconSpace = icon.isEmpty() ? 0 : 18;
		int width = font.getWidth(text) + iconSpace + 12;
		int x0 = centerX - width / 2, x1 = x0 + width, y = 28, y1 = y + 20;
		context.fill(x0 + 1, y, x1 - 1, y1, 0xC0101018);
		context.fill(x0, y + 1, x1, y1 - 1, 0xC0101018);
		context.fill(x0 + 1, y1 - 1, x1 - 1, y1, accent);
		int x = x0 + 6;
		if (!icon.isEmpty()) {
			context.drawItem(icon, x, y + 2);
			x += iconSpace;
		}
		context.drawTextWithShadow(font, text, x, y + 6, accent);
	}

	/** Projects each target to the screen with the camera's own maths, pinning off-screen ones to the edge. */
	private static void drawMarkers(DrawContext context, MinecraftClient client, TextRenderer font, List<BlockPos> shown) {
		Camera camera = client.gameRenderer.getCamera();
		Vec3d eye = camera.getCameraPos();
		double yaw = Math.toRadians(camera.getYaw()), pitch = Math.toRadians(camera.getPitch());
		Vec3d forward = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3d up = right.crossProduct(forward);
		double tanHalfFov = Math.tan(Math.toRadians(client.options.getFov().getValue()) / 2);
		int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
		double halfW = w / 2.0, halfH = h / 2.0;
		int margin = 18;
		for (int i = 0; i < shown.size(); i++) {
			BlockPos pos = shown.get(i);
			Vec3d d = Vec3d.ofCenter(pos).subtract(eye);
			double cx = d.dotProduct(right), cy = d.dotProduct(up), cz = d.dotProduct(forward);
			double sx, sy;
			boolean onScreen;
			if (cz > 0.05) {
				sx = halfW + cx / cz / tanHalfFov * halfH;
				sy = halfH - cy / cz / tanHalfFov * halfH;
				onScreen = sx >= margin && sx <= w - margin && sy >= margin && sy <= h - margin;
			} else {
				sx = halfW + (Math.abs(cx) + Math.abs(cy) < 1e-3 ? 0 : cx) * 1e4;
				sy = halfH + (Math.abs(cx) + Math.abs(cy) < 1e-3 ? 1 : -cy) * 1e4;
				onScreen = false;
			}
			double angle = Math.atan2(sy - halfH, sx - halfW);
			if (!onScreen) {
				double dx = sx - halfW, dy = sy - halfH;
				double scale = Math.min((halfW - margin) / Math.max(1e-6, Math.abs(dx)), (halfH - margin) / Math.max(1e-6, Math.abs(dy)));
				sx = halfW + dx * scale;
				sy = MathHelper.clamp(halfH + dy * scale, 56, h - 56);
			}
			boolean main = i == 0;
			int x = (int) Math.round(sx), y = (int) Math.round(sy);
			int size = main ? 7 : 5;
			int color = main ? COLOR : 0xB0FF5050;
			// A diamond-ish marker: a filled square with a bright border.
			context.fill(x - size, y - size, x + size, y + size, main ? 0xB0200808 : 0x80200808);
			context.drawStrokedRectangle(x - size, y - size, size * 2, size * 2, color);
			context.fill(x - 1, y - 1, x + 1, y + 1, color);
			if (main) {
				String distance = (int) Math.round(d.length()) + "m";
				int tw = font.getWidth(distance);
				context.fill(x - tw / 2 - 2, y + size + 2, x + tw / 2 + 2, y + size + 12, 0xA0000000);
				context.drawTextWithShadow(font, distance, x - tw / 2, y + size + 3, 0xFFFFFFFF);
			}
			if (!onScreen) {
				Matrix3x2fStack matrices = context.getMatrices();
				matrices.pushMatrix();
				matrices.translate((float) (x - Math.cos(angle) * (size + 7)), (float) (y - Math.sin(angle) * (size + 7)));
				matrices.rotate((float) (angle + Math.PI / 2));
				context.drawText(font, "▲", -font.getWidth("▲") / 2, -4, color, true);
				matrices.popMatrix();
			}
		}
	}
}
