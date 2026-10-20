package com.greysworkshop.baseinsurance;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Oxidizable;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a block differing from the snapshot is real damage, or just the world doing its thing
 * (crops growing, fire, snow, copper oxidizing, doors opening...).
 */
public final class ChangeFilter {
	/** Blocks that come and go on their own; a change to or from one of these is ignored. */
	private static final Set<Block> NATURAL = Set.of(
			Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.SNOW, Blocks.POWDER_SNOW,
			Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
			Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
			Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
			Blocks.SUGAR_CANE, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.CACTUS, Blocks.CACTUS_FLOWER,
			Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.BUBBLE_COLUMN,
			Blocks.PUMPKIN, Blocks.MELON, Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER,
			Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER,
			Blocks.POINTED_DRIPSTONE, Blocks.MOVING_PISTON, Blocks.PISTON_HEAD,
			Blocks.SWEET_BERRY_BUSH, Blocks.NETHER_WART, Blocks.COCOA, Blocks.FROGSPAWN, Blocks.TURTLE_EGG,
			Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.SCULK_VEIN
	);

	/** Blocks that turn into each other on their own count as the same block. */
	private static final Map<Block, Integer> GROUPS = new HashMap<>();

	static {
		group(1, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.FARMLAND, Blocks.DIRT_PATH, Blocks.MYCELIUM, Blocks.PODZOL,
				Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT);
		group(2, Blocks.WATER, Blocks.ICE, Blocks.FROSTED_ICE);
		group(3, Blocks.CAULDRON, Blocks.WATER_CAULDRON, Blocks.LAVA_CAULDRON, Blocks.POWDER_SNOW_CAULDRON);
		group(4, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL);
		group(5, Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM);
		group(6, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
	}

	private ChangeFilter() {}

	private static void group(int id, Block... blocks) {
		for (Block block : blocks) GROUPS.put(block, id);
	}

	/** True if {@code now} should count as unchanged from the snapshot's {@code was}. */
	public static boolean same(BlockState was, BlockState now) {
		Block a = was.getBlock();
		Block b = now.getBlock();
		if (a == b) return true;
		if (was.isAir() && now.isAir()) return true;
		if (Oxidizable.getUnaffectedOxidationBlock(a) == Oxidizable.getUnaffectedOxidationBlock(b)) return true;
		Integer ga = GROUPS.get(a);
		if (ga != null && ga.equals(GROUPS.get(b))) return true;
		// Anti-xray on many servers swaps ores and stone (or adds fake ores), so rock-for-rock changes are noise.
		if (isRock(was) && isRock(now)) return true;
		return isNatural(was) || isNatural(now) || isFlowing(was) || isFlowing(now);
	}

	private static boolean isRock(BlockState state) {
		return state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER)
				|| state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.GOLD_ORES)
				|| state.isIn(BlockTags.COPPER_ORES) || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES)
				|| state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES)
				|| state.isOf(Blocks.NETHER_QUARTZ_ORE) || state.isOf(Blocks.ANCIENT_DEBRIS) || state.isOf(Blocks.END_STONE);
	}

	/** Flowing (non-source) water and lava spreads on its own whenever a source is placed or removed. */
	private static boolean isFlowing(BlockState state) {
		return !state.getFluidState().isEmpty() && !state.getFluidState().isStill();
	}

	private static boolean isNatural(BlockState state) {
		if (NATURAL.contains(state.getBlock())) return true;
		if (state.isIn(BlockTags.SAPLINGS) || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.CROPS)) return true;
		if (BIConfig.INSTANCE.ignoredBlocks.isEmpty()) return false;
		Identifier id = Registries.BLOCK.getId(state.getBlock());
		return BIConfig.INSTANCE.ignoredBlocks.contains(id.toString());
	}
}
