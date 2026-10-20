package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The block snapshot of a base, stored per 16x16x16 chunk section as a palette of block states plus one
 * palette index per block. Sections made of a single block (like all air) skip the index array.
 */
public final class Baseline {
	private static final int VERSION = 1;

	public static final class Section {
		/** Palette entries can be null if a saved block no longer exists in the game. */
		final List<BlockState> palette = new ArrayList<>();
		/** Palette index per block, or null if every block is palette entry 0. */
		short[] data;

		public BlockState get(int index) {
			return palette.get(data == null ? 0 : data[index]);
		}

		public void set(int index, BlockState state) {
			int id = palette.indexOf(state);
			if (id < 0) {
				id = palette.size();
				palette.add(state);
			}
			if (data == null) {
				if (id == 0) return;
				data = new short[4096];
			}
			data[index] = (short) id;
		}
	}

	public final Map<Long, Section> sections = new HashMap<>();

	public static int index(int x, int y, int z) {
		return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
	}

	/** Returns the snapshot state at {@code pos}, or null if that part hasn't been captured. */
	public BlockState get(BlockPos pos) {
		Section section = sections.get(ChunkSectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		return section == null ? null : section.get(index(pos.getX(), pos.getY(), pos.getZ()));
	}

	public void set(BlockPos pos, BlockState state) {
		Section section = sections.get(ChunkSectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		if (section != null) section.set(index(pos.getX(), pos.getY(), pos.getZ()), state);
	}

	public static Section capture(ClientWorld world, long key) {
		int baseX = ChunkSectionPos.unpackX(key) << 4;
		int baseY = ChunkSectionPos.unpackY(key) << 4;
		int baseZ = ChunkSectionPos.unpackZ(key) << 4;
		Section section = new Section();
		Map<BlockState, Integer> ids = new IdentityHashMap<>();
		short[] data = new short[4096];
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = world.getBlockState(pos.set(baseX + x, baseY + y, baseZ + z));
					Integer id = ids.get(state);
					if (id == null) {
						id = section.palette.size();
						ids.put(state, id);
						section.palette.add(state);
					}
					data[index(x, y, z)] = (short) (int) id;
				}
			}
		}
		section.data = section.palette.size() == 1 ? null : data;
		return section;
	}

	public void write(Path file) throws IOException {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
			out.writeInt(VERSION);
			out.writeInt(sections.size());
			for (Map.Entry<Long, Section> entry : sections.entrySet()) {
				Section section = entry.getValue();
				out.writeLong(entry.getKey());
				out.writeShort(section.palette.size());
				for (BlockState state : section.palette) {
					out.writeUTF(state == null ? "minecraft:air" : BlockStates.toString(state));
				}
				out.writeBoolean(section.data != null);
				if (section.data != null) {
					for (short s : section.data) out.writeShort(s);
				}
			}
		}
		Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	public static Baseline read(Path file) throws IOException {
		Baseline baseline = new Baseline();
		if (!Files.exists(file)) return baseline;
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
			int version = in.readInt();
			if (version != VERSION) throw new IOException("Unknown snapshot version " + version);
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				long key = in.readLong();
				Section section = new Section();
				int paletteSize = in.readUnsignedShort();
				for (int p = 0; p < paletteSize; p++) {
					section.palette.add(BlockStates.parse(in.readUTF()));
				}
				if (in.readBoolean()) {
					section.data = new short[4096];
					for (int b = 0; b < 4096; b++) section.data[b] = in.readShort();
				}
				baseline.sections.put(key, section);
			}
		}
		return baseline;
	}
}
