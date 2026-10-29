package com.greysworkshop.baseinsurance;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Set;

/**
 * What the client can believe, on any server. Servers lie to clients in a few ways, and each lie has a limit:
 * <ul>
 *   <li>Anti-xray (Paper, Orebfuscator and others) fakes only blocks sealed in on every side, swapping them for
 *       stone, ores, obsidian, chests and the like. A block with an open side is always sent as it is.</li>
 *   <li>Container and entity hiders (anti-ESP) hide chests, item frames and the like that are out of sight or far
 *       off. One in plain view, close by, is always sent.</li>
 *   <li>Chunks arrive in stages, and the server corrects blocks after a moment. What stays the same for a while is
 *       real.</li>
 * </ul>
 * So a change only counts once it's on an exposed block, in clear line of sight, close by, and still there a second
 * later. That holds whatever the server runs.
 */
public final class Sight {
	/** Containers and the like are only judged this close: hiders reveal them nearby, in sight. */
	public static final double CONTAINER_RANGE = 16;

	/** Blocks anti-xray swaps in that aren't full cubes, but stand for one (so they don't make a neighbour look exposed). */
	private static final Set<Block> FAKE_SOLIDS = Set.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST);

	/** Blocks anti-xray uses as fakes, besides rock and ores (see {@link ChangeFilter}). */
	private static final Set<Block> FAKES = Set.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.MOSSY_COBBLESTONE, Blocks.CLAY,
			Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.SPAWNER, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK,
			Blocks.RAW_GOLD_BLOCK, Blocks.BEDROCK, Blocks.GRAVEL, Blocks.DIRT, Blocks.SAND);

	private Sight() {}

	/** Hides nothing behind it, as far as anti-xray goes: a full, opaque block (or a fake standing for one). */
	private static boolean seals(BlockState state) {
		return state.isOpaqueFullCube() || FAKE_SOLIDS.contains(state.getBlock());
	}

	/**
	 * Has an open side: some neighbour isn't a full, opaque block. Anti-xray never fakes these. (A neighbour that's
	 * itself a fake counts as sealing, so a fake can't make a sealed-in block look exposed.)
	 */
	public static boolean exposed(ClientWorld world, BlockPos pos) {
		BlockPos.Mutable n = new BlockPos.Mutable();
		for (Direction d : Direction.values()) {
			n.set(pos.getX() + d.getOffsetX(), pos.getY() + d.getOffsetY(), pos.getZ() + d.getOffsetZ());
			if (!seals(world.getBlockState(n))) return true;
		}
		return false;
	}

	/**
	 * Whether the block was exposed in the snapshot itself (by its neighbours there). Unknown neighbours count as
	 * sealing, so a block next to an unrecorded spot counts as sealed in.
	 */
	public static boolean exposedIn(Baseline baseline, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState n = baseline.get(pos.offset(d));
			if (n != null && !seals(n)) return true;
		}
		return false;
	}

	/** A block anti-xray could have put there in place of the real one. */
	public static boolean fakeable(BlockState state) {
		if (state == null) return false;
		return state.isAir() || FAKES.contains(state.getBlock()) || ChangeFilter.isRock(state)
				|| state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER);
	}

	/** Has a block entity (chests, signs, spawners...): what hiders hide. */
	public static boolean hidable(BlockState state) {
		return state != null && state.hasBlockEntity();
	}

	/**
	 * In clear line of sight from the eye: a straight line reaches the block (its middle, or the middle of a side
	 * facing you) without passing through any other block.
	 */
	public static boolean visible(ClientWorld world, Entity viewer, Vec3d eye, BlockPos pos) {
		Vec3d center = Vec3d.ofCenter(pos);
		if (clear(world, viewer, eye, center, pos)) return true;
		for (Direction d : Direction.values()) {
			Vec3d face = center.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
			// Only the sides that face you can be seen.
			if (face.subtract(center).dotProduct(eye.subtract(center)) <= 0) continue;
			if (clear(world, viewer, eye, face, pos)) return true;
		}
		return false;
	}

	/**
	 * Out of sight beyond doubt: every line to it, from your eye and from points a block around it, passes through a
	 * full, opaque block. Hiders look through glass, leaves and the like, and some look from more than one point, so a
	 * block seen past those isn't taken as hidden from them.
	 */
	public static boolean occluded(ClientWorld world, Vec3d eye, BlockPos pos) {
		Vec3d center = Vec3d.ofCenter(pos);
		for (int k = 0; k < 7; k++) {
			Vec3d from = k == 0 ? eye : eye.add(k == 1 ? 1 : k == 2 ? -1 : 0, k == 3 ? 1 : k == 4 ? -1 : 0, k == 5 ? 1 : k == 6 ? -1 : 0);
			if (!opaqueBetween(world, from, center, pos)) return false;
			for (Direction d : Direction.values()) {
				Vec3d face = center.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
				if (face.subtract(center).dotProduct(from.subtract(center)) > 0 && !opaqueBetween(world, from, face, pos)) return false;
			}
		}
		return true;
	}

	/** Whether a full, opaque block (other than the target) lies on the line. */
	private static boolean opaqueBetween(ClientWorld world, Vec3d from, Vec3d to, BlockPos target) {
		Boolean hit = net.minecraft.world.BlockView.raycast(from, to, null, (ctx, p) -> {
			if (p.equals(target)) return null;
			return world.getBlockState(p).isOpaqueFullCube() ? Boolean.TRUE : null;
		}, ctx -> Boolean.FALSE);
		return hit;
	}

	private static boolean clear(ClientWorld world, Entity viewer, Vec3d eye, Vec3d to, BlockPos target) {
		BlockHitResult hit = world.raycast(new RaycastContext(eye, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, viewer));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
	}
}
