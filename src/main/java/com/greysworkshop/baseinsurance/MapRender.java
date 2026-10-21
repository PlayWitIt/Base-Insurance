package com.greysworkshop.baseinsurance;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2fStack;

/** Draws explored terrain from the world map; shared by the map screen and the minimap. */
public final class MapRender {
	/** Below this many screen pixels per block, the quarter-size overview tiles are used. */
	private static final double OVERVIEW_BELOW = 0.5;

	private MapRender() {}

	/**
	 * Draws the terrain so that block (centerX, centerZ) lands on (originX, originY) in the current matrix space,
	 * covering at least {@code halfW}×{@code halfH} pixels each way from there.
	 */
	public static void drawTerrain(DrawContext context, double originX, double originY, double centerX, double centerZ,
			double zoom, double halfW, double halfH) {
		WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
		if (map.dimension() == null) return;
		map.beginFrame();
		int rx0 = Math.floorDiv((int) Math.floor(centerX - halfW / zoom), WorldMap.TILE);
		int rx1 = Math.floorDiv((int) Math.floor(centerX + halfW / zoom), WorldMap.TILE);
		int rz0 = Math.floorDiv((int) Math.floor(centerZ - halfH / zoom), WorldMap.TILE);
		int rz1 = Math.floorDiv((int) Math.floor(centerZ + halfH / zoom), WorldMap.TILE);
		boolean overview = zoom < OVERVIEW_BELOW;
		Matrix3x2fStack matrices = context.getMatrices();
		// Nearest tiles first, so when only a few can be read from disk this frame, the middle fills in first.
		int crx = Math.floorDiv((int) Math.floor(centerX), WorldMap.TILE), crz = Math.floorDiv((int) Math.floor(centerZ), WorldMap.TILE);
		int rings = Math.max(Math.max(crx - rx0, rx1 - crx), Math.max(crz - rz0, rz1 - crz));
		for (int ring = 0; ring <= rings; ring++) {
			for (int rx = crx - ring; rx <= crx + ring; rx++) {
				for (int rz = crz - ring; rz <= crz + ring; rz++) {
					if (Math.max(Math.abs(rx - crx), Math.abs(rz - crz)) != ring) continue;
					if (rx < rx0 || rx > rx1 || rz < rz0 || rz > rz1) continue;
					float x = (float) (originX + (rx * WorldMap.TILE - centerX) * zoom);
					float y = (float) (originY + (rz * WorldMap.TILE - centerZ) * zoom);
					WorldMap.Tile tile = overview ? null : map.drawable(rx, rz);
					if (tile != null) {
						matrices.pushMatrix();
						matrices.translate(x, y);
						matrices.scale((float) zoom, (float) zoom);
						context.drawTexture(RenderPipelines.GUI_TEXTURED, map.texture(tile), 0, 0, 0, 0,
								WorldMap.TILE, WorldMap.TILE, WorldMap.TILE, WorldMap.TILE);
						matrices.popMatrix();
						continue;
					}
					WorldMap.Small small = map.drawableSmall(rx, rz);
					if (small == null) continue;
					float scale = (float) (zoom * WorldMap.TILE / WorldMap.SMALL);
					matrices.pushMatrix();
					matrices.translate(x, y);
					matrices.scale(scale, scale);
					context.drawTexture(RenderPipelines.GUI_TEXTURED, map.texture(small), 0, 0, 0, 0,
							WorldMap.SMALL, WorldMap.SMALL, WorldMap.SMALL, WorldMap.SMALL);
					matrices.popMatrix();
				}
			}
		}
	}
}
