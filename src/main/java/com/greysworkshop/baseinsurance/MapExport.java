package com.greysworkshop.baseinsurance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Saves the map as a PNG in the screenshots folder: what's on screen, or everything ever explored in this dimension.
 * One pixel per block, unless that would be wider or taller than {@link #MAX_SIDE} pixels; then every 2nd, 4th, ...
 * block. Written a strip at a time on a background thread, so even a huge world needs little memory.
 */
public final class MapExport {
	/** Bigger than this and most picture viewers give up. */
	public static final int MAX_SIDE = 16384;
	/** Where nothing's been explored: the map's dark background. */
	private static final int EMPTY = 0xFF101014;
	private static final AtomicBoolean BUSY = new AtomicBoolean();
	/** The last picture saved, and its size and scale; for the self-test. */
	public static volatile Path last;
	public static volatile int lastWidth, lastHeight, lastScale;

	private MapExport() {}

	public static boolean busy() {
		return BUSY.get();
	}

	/** Blocks per pixel for an area this big. */
	public static int scaleFor(long across, long deep) {
		int s = 1;
		while ((across + s - 1) / s > MAX_SIDE || (deep + s - 1) / s > MAX_SIDE) s *= 2;
		return s;
	}

	/** Everything explored in this dimension. */
	public static void exportAll(MinecraftClient client) {
		int[] b = BaseInsuranceClient.INSTANCE.worldMap.exploredBounds();
		if (b == null) {
			say(client, Text.literal("Nothing mapped here yet: walk around and the map fills in.").formatted(Formatting.YELLOW));
			return;
		}
		export(client, b[0], b[1], b[2] - 1, b[3] - 1, "whole");
	}

	/** Blocks minX..maxX, minZ..maxZ (inclusive). */
	public static void export(MinecraftClient client, int minX, int minZ, int maxX, int maxZ, String what) {
		WorldMap map = BaseInsuranceClient.INSTANCE.worldMap;
		Path folder = map.folderPath();
		if (folder == null || client.world == null) {
			say(client, Text.literal("Join a world first.").formatted(Formatting.YELLOW));
			return;
		}
		if (!BUSY.compareAndSet(false, true)) {
			say(client, Text.literal("Still saving the last picture.").formatted(Formatting.YELLOW));
			return;
		}
		// What's been seen up to now goes in too.
		map.save();
		long across = (long) maxX - minX + 1, deep = (long) maxZ - minZ + 1;
		int scale = scaleFor(across, deep);
		int w = (int) ((across + scale - 1) / scale), h = (int) ((deep + scale - 1) / scale);
		String world = folder.getParent().getParent().getFileName().toString();
		String dim = folder.getFileName().toString().replaceFirst("^minecraft_", "");
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT));
		Path out = client.runDirectory.toPath().resolve("screenshots").resolve("map_" + world + "_" + dim + "_" + what + "_" + stamp + ".png");
		say(client, Text.literal(String.format(Locale.ROOT, "Saving the map as a picture: %,d × %,d pixels%s…", w, h,
				scale == 1 ? "" : " (1 pixel = " + scale + " × " + scale + " blocks)")).formatted(Formatting.GRAY));
		Thread t = new Thread(() -> {
			try {
				write(folder, minX, minZ, w, h, scale, out);
				last = out;
				lastWidth = w;
				lastHeight = h;
				lastScale = scale;
				Text link = Text.literal(out.getFileName().toString()).formatted(Formatting.UNDERLINE, Formatting.AQUA)
						.styled(s -> s.withClickEvent(new ClickEvent.OpenFile(out)));
				client.execute(() -> say(client, Text.literal("Map saved: ").formatted(Formatting.GREEN).append(link)
						.append(Text.literal(String.format(Locale.ROOT, " (%,d × %,d, %s)", w, h, size(out))).formatted(Formatting.GRAY))));
			} catch (IOException | RuntimeException e) {
				BaseInsuranceClient.LOGGER.warn("Could not save the map picture", e);
				client.execute(() -> say(client, Text.literal("Couldn't save the map picture: " + e.getMessage()).formatted(Formatting.RED)));
			} finally {
				BUSY.set(false);
			}
		}, "Base Insurance map picture");
		t.setDaemon(true);
		t.start();
	}

	private static String size(Path file) {
		try {
			long b = Files.size(file);
			return b >= 1 << 20 ? String.format(Locale.ROOT, "%.1f MB", b / 1048576.0) : (b >> 10) + " KB";
		} catch (IOException e) {
			return "?";
		}
	}

	private static void say(MinecraftClient client, Text text) {
		if (client.player != null) client.player.sendMessage(Text.literal("[Base Insurance] ").formatted(Formatting.GOLD).append(text), false);
	}

	/** The picture, written one strip of map tiles at a time. */
	static void write(Path folder, int minX, int minZ, int w, int h, int scale, Path out) throws IOException {
		Set<Long> tiles = new HashSet<>();
		try (var files = Files.list(folder)) {
			files.forEach(f -> {
				String[] p = f.getFileName().toString().split("\\.");
				if (p.length == 4 && p[0].equals("r") && p[3].equals("png")) {
					try {
						tiles.add(ChunkPos.toLong(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
					} catch (NumberFormatException ignored) {
						// Not a tile.
					}
				}
			});
		}
		int T = WorldMap.TILE;
		Files.createDirectories(out.getParent());
		Path tmp = out.resolveSibling(out.getFileName() + ".part");
		try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
			file.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
			ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
			DataOutputStream d = new DataOutputStream(ihdr);
			d.writeInt(w);
			d.writeInt(h);
			d.write(new byte[] {8, 6, 0, 0, 0});
			chunk(file, "IHDR", ihdr.toByteArray());
			try (DeflaterOutputStream z = new DeflaterOutputStream(new ChunkStream(file), new Deflater(6), 1 << 16)) {
				byte[] row = new byte[1 + w * 4];
				int oy = 0;
				while (oy < h) {
					// The output rows that come from this row of tiles.
					long bz = minZ + (long) oy * scale;
					int tz = Math.floorDiv((int) bz, T);
					int rows = 0;
					while (oy + rows < h && Math.floorDiv((int) (minZ + (long) (oy + rows) * scale), T) == tz) rows++;
					int[] band = new int[rows * w];
					java.util.Arrays.fill(band, EMPTY);
					int tx0 = Math.floorDiv(minX, T), tx1 = Math.floorDiv((int) (minX + (long) (w - 1) * scale), T);
					for (int tx = tx0; tx <= tx1; tx++) {
						if (!tiles.contains(ChunkPos.toLong(tx, tz))) continue;
						// The output columns inside this tile (none, when zoomed far out and it falls between them).
						long first = Math.max(0, ceilDiv((long) tx * T - minX, scale));
						long lastCol = Math.min(w - 1, Math.floorDiv((long) tx * T + T - 1 - minX, scale));
						if (first > lastCol) continue;
						int[] px = readTile(folder.resolve("r." + tx + "." + tz + ".png"));
						if (px == null) continue;
						for (int r = 0; r < rows; r++) {
							int lz = Math.floorMod((int) (minZ + (long) (oy + r) * scale), T);
							for (long ox = first; ox <= lastCol; ox++) {
								int lx = (int) (minX + ox * scale - (long) tx * T);
								int c = px[lz * T + lx];
								if ((c >>> 24) != 0) band[r * w + (int) ox] = c | 0xFF000000;
							}
						}
					}
					for (int r = 0; r < rows; r++) {
						row[0] = 0;
						for (int x = 0; x < w; x++) {
							int c = band[r * w + x];
							int i = 1 + x * 4;
							row[i] = (byte) (c >> 16);
							row[i + 1] = (byte) (c >> 8);
							row[i + 2] = (byte) c;
							row[i + 3] = (byte) (c >>> 24);
						}
						z.write(row);
					}
					oy += rows;
				}
			}
			chunk(file, "IEND", new byte[0]);
		}
		Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private static long ceilDiv(long a, long b) {
		return -Math.floorDiv(-a, b);
	}

	private static int[] readTile(Path file) {
		try (InputStream in = Files.newInputStream(file); NativeImage image = NativeImage.read(in)) {
			if (image.getWidth() != WorldMap.TILE || image.getHeight() != WorldMap.TILE) return null;
			return image.copyPixelsArgb();
		} catch (IOException e) {
			BaseInsuranceClient.LOGGER.warn("Could not read map tile {}", file, e);
			return null;
		}
	}

	private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
		DataOutputStream d = new DataOutputStream(out);
		d.writeInt(data.length);
		byte[] t = type.getBytes(StandardCharsets.US_ASCII);
		d.write(t);
		d.write(data);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		d.writeInt((int) crc.getValue());
	}

	/** Compressed picture data, cut into IDAT chunks as it comes. */
	private static final class ChunkStream extends OutputStream {
		private final OutputStream out;
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 17);

		ChunkStream(OutputStream out) {
			this.out = out;
		}

		@Override
		public void write(int b) throws IOException {
			buf.write(b);
			if (buf.size() >= 1 << 17) flushChunk();
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			buf.write(b, off, len);
			if (buf.size() >= 1 << 17) flushChunk();
		}

		private void flushChunk() throws IOException {
			if (buf.size() == 0) return;
			chunk(out, "IDAT", buf.toByteArray());
			buf.reset();
		}

		@Override
		public void close() throws IOException {
			flushChunk();
		}
	}
}
