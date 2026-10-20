package com.greysworkshop.baseinsurance;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

/** Converting block states to and from strings, and other small helpers. */
public final class BlockStates {
	private static final Map<String, BlockState> CACHE = new HashMap<>();

	private BlockStates() {}

	public static String toString(BlockState state) {
		return BlockArgumentParser.stringifyBlockState(state);
	}

	/** Parses a block-state string; returns null if the block no longer exists. */
	public static BlockState parse(String string) {
		if (string == null) return null;
		return CACHE.computeIfAbsent(string, s -> {
			try {
				return BlockArgumentParser.block(Registries.BLOCK, s, false).blockState();
			} catch (CommandSyntaxException e) {
				return null;
			}
		});
	}

	public static boolean isAirString(String string) {
		return string == null || string.startsWith("minecraft:air") || string.startsWith("minecraft:cave_air")
				|| string.startsWith("minecraft:void_air");
	}

	public static Text name(String string) {
		BlockState state = parse(string);
		return state == null ? Text.literal(string) : state.getBlock().getName();
	}

	/** The item that places this block, or empty for blocks without one (air, fire, water...). */
	public static ItemStack icon(BlockState state) {
		if (state == null) return ItemStack.EMPTY;
		Block block = state.getBlock();
		if (block == Blocks.WATER) return new ItemStack(Items.WATER_BUCKET);
		if (block == Blocks.LAVA) return new ItemStack(Items.LAVA_BUCKET);
		Item item = block.asItem();
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** Two-block things (doors, tall plants, beds) count once, from their lower/foot half. */
	public static boolean isSecondaryHalf(BlockState state) {
		if (state.contains(Properties.DOUBLE_BLOCK_HALF) && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return true;
		}
		return state.contains(Properties.BED_PART) && state.get(Properties.BED_PART) == BedPart.HEAD;
	}
}
