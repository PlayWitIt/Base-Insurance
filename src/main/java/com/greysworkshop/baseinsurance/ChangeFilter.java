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
	/** Plants and other things that grow, spread or get harvested on their own. */
	private static final Set<Block> PLANTS = Set.of(
			Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
			Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
			Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
			Blocks.SUGAR_CANE, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.CACTUS, Blocks.CACTUS_FLOWER,
			Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
			Blocks.PUMPKIN, Blocks.MELON, Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER,
			Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER,
			Blocks.POINTED_DRIPSTONE, Blocks.SWEET_BERRY_BUSH, Blocks.NETHER_WART, Blocks.COCOA, Blocks.FROGSPAWN,
			Blocks.TURTLE_EGG, Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.SCULK_VEIN,
			Blocks.SNIFFER_EGG, Blocks.LEAF_LITTER, Blocks.WILDFLOWERS, Blocks.PINK_PETALS, Blocks.BUSH, Blocks.FIREFLY_BUSH,
			Blocks.SHORT_DRY_GRASS, Blocks.TALL_DRY_GRASS, Blocks.DEAD_BUSH, Blocks.HANGING_ROOTS, Blocks.SPORE_BLOSSOM,
			Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET,
			Blocks.PALE_HANGING_MOSS, Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_SPROUTS, Blocks.CRIMSON_FUNGUS,
			Blocks.WARPED_FUNGUS, Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_CROP, Blocks.PITCHER_PLANT
	);

	/** Fire, snow and similar that appear and vanish with the weather. */
	private static final Set<Block> WEATHER = Set.of(
			Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.SNOW, Blocks.POWDER_SNOW, Blocks.BUBBLE_COLUMN
	);

	private static final Set<Block> PISTON_PARTS = Set.of(Blocks.MOVING_PISTON, Blocks.PISTON_HEAD);

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
		// Coral dies out of water, and concrete powder sets in it.
		int id = 7;
		for (String coral : new String[] {"tube", "brain", "bubble", "fire", "horn"}) {
			for (String form : new String[] {"_coral", "_coral_block", "_coral_fan", "_coral_wall_fan"}) {
				group(id++, block(coral + form), block("dead_" + coral + form));
			}
		}
		for (net.minecraft.util.DyeColor color : net.minecraft.util.DyeColor.values()) {
			group(id++, block(color.asString() + "_concrete_powder"), block(color.asString() + "_concrete"));
		}
	}

	private static Block block(String path) {
		return Registries.BLOCK.get(Identifier.ofVanilla(path));
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
		BIConfig config = BIConfig.INSTANCE;
		// Anti-xray on many servers swaps ores and stone (or adds fake ores), so rock-for-rock changes are noise.
		if (config.ignoreOreSwaps && isRock(was) && isRock(now)) return true;
		// Natural blocks (and flowing fluids, and ignored types) only cancel a change when the other side is "soft"
		// too: grass coming and going is noise, but a chest that's now fire, or grass that's now lava, is damage.
		if (config.ignorePlants && was.isIn(BlockTags.SAPLINGS) && (now.isIn(BlockTags.LOGS) || now.isIn(BlockTags.LEAVES))) return true;
		boolean naturalWas = isNatural(was) || (config.ignoreFlowingFluids && isFlowing(was));
		boolean naturalNow = isNatural(now) || (config.ignoreFlowingFluids && isFlowing(now));
		return (naturalWas && isSoft(now)) || (naturalNow && isSoft(was));
	}

	/** Air, any fluid, or a natural block: something whose disappearance isn't damage on its own. */
	private static boolean isSoft(BlockState state) {
		return state.isAir() || !state.getFluidState().isEmpty() || isNatural(state);
	}

	static boolean isRock(BlockState state) {
		return state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.BASE_STONE_NETHER)
				|| state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.GOLD_ORES)
				|| state.isIn(BlockTags.COPPER_ORES) || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES)
				|| state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES)
				|| state.isOf(Blocks.NETHER_QUARTZ_ORE) || state.isOf(Blocks.ANCIENT_DEBRIS) || state.isOf(Blocks.END_STONE);
	}

	/** Natural terrain blocks (not building blocks like cobblestone, obsidian or planks). */
	private static final Set<Block> TERRAIN = Set.of(
			Blocks.GRAVEL, Blocks.CLAY, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.SMOOTH_BASALT, Blocks.BEDROCK,
			Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
			Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST,
			Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.GLOWSTONE, Blocks.SCULK, Blocks.MOSS_BLOCK
	);

	/**
	 * True for natural terrain: stone of all kinds, ores, dirt, sand, gravel and so on. With {@code fluids},
	 * water and lava count too (used when judging a whole section, not single blocks, so lava casts still show).
	 */
	public static boolean isTerrain(BlockState state, boolean fluids) {
		if (isRock(state) || TERRAIN.contains(state.getBlock())) return true;
		if (state.isIn(BlockTags.DIRT) || state.isIn(BlockTags.SAND) || state.isIn(BlockTags.TERRACOTTA)) return true;
		return fluids && !state.getFluidState().isEmpty();
	}

	/** Flowing (non-source) water and lava spreads on its own whenever a source is placed or removed. */
	private static boolean isFlowing(BlockState state) {
		return !state.getFluidState().isEmpty() && !state.getFluidState().isStill();
	}

	private static boolean isNatural(BlockState state) {
		BIConfig config = BIConfig.INSTANCE;
		Block block = state.getBlock();
		if (config.ignorePlants && (PLANTS.contains(block) || state.isIn(BlockTags.SAPLINGS) || state.isIn(BlockTags.LEAVES)
				|| state.isIn(BlockTags.CROPS) || state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.REPLACEABLE_BY_TREES))) return true;
		if (config.ignoreWeather && WEATHER.contains(block)) return true;
		if (config.ignorePistons && PISTON_PARTS.contains(block)) return true;
		if (BIConfig.INSTANCE.ignoredBlocks.isEmpty()) return false;
		Identifier id = Registries.BLOCK.getId(state.getBlock());
		return BIConfig.INSTANCE.ignoredBlocks.contains(id.toString());
	}
}
