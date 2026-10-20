package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A top-down map of everything you've explored, drawn from chunks as they load, in vanilla map colours with
 * hill shading. Stored per server and dimension as 512×512-block PNG tiles that only ever grow.
 */
public final class WorldMap {
	public static final int TILE = 512;
	private static final int MAX_LOADED_TILES = 32;
	private static final int SAVE_INTERVAL = 600;
	private static final int RESCAN_INTERVAL = 100;

	public static final class Tile {
		final int rx, rz;
		final NativeImage image;
		NativeImageBackedTexture texture;
		Identifier id;
		boolean dirty;
		boolean textureDirty = true;
		long lastUsed;

		Tile(int rx, int rz, NativeImage image) {
			this.rx = rx;
			this.rz = rz;
			this.image = image;
		}
	}

	private String worldKey;
	private String dimension;
	private final Map<Long, Tile> tiles = new HashMap<>();
	private final Set<Long> missing = new HashSet<>();
	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	private int tick;

	public String dimension() {
		return dimension;
	}

	public void onChunkLoad(ChunkPos pos) {
		if (queued.add(pos.toLong())) queue.add(pos.toLong());
	}

	public void tick(MinecraftClient client) {
		ClientWorld world = client.world;
		String key = BaseManager.worldKey(client);
		String dim = BaseManager.dimensionKey(world);
		if (!dim.equals(dimension) || key == null || !key.equals(worldKey)) {
			close();
			worldKey = key;
			dimension = dim;
			// Map everything that's already loaded.
			int view = client.options.getClampedViewDistance() + 1;
			ChunkPos center = client.player.getChunkPos();
			for (int dx = -view; dx <= view; dx++) {
				for (int dz = -view; dz <= view; dz++) onChunkLoad(new ChunkPos(center.x + dx, center.z + dz));
			}
		}
		if (key == null) return;

		tick++;
		// Re-map the chunks around you now and then, so building and digging show up.
		if (tick % RESCAN_INTERVAL == 0) {
			ChunkPos center = client.player.getChunkPos();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) onChunkLoad(new ChunkPos(center.x + dx, center.z + dz));
			}
		}
		if (!BIConfig.INSTANCE.recordMap) {
			queue.clear();
			queued.clear();
			return;
		}
		for (int i = 0; i < BIConfig.INSTANCE.mapSpeed && !queue.isEmpty(); i++) {
			long chunk = queue.poll();
			queued.remove(chunk);
			int cx = ChunkPos.getPackedX(chunk), cz = ChunkPos.getPackedZ(chunk);
			if (world.isChunkLoaded(cx, cz)) scan(world, world.getChunk(cx, cz));
		}
		if (tick % SAVE_INTERVAL == 0) save();
	}

	private void scan(ClientWorld world, WorldChunk chunk) {
		boolean ceiling = world.getDimension().hasCeiling();
		int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
		Tile tile = tile(Math.floorDiv(baseX, TILE), Math.floorDiv(baseZ, TILE), true);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int[] northHeights = new int[16];
		for (int x = 0; x < 16; x++) northHeights[x] = surface(world, chunk, baseX + x, baseZ - 1, ceiling, pos, true);

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int wx = baseX + x, wz = baseZ + z;
				int y = surface(world, chunk, wx, wz, ceiling, pos, false);
				int color;
				if (y == Integer.MIN_VALUE) {
					color = 0xFF000000;
				} else {
					BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
					FluidState fluid = state.getFluidState();
					MapColor mapColor = state.getMapColor(world, pos);
					MapColor.Brightness brightness;
					if (fluid.isIn(FluidTags.WATER)) {
						// Deeper water is darker, like vanilla maps.
						int depth = 0;
						while (depth < 12 && chunk.getBlockState(pos.set(wx, y - depth - 1, wz)).getFluidState().isIn(FluidTags.WATER)) depth++;
						brightness = depth > 8 ? MapColor.Brightness.LOW : depth > 3 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
					} else {
						int north = northHeights[x];
						brightness = north == Integer.MIN_VALUE || y == north ? MapColor.Brightness.NORMAL
								: y > north ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
					}
					color = mapColor == MapColor.CLEAR ? 0xFF000000 : mapColor.getRenderColor(brightness);
				}
				northHeights[x] = y;
				int px = Math.floorMod(wx, TILE), pz = Math.floorMod(wz, TILE);
				if (tile.image.getColorArgb(px, pz) != color) {
					tile.image.setColorArgb(px, pz, color);
					tile.dirty = true;
					tile.textureDirty = true;
				}
			}
		}
	}

	/** Y of the top visible block at x/z, or MIN_VALUE if unknown. {@code neighbour} allows other chunks. */
	private static int surface(ClientWorld world, WorldChunk chunk, int x, int z, boolean ceiling, BlockPos.Mutable pos, boolean neighbour) {
		WorldChunk source = chunk;
		if (neighbour) {
			if (!world.isChunkLoaded(x >> 4, z >> 4)) return Integer.MIN_VALUE;
			source = world.getChunk(x >> 4, z >> 4);
		}
		if (ceiling) {
			// Under a bedrock roof (the Nether), find the first floor below open air.
			boolean air = false;
			for (int y = Math.min(world.getTopYInclusive(), 120); y > world.getBottomY(); y--) {
				BlockState state = source.getBlockState(pos.set(x, y, z));
				if (state.isAir()) air = true;
				else if (air) return y;
			}
			return Integer.MIN_VALUE;
		}
		int y = source.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
		// Skip see-through blocks (glass, etc.) that have no map colour.
		for (int i = 0; i < 4 && y > world.getBottomY(); i++) {
			if (source.getBlockState(pos.set(x, y, z)).getMapColor(world, pos) != MapColor.CLEAR) return y;
			y--;
		}
		return y;
	}

	/** The tile at region rx/rz, loading it from disk; creates a blank one if {@code create}, else may return null. */
	public Tile tile(int rx, int rz, boolean create) {
		long key = ChunkPos.toLong(rx, rz);
		Tile tile = tiles.get(key);
		if (tile == null && (create || !missing.contains(key))) {
			Path file = file(rx, rz);
			NativeImage image = null;
			if (file != null && Files.exists(file)) {
				try (InputStream in = Files.newInputStream(file)) {
					image = NativeImage.read(in);
				} catch (IOException e) {
					BaseInsuranceClient.LOGGER.warn("Could not read map tile {}", file, e);
				}
			}
			if (image == null && !create) {
				missing.add(key);
				return null;
			}
			if (image == null) {
				image = new NativeImage(TILE, TILE, true);
				image.fillRect(0, 0, TILE, TILE, 0);
			}
			missing.remove(key);
			tile = new Tile(rx, rz, image);
			tiles.put(key, tile);
			evict();
		}
		if (tile != null) tile.lastUsed = System.nanoTime();
		return tile;
	}

	/** A texture for drawing the tile, uploaded if it has changed. */
	public Identifier texture(Tile tile) {
		if (tile.texture == null) {
			tile.texture = new NativeImageBackedTexture(() -> "baseinsurance map " + tile.rx + "," + tile.rz, tile.image);
			tile.id = Identifier.of(BaseInsuranceClient.MOD_ID, "map/" + Math.abs((worldKey + dimension).hashCode()) + "/" + tile.rx + "_" + tile.rz)
					.withPath(p -> p.replace('-', 'm'));
			MinecraftClient.getInstance().getTextureManager().registerTexture(tile.id, tile.texture);
			tile.textureDirty = false;
		} else if (tile.textureDirty) {
			tile.texture.upload();
			tile.textureDirty = false;
		}
		return tile.id;
	}

	/** Colour of the map at a block, or 0 if unexplored. */
	public int colorAt(int x, int z) {
		Tile tile = tile(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE), false);
		return tile == null ? 0 : tile.image.getColorArgb(Math.floorMod(x, TILE), Math.floorMod(z, TILE));
	}

	private void evict() {
		while (tiles.size() > MAX_LOADED_TILES) {
			Tile oldest = null;
			for (Tile tile : tiles.values()) {
				if (oldest == null || tile.lastUsed < oldest.lastUsed) oldest = tile;
			}
			unload(oldest);
			tiles.remove(ChunkPos.toLong(oldest.rx, oldest.rz));
		}
	}

	private void unload(Tile tile) {
		saveTile(tile);
		if (tile.texture != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(tile.id);
		} else {
			tile.image.close();
		}
	}

	public void save() {
		for (Tile tile : tiles.values()) saveTile(tile);
	}

	private void saveTile(Tile tile) {
		if (!tile.dirty) return;
		Path file = file(tile.rx, tile.rz);
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			tile.image.writeTo(file);
			tile.dirty = false;
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not save map tile {}", file, e);
		}
	}

	/** Saves and frees everything; called when leaving a world or dimension. */
	public void close() {
		Iterator<Tile> it = tiles.values().iterator();
		while (it.hasNext()) {
			unload(it.next());
			it.remove();
		}
		missing.clear();
		queue.clear();
		queued.clear();
		worldKey = null;
		dimension = null;
	}

	private Path file(int rx, int rz) {
		if (worldKey == null || dimension == null) return null;
		return BIConfig.dir().resolve(worldKey.replaceAll("[^A-Za-z0-9._-]", "_")).resolve("map")
				.resolve(dimension.replaceAll("[^A-Za-z0-9._-]", "_")).resolve("r." + rx + "." + rz + ".png");
	}
}
