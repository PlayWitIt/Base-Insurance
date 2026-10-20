package com.greysworkshop.baseinsurance;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Draws a coloured box on every changed block: red = removed, orange = placed by someone, yellow = replaced. */
public final class Overlay {
	public static final int REMOVED = 0xFF3030;
	public static final int ADDED = 0xFF9900;
	public static final int REPLACED = 0xFFE030;
	/** Item frames, armor stands and paintings. */
	public static final int ENTITY = 0xE040FF;
	private static final int MAX_BOXES = 8000;
	private static final double GROW = 0.004;
	private static final VoxelShape SHAPE = VoxelShapes.cuboid(-GROW, -GROW, -GROW, 1 + GROW, 1 + GROW, 1 + GROW);

	public static boolean enabled;

	private record Box(double x, double y, double z, int rgb) {}

	private Overlay() {}

	/** Whether this kind of change is shown, per the settings. */
	public static boolean shown(Base.Kind kind) {
		BIConfig config = BIConfig.INSTANCE;
		return switch (kind) {
			case REMOVED -> config.showRemoved;
			case ADDED -> config.showAdded;
			case REPLACED -> config.showReplaced;
		};
	}

	public static int color(Base.Kind kind) {
		return switch (kind) {
			case REMOVED -> REMOVED;
			case ADDED -> ADDED;
			case REPLACED -> REPLACED;
		};
	}

	public static void render(WorldRenderContext context, BaseManager manager) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!enabled || client.world == null || !BIConfig.INSTANCE.enabled) return;
		Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
		double range = BIConfig.INSTANCE.overlayRange;
		double rangeSq = range * range;

		List<Box> boxes = new ArrayList<>();
		for (Base base : manager.inDimension(BaseManager.dimensionKey(client.world))) {
			if (base.damageCount() == 0 || base.distanceTo(camera.x, camera.y, camera.z) > range) continue;
			for (Map.Entry<Long, Base.Change> entry : base.changes.entrySet()) {
				long key = entry.getKey();
				double x = BlockPos.unpackLongX(key), y = BlockPos.unpackLongY(key), z = BlockPos.unpackLongZ(key);
				double dx = x + 0.5 - camera.x, dy = y + 0.5 - camera.y, dz = z + 0.5 - camera.z;
				if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
				Base.Kind kind = entry.getValue().kind();
				if (!shown(kind)) continue;
				boxes.add(new Box(x - camera.x, y - camera.y, z - camera.z, color(kind)));
				if (boxes.size() >= MAX_BOXES) break;
			}
			for (Base.EntityChange change : base.entityChanges.values()) {
				BlockPos pos = change.was.pos();
				if (camera.squaredDistanceTo(pos.toCenterPos()) > rangeSq) continue;
				boxes.add(new Box(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z, ENTITY));
			}
		}
		if (boxes.isEmpty()) return;

		MatrixStack matrices = context.matrices();
		VertexConsumer lines = context.consumers().getBuffer(RenderLayers.lines());
		for (Box box : boxes) {
			VertexRendering.drawOutline(matrices, lines, SHAPE, box.x, box.y, box.z, 0xFF000000 | box.rgb, BIConfig.INSTANCE.outlineWidth);
		}

		if (!BIConfig.INSTANCE.overlayFill) return;
		VertexConsumer quads = context.consumers().getBuffer(RenderLayers.debugQuads());
		MatrixStack.Entry entry = matrices.peek();
		int alpha = Math.round(BIConfig.INSTANCE.overlayOpacity * 2.55f) << 24;
		for (Box box : boxes) {
			fillCube(quads, entry, (float) box.x, (float) box.y, (float) box.z, alpha | box.rgb);
		}
	}

	private static void fillCube(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y, float z, int argb) {
		float g = (float) GROW;
		float x0 = x - g, y0 = y - g, z0 = z - g, x1 = x + 1 + g, y1 = y + 1 + g, z1 = z + 1 + g;
		// Bottom, top, north, south, west, east.
		quad(buffer, entry, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		quad(buffer, entry, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		quad(buffer, entry, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		quad(buffer, entry, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(buffer, entry, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(buffer, entry, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void quad(VertexConsumer buffer, MatrixStack.Entry entry, int argb,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		buffer.vertex(entry, ax, ay, az).color(argb);
		buffer.vertex(entry, bx, by, bz).color(argb);
		buffer.vertex(entry, cx, cy, cz).color(argb);
		buffer.vertex(entry, dx, dy, dz).color(argb);
	}
}
