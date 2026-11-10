package com.greysworkshop.baseinsurance;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2fStack;

/**
 * Draws explored terrain from the world map; shared by the map screen and the minimap. It picks the level of detail
 * that suits the zoom, and draws the next coarser level underneath first, so nothing is ever blank while the sharper
 * tiles are still being read: the map shows straight away and sharpens.
 */
public final class MapRender {
	private MapRender() {}

	/** The level for a zoom (screen pixels per block): 0 full tiles, 1 overview tiles, 2 to 4 far tiles. */
	public static int levelFor(double zoom) {
		if (zoom >= 0.5) return 0;
		if (zoom >= 1 / 8.0) return 1;
		if (zoom >= 1 / 64.0) return 2;
		if (zoom >= 1 / 512.0) return 3;
		return WorldMap.TOP_LEVEL;
	}

	/**
	 * Draws the terrain so that block (centerX, centerZ) lands on (originX, originY) in the current matrix space,
	 * covering at least {@code halfW}×{@code halfH} pixels each way from there.
	 */
	public static void drawTerrain(DrawContext context, double originX, double originY, double centerX, double centerZ,
			double zoom, double halfW, double halfH) {
		WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
		if (map.dimension() == null) return;
		map.beginFrame();
		int level = levelFor(zoom);
		// The coarser level first, as a stand-in wherever the sharper one isn't in yet.
		if (level < WorldMap.TOP_LEVEL) drawLevel(context, map, level + 1, originX, originY, centerX, centerZ, zoom, halfW, halfH);
		drawLevel(context, map, level, originX, originY, centerX, centerZ, zoom, halfW, halfH);
	}

	private static void drawLevel(DrawContext context, WorldMap map, int level, double originX, double originY, double centerX,
			double centerZ, double zoom, double halfW, double halfH) {
		long span = WorldMap.farBlocks(Math.max(1, level));
		int x0 = (int) Math.floorDiv((long) Math.floor(centerX - halfW / zoom), span);
		int x1 = (int) Math.floorDiv((long) Math.floor(centerX + halfW / zoom), span);
		int z0 = (int) Math.floorDiv((long) Math.floor(centerZ - halfH / zoom), span);
		int z1 = (int) Math.floorDiv((long) Math.floor(centerZ + halfH / zoom), span);
		Matrix3x2fStack matrices = context.getMatrices();
		// Nearest first, so when only a few can be read from disk this frame, the middle fills in first.
		int cx = (int) Math.floorDiv((long) Math.floor(centerX), span), cz = (int) Math.floorDiv((long) Math.floor(centerZ), span);
		int rings = Math.max(Math.max(cx - x0, x1 - cx), Math.max(cz - z0, z1 - cz));
		for (int ring = 0; ring <= rings; ring++) {
			for (int tx = cx - ring; tx <= cx + ring; tx++) {
				for (int tz = cz - ring; tz <= cz + ring; tz++) {
					if (Math.max(Math.abs(tx - cx), Math.abs(tz - cz)) != ring) continue;
					if (tx < x0 || tx > x1 || tz < z0 || tz > z1) continue;
					float x = (float) (originX + (tx * (double) span - centerX) * zoom);
					float y = (float) (originY + (tz * (double) span - centerZ) * zoom);
					net.minecraft.util.Identifier texture;
					int size;
					if (level == 0) {
						WorldMap.Tile tile = map.drawable(tx, tz);
						if (tile == null) continue;
						texture = map.texture(tile);
						size = WorldMap.TILE;
					} else if (level == 1) {
						WorldMap.Small small = map.drawableSmall(tx, tz);
						if (small == null) continue;
						texture = map.texture(small);
						size = WorldMap.SMALL;
					} else {
						WorldMap.Far far = map.drawableFar(level, tx, tz);
						if (far == null) continue;
						texture = map.texture(far);
						size = WorldMap.SMALL;
					}
					float scale = (float) (zoom * span / size);
					matrices.pushMatrix();
					matrices.translate(x, y);
					matrices.scale(scale, scale);
					context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0, size, size, size, size);
					matrices.popMatrix();
				}
			}
		}
	}
}
