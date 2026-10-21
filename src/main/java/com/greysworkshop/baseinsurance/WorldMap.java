package com.greysworkshop.baseinsurance;

import com.mojang.blaze3d.systems.RenderSystem;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A top-down map of everything you've explored, drawn from chunks as they load, in vanilla map colours with
 * hill shading. Stored per server and dimension as 512×512-block PNG tiles that only ever grow, plus a
 * quarter-size copy of each for drawing the map zoomed out.
 */
public final class WorldMap {
	public static final int TILE = 512;
	/** Overview tiles are this many pixels across: one pixel per 4×4 blocks. */
	public static final int SMALL = 128;
	private static final int SMALL_SCALE = TILE / SMALL;
	/** Full tiles kept in memory (1 MB each); more are allowed while they're all on screen. */
	private static final int MAX_TILES = 40;
	/** Overview tiles kept in memory (64 KB each). */
	private static final int MAX_SMALL = 640;
	/** Overview tiles made from tiles in memory per frame (each takes about a millisecond). */
	private static final int LOADS_PER_FRAME = 3;
	/** Tiles loaded in the background taken in per frame (each makes a texture). */
	private static final int ACCEPT_PER_FRAME = 16;
	/** Background loads waiting at once; the nearest tiles are asked for first, so they come first. */
	private static final int MAX_PENDING = 48;
	/** Reads and shrinks tiles off the render thread, so a big map never stutters. */
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Base Insurance map loader");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	/** Tiles drawn in the last this-many ms are never unloaded. */
	private static final long IN_USE_NANOS = 1_000_000_000L;
	/** An overview tile is redrawn from its full tile at most this often while you explore. */
	private static final long SMALL_REFRESH_NANOS = 2_000_000_000L;
	private static final int SAVE_INTERVAL = 600;
	private static final int RESCAN_INTERVAL = 100;
	/** How often loaded chunks that were never mapped (say, ones that arrived while joining) are looked for. */
	private static final int SWEEP_INTERVAL = 40;

	/** What the map has done since you joined, for {@code /bi map status}. */
	public static final class Stats {
		public int chunksMapped;
		/** Chunks that came out with nothing to draw: all air, or their blocks never arrived. */
		public int emptyChunks;
		/** Columns where the server's surface height was wrong, so the map found the surface itself. */
		public int heightFixed;
		public int sweptIn;
	}

	public static final class Tile {
		final int rx, rz;
		final NativeImage image;
		NativeImageBackedTexture texture;
		Identifier id;
		/** Changed since last saved. */
		boolean dirty;
		/** The part changed since the texture was last uploaded; empty when minX > maxX. */
		int upMinX = TILE, upMinZ = TILE, upMaxX = -1, upMaxZ = -1;
		long lastUsed;

		Tile(int rx, int rz, NativeImage image) {
			this.rx = rx;
			this.rz = rz;
			this.image = image;
		}

		void changed(int px, int pz) {
			dirty = true;
			upMinX = Math.min(upMinX, px);
			upMaxX = Math.max(upMaxX, px);
			upMinZ = Math.min(upMinZ, pz);
			upMaxZ = Math.max(upMaxZ, pz);
		}
	}

	/** A quarter-size copy of a tile, for drawing zoomed out. */
	public static final class Small {
		final int rx, rz;
		final NativeImage image;
		NativeImageBackedTexture texture;
		Identifier id;
		/** Its full tile has changed since it was made. */
		boolean stale;
		boolean textureStale;
		long made;
		long lastUsed;

		Small(int rx, int rz, NativeImage image) {
			this.rx = rx;
			this.rz = rz;
			this.image = image;
			this.made = System.nanoTime();
		}
	}

	private String worldKey;
	private String dimension;
	private final Map<Long, Tile> tiles = new HashMap<>();
	private final Map<Long, Small> smalls = new HashMap<>();
	/** Which tiles and overview tiles exist on disk; read from the folder once, then kept up to date. */
	private Set<Long> onDisk;
	private Set<Long> smallOnDisk;
	/** Tiles changed since their overview copy was last written to disk. */
	private final Set<Long> smallUnsaved = new HashSet<>();
	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	/** Loaded chunks mapped at least once since they loaded. */
	private final Set<Long> mapped = new HashSet<>();
	public final Stats stats = new Stats();
	private final Set<Long> pendingFull = new HashSet<>();
	private final Set<Long> pendingSmall = new HashSet<>();
	private final ConcurrentLinkedQueue<Loaded> finished = new ConcurrentLinkedQueue<>();
	/** Goes up whenever the map is closed, so loads still running for the old world are thrown away. */
	private int generation;
	private int tick;
	private int loadsLeft = LOADS_PER_FRAME;
	private long frameNanos;

	public String dimension() {
		return dimension;
	}

	public int queueSize() {
		return queue.size();
	}

	public int loadedTiles() {
		return tiles.size();
	}

	public int loadedSmallTiles() {
		return smalls.size();
	}

	public boolean hasSmall(int rx, int rz) {
		return smalls.containsKey(ChunkPos.toLong(rx, rz));
	}

	public void onChunkLoad(ChunkPos pos) {
		if (queued.add(pos.toLong())) queue.add(pos.toLong());
	}

	public void onChunkUnload(ChunkPos pos) {
		mapped.remove(pos.toLong());
	}

	/** How many tiles of this world and dimension are saved. */
	public int tilesOnDisk() {
		return worldKey == null ? 0 : onDisk().size();
	}

	public Path folderPath() {
		return folder();
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
		// Catch any loaded chunk that was missed: ones that arrived while joining, before we knew which world this was.
		if (tick % SWEEP_INTERVAL == 0 && queue.isEmpty()) {
			int view = client.options.getClampedViewDistance() + 1;
			ChunkPos center = client.player.getChunkPos();
			for (int dx = -view; dx <= view; dx++) {
				for (int dz = -view; dz <= view; dz++) {
					int cx = center.x + dx, cz = center.z + dz;
					if (!mapped.contains(ChunkPos.toLong(cx, cz)) && world.isChunkLoaded(cx, cz)) {
						onChunkLoad(new ChunkPos(cx, cz));
						stats.sweptIn++;
					}
				}
			}
		}
		if (!BIConfig.INSTANCE.recordMap) {
			queue.clear();
			queued.clear();
		}
		for (int i = 0; i < BIConfig.INSTANCE.mapSpeed && !queue.isEmpty(); i++) {
			long chunk = queue.poll();
			queued.remove(chunk);
			int cx = ChunkPos.getPackedX(chunk), cz = ChunkPos.getPackedZ(chunk);
			if (world.isChunkLoaded(cx, cz)) {
				// A chunk with nothing to draw may just not have its blocks yet; the sweep tries it again later.
				if (scan(world, world.getChunk(cx, cz))) mapped.add(chunk);
			}
		}
		evict();
		if (tick % SAVE_INTERVAL == 0) save();
	}

	/** Draws a chunk onto the map; false if there was nothing to draw. */
	private boolean scan(ClientWorld world, WorldChunk chunk) {
		boolean ceiling = world.getDimension().hasCeiling();
		int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
		Tile tile = tile(Math.floorDiv(baseX, TILE), Math.floorDiv(baseZ, TILE), true);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int[] northHeights = new int[16];
		for (int x = 0; x < 16; x++) northHeights[x] = surface(world, chunk, baseX + x, baseZ - 1, ceiling, pos, true);

		boolean changed = false;
		boolean drew = false;
		stats.chunksMapped++;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int wx = baseX + x, wz = baseZ + z;
				int y = surface(world, chunk, wx, wz, ceiling, pos, false);
				int color;
				if (y == Integer.MIN_VALUE && !ceiling) {
					// Nothing here (or the chunk's blocks never arrived): leave it unexplored rather than paint over
					// what was mapped before.
					northHeights[x] = Integer.MIN_VALUE;
					continue;
				} else if (y == Integer.MIN_VALUE) {
					color = 0xFF000000;
				} else {
					BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
					if (state.isAir()) {
						// Nothing here: the void, or a chunk whose blocks haven't arrived yet. Leave it unexplored.
						northHeights[x] = Integer.MIN_VALUE;
						continue;
					}
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
				drew = true;
				int px = Math.floorMod(wx, TILE), pz = Math.floorMod(wz, TILE);
				if (tile.image.getColorArgb(px, pz) != color) {
					tile.image.setColorArgb(px, pz, color);
					tile.changed(px, pz);
					changed = true;
				}
			}
		}
		if (!drew) stats.emptyChunks++;
		if (changed) {
			long key = ChunkPos.toLong(tile.rx, tile.rz);
			smallUnsaved.add(key);
			Small small = smalls.get(key);
			if (small != null) small.stale = true;
		}
		return drew;
	}

	/** Y of the top visible block at x/z, or MIN_VALUE if unknown. {@code neighbour} allows other chunks. */
	private int surface(ClientWorld world, WorldChunk chunk, int x, int z, boolean ceiling, BlockPos.Mutable pos, boolean neighbour) {
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
		int bottom = world.getBottomY();
		int y = source.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15);
		// The height the server sent is only a hint: servers on older versions (through ViaVersion) and some server
		// software send it empty or wrong, which would leave the whole map blank. Check it, and look for ourselves if
		// it's off.
		boolean trusted = y > bottom && y <= world.getTopYInclusive() && !source.getBlockState(pos.set(x, y, z)).isAir()
				&& (y == world.getTopYInclusive() || source.getBlockState(pos.set(x, y + 1, z)).isAir());
		if (!trusted) {
			int top = source.getHighestNonEmptySectionYOffset();
			if (source.getHighestNonEmptySection() < 0) return Integer.MIN_VALUE;
			y = Math.min(world.getTopYInclusive(), top + 15);
			while (y > bottom && source.getBlockState(pos.set(x, y, z)).isAir()) y--;
			if (y <= bottom) return Integer.MIN_VALUE;
			if (!neighbour) stats.heightFixed++;
		}
		// Skip see-through blocks (glass, etc.) that have no map colour.
		for (int i = 0; i < 4 && y > bottom; i++) {
			if (source.getBlockState(pos.set(x, y, z)).getMapColor(world, pos) != MapColor.CLEAR) return y;
			y--;
		}
		return y;
	}

	// ---- Tiles ----

	/** The tile at region rx/rz, loading it from disk; creates a blank one if {@code create}, else may return null. */
	public Tile tile(int rx, int rz, boolean create) {
		long key = ChunkPos.toLong(rx, rz);
		Tile tile = tiles.get(key);
		if (tile == null && (create || onDisk().contains(key))) {
			NativeImage image = onDisk().contains(key) ? sized(read(file(rx, rz)), TILE) : null;
			if (image == null && !create) {
				onDisk().remove(key);
				return null;
			}
			if (image == null) {
				image = new NativeImage(TILE, TILE, true);
				image.fillRect(0, 0, TILE, TILE, 0);
			}
			tile = new Tile(rx, rz, image);
			tiles.put(key, tile);
		}
		if (tile != null) tile.lastUsed = System.nanoTime();
		return tile;
	}

	/**
	 * Call once per frame before drawing tiles: takes in tiles finished loading in the background, and resets the
	 * per-frame budget for work done here on the render thread.
	 */
	public void beginFrame() {
		long now = System.nanoTime();
		// The minimap and the map screen can both draw in one frame; they share one budget.
		if (now - frameNanos < 5_000_000L) return;
		frameNanos = now;
		loadsLeft = LOADS_PER_FRAME;
		for (int i = 0; i < ACCEPT_PER_FRAME; i++) {
			Loaded loaded = finished.poll();
			if (loaded == null) break;
			accept(loaded);
		}
	}

	/** A full tile for drawing, or null if there's none (or it's still loading). */
	public Tile drawable(int rx, int rz) {
		long key = ChunkPos.toLong(rx, rz);
		Tile tile = tiles.get(key);
		if (tile != null) {
			tile.lastUsed = System.nanoTime();
			return tile;
		}
		if (onDisk().contains(key)) load(key, false);
		return null;
	}

	/** An overview tile for drawing, or null if there's none (or it's still being loaded or made). */
	public Small drawableSmall(int rx, int rz) {
		long key = ChunkPos.toLong(rx, rz);
		long now = System.nanoTime();
		Small small = smalls.get(key);
		Tile tile = tiles.get(key);
		if (small != null) {
			small.lastUsed = now;
			// Keep it up to date as you explore, from the full tile (which is in memory while being drawn into).
			if (small.stale && tile != null && now - small.made > SMALL_REFRESH_NANOS && loadsLeft > 0) {
				loadsLeft--;
				shrink(tile.image, small.image);
				small.stale = false;
				small.textureStale = true;
				small.made = now;
			}
			return small;
		}
		if (tile != null) {
			// Quick to make from a tile in memory.
			if (loadsLeft <= 0) return null;
			loadsLeft--;
			NativeImage image = blankSmall();
			shrink(tile.image, image);
			small = new Small(rx, rz, image);
			small.lastUsed = now;
			smalls.put(key, small);
			return small;
		}
		// Unexplored places cost nothing.
		if ((smallOnDisk().contains(key) && !smallUnsaved.contains(key)) || onDisk().contains(key)) load(key, true);
		return null;
	}

	// ---- Loading in the background ----

	/** A tile read (or an overview tile made) on the loader thread, waiting to be taken in on the render thread. */
	private record Loaded(long key, boolean small, NativeImage image, boolean madeSmall, int generation) {}

	/** Starts reading a tile or overview tile from disk in the background, unless it's already on its way. */
	private void load(long key, boolean small) {
		Set<Long> pending = small ? pendingSmall : pendingFull;
		if (pending.contains(key) || pendingFull.size() + pendingSmall.size() >= MAX_PENDING) return;
		int rx = ChunkPos.getPackedX(key), rz = ChunkPos.getPackedZ(key);
		Path full = file(rx, rz);
		// Overview tiles from before they existed are made from the full tile, and saved for next time.
		Path smallPath = small && smallOnDisk().contains(key) && !smallUnsaved.contains(key) ? smallFile(rx, rz) : null;
		Path saveSmall = small && smallPath == null ? smallFile(rx, rz) : null;
		int gen = generation;
		pending.add(key);
		LOADER.execute(() -> {
			NativeImage image = null;
			boolean made = false;
			try {
				if (!small) {
					image = sized(read(full), TILE);
				} else if (smallPath != null) {
					image = sized(read(smallPath), SMALL);
				} else {
					NativeImage fullImage = sized(read(full), TILE);
					if (fullImage != null) {
						image = blankSmall();
						shrink(fullImage, image);
						fullImage.close();
						writeImage(saveSmall, image);
						made = true;
					}
				}
			} catch (RuntimeException e) {
				BaseInsuranceClient.LOGGER.warn("Could not load map tile {}", full, e);
			}
			finished.add(new Loaded(key, small, image, made, gen));
		});
	}

	/** Takes in a finished background load, on the render thread. */
	private void accept(Loaded loaded) {
		long key = loaded.key();
		NativeImage image = loaded.image();
		if (loaded.generation() != generation) {
			// From a world or dimension we've since left.
			if (image != null) image.close();
			return;
		}
		(loaded.small() ? pendingSmall : pendingFull).remove(key);
		int rx = ChunkPos.getPackedX(key), rz = ChunkPos.getPackedZ(key);
		if (image == null) {
			// Unreadable: forget it's there rather than trying again every frame.
			(loaded.small() ? smallOnDisk() : onDisk()).remove(key);
			return;
		}
		if (loaded.madeSmall()) smallOnDisk().add(key);
		long now = System.nanoTime();
		if (loaded.small()) {
			if (smalls.containsKey(key)) {
				image.close();
				return;
			}
			Small small = new Small(rx, rz, image);
			small.lastUsed = now;
			smalls.put(key, small);
		} else {
			// Mapping may have loaded it meanwhile; that copy wins, as it may already have new parts drawn in.
			if (tiles.containsKey(key)) {
				image.close();
				return;
			}
			Tile tile = new Tile(rx, rz, image);
			tile.lastUsed = now;
			tiles.put(key, tile);
		}
	}

	private static NativeImage sized(NativeImage image, int size) {
		if (image != null && (image.getWidth() != size || image.getHeight() != size)) {
			image.close();
			return null;
		}
		return image;
	}

	private static NativeImage blankSmall() {
		NativeImage image = new NativeImage(SMALL, SMALL, true);
		image.fillRect(0, 0, SMALL, SMALL, 0);
		return image;
	}

	/** Averages each 4×4 block of the full tile into one pixel; unexplored pixels don't count. */
	private static void shrink(NativeImage full, NativeImage small) {
		for (int sz = 0; sz < SMALL; sz++) {
			for (int sx = 0; sx < SMALL; sx++) {
				int r = 0, g = 0, b = 0, n = 0;
				for (int dz = 0; dz < SMALL_SCALE; dz++) {
					for (int dx = 0; dx < SMALL_SCALE; dx++) {
						int c = full.getColorArgb(sx * SMALL_SCALE + dx, sz * SMALL_SCALE + dz);
						if ((c >>> 24) == 0) continue;
						r += (c >> 16) & 0xFF;
						g += (c >> 8) & 0xFF;
						b += c & 0xFF;
						n++;
					}
				}
				// Mostly-unexplored blocks stay see-through, so the edge of the map stays crisp.
				small.setColorArgb(sx, sz, n < SMALL_SCALE * SMALL_SCALE / 4 ? 0 : 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n));
			}
		}
	}

	/** A texture for drawing the tile, updated with whatever has changed since it was last drawn. */
	public Identifier texture(Tile tile) {
		if (tile.texture == null) {
			tile.texture = new NativeImageBackedTexture(() -> "baseinsurance map " + tile.rx + "," + tile.rz, tile.image);
			tile.id = textureId("", tile.rx, tile.rz);
			MinecraftClient.getInstance().getTextureManager().registerTexture(tile.id, tile.texture);
		} else if (tile.upMinX <= tile.upMaxX) {
			int w = tile.upMaxX - tile.upMinX + 1, h = tile.upMaxZ - tile.upMinZ + 1;
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(tile.texture.getGlTexture(), tile.image,
					0, 0, tile.upMinX, tile.upMinZ, w, h, tile.upMinX, tile.upMinZ);
		}
		tile.upMinX = tile.upMinZ = TILE;
		tile.upMaxX = tile.upMaxZ = -1;
		return tile.id;
	}

	public Identifier texture(Small small) {
		if (small.texture == null) {
			small.texture = new NativeImageBackedTexture(() -> "baseinsurance map overview " + small.rx + "," + small.rz, small.image);
			small.id = textureId("small/", small.rx, small.rz);
			MinecraftClient.getInstance().getTextureManager().registerTexture(small.id, small.texture);
			small.textureStale = false;
		} else if (small.textureStale) {
			small.texture.upload();
			small.textureStale = false;
		}
		return small.id;
	}

	private Identifier textureId(String kind, int rx, int rz) {
		return Identifier.of(BaseInsuranceClient.MOD_ID, ("map/" + Math.abs((worldKey + dimension).hashCode()) + "/" + kind + rx + "_" + rz)
				.replace('-', 'm'));
	}

	/** Colour of the map at a block, or 0 if unexplored. */
	public int colorAt(int x, int z) {
		Tile tile = tile(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE), false);
		return tile == null ? 0 : tile.image.getColorArgb(Math.floorMod(x, TILE), Math.floorMod(z, TILE));
	}

	/** Unloads the least recently drawn tiles beyond the limits. Runs on the game tick, never mid-frame. */
	private void evict() {
		long now = System.nanoTime();
		if (tiles.size() > MAX_TILES) {
			List<Tile> old = new ArrayList<>(tiles.values());
			old.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
			for (int i = 0; i < old.size() - MAX_TILES && now - old.get(i).lastUsed > IN_USE_NANOS; i++) {
				Tile tile = old.get(i);
				unload(tile);
				tiles.remove(ChunkPos.toLong(tile.rx, tile.rz));
			}
		}
		if (smalls.size() > MAX_SMALL) {
			List<Small> old = new ArrayList<>(smalls.values());
			old.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
			for (int i = 0; i < old.size() - MAX_SMALL && now - old.get(i).lastUsed > IN_USE_NANOS; i++) {
				Small small = old.get(i);
				unload(small);
				smalls.remove(ChunkPos.toLong(small.rx, small.rz));
			}
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

	private void unload(Small small) {
		if (small.texture != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(small.id);
		} else {
			small.image.close();
		}
	}

	public void save() {
		for (Tile tile : tiles.values()) saveTile(tile);
	}

	private void saveTile(Tile tile) {
		long key = ChunkPos.toLong(tile.rx, tile.rz);
		if (smallUnsaved.remove(key)) {
			Small small = smalls.get(key);
			NativeImage image = small != null ? small.image : blankSmall();
			shrink(tile.image, image);
			if (small != null) {
				small.stale = false;
				small.textureStale = true;
				small.made = System.nanoTime();
			}
			writeSmall(tile.rx, tile.rz, image);
			if (small == null) image.close();
		}
		if (!tile.dirty) return;
		Path file = file(tile.rx, tile.rz);
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			tile.image.writeTo(file);
			tile.dirty = false;
			onDisk().add(key);
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not save map tile {}", file, e);
		}
	}

	private void writeSmall(int rx, int rz, NativeImage image) {
		if (writeImage(smallFile(rx, rz), image)) smallOnDisk().add(ChunkPos.toLong(rx, rz));
	}

	/** Writes a PNG; safe on the loader thread. */
	private static boolean writeImage(Path file, NativeImage image) {
		if (file == null) return false;
		try {
			Files.createDirectories(file.getParent());
			image.writeTo(file);
			return true;
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not save map overview {}", file, e);
			return false;
		}
	}

	private static NativeImage read(Path file) {
		if (file == null || !Files.exists(file)) return null;
		try (InputStream in = Files.newInputStream(file)) {
			return NativeImage.read(in);
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not read map tile {}", file, e);
			return null;
		}
	}

	/** Saves and frees everything; called when leaving a world or dimension. */
	public void close() {
		for (Tile tile : tiles.values()) unload(tile);
		tiles.clear();
		for (Small small : smalls.values()) unload(small);
		smalls.clear();
		smallUnsaved.clear();
		generation++;
		pendingFull.clear();
		pendingSmall.clear();
		for (Loaded loaded; (loaded = finished.poll()) != null; ) {
			if (loaded.image() != null) loaded.image().close();
		}
		onDisk = null;
		smallOnDisk = null;
		queue.clear();
		queued.clear();
		mapped.clear();
		worldKey = null;
		dimension = null;
	}

	private Set<Long> onDisk() {
		if (onDisk == null) onDisk = index(folder());
		return onDisk;
	}

	private Set<Long> smallOnDisk() {
		if (smallOnDisk == null) smallOnDisk = index(folder() == null ? null : folder().resolve("overview"));
		return smallOnDisk;
	}

	/** The tiles saved in a folder, from their r.X.Z.png names. */
	private static Set<Long> index(Path folder) {
		Set<Long> keys = new HashSet<>();
		if (folder == null || !Files.isDirectory(folder)) return keys;
		try (var files = Files.list(folder)) {
			files.forEach(file -> {
				String[] parts = file.getFileName().toString().split("\\.");
				if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("png")) return;
				try {
					keys.add(ChunkPos.toLong(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
				} catch (NumberFormatException ignored) {
					// Not a tile.
				}
			});
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not list map tiles in {}", folder, e);
		}
		return keys;
	}

	private Path folder() {
		if (worldKey == null || dimension == null) return null;
		return BIConfig.dir().resolve(worldKey.replaceAll("[^A-Za-z0-9._-]", "_")).resolve("map")
				.resolve(dimension.replaceAll("[^A-Za-z0-9._-]", "_"));
	}

	private Path file(int rx, int rz) {
		Path folder = folder();
		return folder == null ? null : folder.resolve("r." + rx + "." + rz + ".png");
	}

	private Path smallFile(int rx, int rz) {
		Path folder = folder();
		return folder == null ? null : folder.resolve("overview").resolve("r." + rx + "." + rz + ".png");
	}
}
