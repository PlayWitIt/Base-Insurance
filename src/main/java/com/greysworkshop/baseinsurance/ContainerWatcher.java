package com.greysworkshop.baseinsurance;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoubleBlockProperties;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches storage containers inside bases. When you open one, its contents (as the server sends them) are
 * compared to how you left it; anything missing is reported. When you close it, the new contents become
 * the approved state.
 */
public final class ContainerWatcher {
	private static final int PENDING_TICKS = 40;

	private final BaseManager manager;

	private Base pendingBase;
	private BlockPos pendingPos;
	private String pendingBlock;
	private int pendingTicks;

	private HandledScreen<?> screen;
	private Base base;
	private BlockPos pos;
	private String block;
	private boolean captured;

	public ContainerWatcher(BaseManager manager) {
		this.manager = manager;
	}

	/** Storage we track: chests (all kinds), barrels and shulker boxes. Hoppers and furnaces churn too much. */
	public static boolean isStorage(BlockEntity be) {
		return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
	}

	/** Both halves of a double chest map to the half with the lower coordinates. */
	public static BlockPos canonical(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && ChestBlock.getDoubleBlockType(state) != DoubleBlockProperties.Type.SINGLE) {
			BlockPos other = pos.offset(ChestBlock.getFacing(state));
			if (other.asLong() < pos.asLong()) return other;
		}
		return pos.toImmutable();
	}

	public void onUseBlock(ClientWorld world, BlockPos clicked) {
		if (!BIConfig.INSTANCE.trackContainers || !isStorage(world.getBlockEntity(clicked))) return;
		// With bases inside bases, the smallest one owns the container.
		Base owner = manager.owner(BaseManager.dimensionKey(world), clicked);
		if (owner == null) return;
		pendingBase = owner;
		pendingPos = canonical(world, clicked);
		pendingBlock = Registries.BLOCK.getId(world.getBlockState(clicked).getBlock()).toString();
		pendingTicks = PENDING_TICKS;
	}

	public void onScreenOpened(Screen opened) {
		if (pendingBase == null || !(opened instanceof HandledScreen<?> handled)) return;
		if (opened instanceof InventoryScreen || opened instanceof CreativeInventoryScreen) return;
		screen = handled;
		base = pendingBase;
		pos = pendingPos;
		block = pendingBlock;
		captured = false;
		pendingBase = null;
		ScreenEvents.remove(opened).register(closed -> onClose());
	}

	public void tick(MinecraftClient client) {
		if (pendingBase != null && --pendingTicks <= 0) pendingBase = null;
		// The screen opens before its contents arrive; the revision becomes non-zero once they have.
		if (screen != null && !captured && screen.getScreenHandler().getRevision() != 0) {
			captured = true;
			compare(contents(screen.getScreenHandler()));
		}
	}

	private void compare(Map<String, Integer> found) {
		Base.ContainerSnapshot approved = base.containers.get(pos.asLong());
		if (approved == null) return;
		Map<String, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : approved.items.entrySet()) {
			int lost = entry.getValue() - found.getOrDefault(entry.getKey(), 0);
			if (lost > 0) missing.put(itemName(entry.getKey()), lost);
		}
		Map<String, Integer> extra = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : found.entrySet()) {
			int gained = entry.getValue() - approved.items.getOrDefault(entry.getKey(), 0);
			if (gained > 0) extra.put(itemName(entry.getKey()), gained);
		}
		if (missing.isEmpty()) return;

		String what = BlockStates.name(block).getString() + " at " + Alerts.pos(pos);
		String text = what + " is missing " + Alerts.topCounts(missing, 6)
				+ " (last checked " + Alerts.time(approved.updated) + ")";
		if (!extra.isEmpty()) text += "; new items: " + Alerts.topCounts(extra, 3);
		// A hopper next to it can move items on its own, so that's only noted, not alerted.
		boolean hopper = hasHopper(MinecraftClient.getInstance().world, pos);
		if (hopper) text += " (a hopper is attached, so items may have moved on their own)";
		base.log(Base.EventType.CONTAINER, text);
		if (BIConfig.INSTANCE.theftAlerts && !hopper) Alerts.alert(Text.literal("⚠ " + text).formatted(Formatting.RED), true);
	}

	private void onClose() {
		if (screen == null) return;
		if (captured) {
			Base.ContainerSnapshot snapshot = new Base.ContainerSnapshot();
			snapshot.block = block;
			snapshot.items = contents(screen.getScreenHandler());
			snapshot.updated = System.currentTimeMillis();
			base.containers.put(pos.asLong(), snapshot);
			base.dirty = true;
		}
		screen = null;
		base = null;
	}

	/** True if a hopper touches the container (or the other half of a double chest). */
	private static boolean hasHopper(ClientWorld world, BlockPos pos) {
		if (world == null) return false;
		List<BlockPos> blocks = new ArrayList<>(List.of(pos));
		BlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && ChestBlock.getDoubleBlockType(state) != DoubleBlockProperties.Type.SINGLE) {
			blocks.add(pos.offset(ChestBlock.getFacing(state)));
		}
		for (BlockPos block : blocks) {
			for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
				if (world.getBlockState(block.offset(direction)).isOf(net.minecraft.block.Blocks.HOPPER)) return true;
			}
		}
		return false;
	}

	/** Totals of the container's own slots, leaving out the player's inventory. */
	private static Map<String, Integer> contents(ScreenHandler handler) {
		Map<String, Integer> totals = new LinkedHashMap<>();
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) continue;
			totals.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		return totals;
	}

	public static String itemName(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null || !Registries.ITEM.containsId(identifier)) return id;
		return Registries.ITEM.get(identifier).getName().getString();
	}

	public static List<String> describe(Map<String, Integer> items, int limit) {
		List<String> lines = new ArrayList<>();
		items.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(limit)
				.forEach(e -> lines.add(itemName(e.getKey()) + " ×" + e.getValue()));
		return lines;
	}
}
