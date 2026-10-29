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
 * <p>
 * Only a like-for-like look counts: the screen must be that container's (its slot count matches the block you
 * clicked, so a server menu that pops up instead isn't taken for it), the contents must have finished arriving
 * (unchanged for a few ticks), and the record must be of the same container (a double chest that's now single, or
 * the reverse, is just recorded afresh).
 */
public final class ContainerWatcher {
	private static final int PENDING_TICKS = 40;
	/** Contents count as arrived once they've stayed the same this many ticks. */
	private static final int STABLE_TICKS = 6;

	private final BaseManager manager;

	private Base pendingBase;
	private BlockPos pendingPos;
	private String pendingBlock;
	private int pendingSize;
	private String pendingTitle;
	private int pendingTicks;

	private HandledScreen<?> screen;
	private Base base;
	private BlockPos pos;
	private String block;
	private int size;
	private boolean captured;
	private Map<String, Integer> lastContents;
	private int stableTicks;

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
		if (!BIConfig.INSTANCE.trackContainers || !isStorage(world.getBlockEntity(clicked))) {
			// Clicked something else: whatever opens next is that, not the container clicked before.
			pendingBase = null;
			return;
		}
		// With bases inside bases, the smallest one owns the container.
		Base owner = manager.owner(BaseManager.dimensionKey(world), clicked);
		if (owner == null) return;
		pendingBase = owner;
		pendingPos = canonical(world, clicked);
		pendingBlock = Registries.BLOCK.getId(world.getBlockState(clicked).getBlock()).toString();
		pendingSize = slotsOf(world, clicked);
		pendingTitle = titleOf(world, clicked, pendingSize);
		pendingTicks = PENDING_TICKS;
	}

	/** The title its screen opens with: its custom name, or the block's ("Chest", "Large Chest", "Barrel"...). */
	private static String titleOf(ClientWorld world, BlockPos pos, int slots) {
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof net.minecraft.util.Nameable named && named.hasCustomName()) return named.getCustomName().getString();
		if (slots == 54) {
			// A double chest is named after either half that has a name, else "Large Chest".
			BlockState state = world.getBlockState(pos);
			if (world.getBlockEntity(pos.offset(ChestBlock.getFacing(state))) instanceof net.minecraft.util.Nameable other && other.hasCustomName()) {
				return other.getCustomName().getString();
			}
			return Text.translatable("container.chestDouble").getString();
		}
		return be instanceof net.minecraft.util.Nameable named ? named.getDisplayName().getString() : "";
	}

	/** How many slots the container at this spot has: 54 for a double chest, otherwise 27. */
	private static int slotsOf(ClientWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && ChestBlock.getDoubleBlockType(state) != DoubleBlockProperties.Type.SINGLE) return 54;
		return 27;
	}

	/** The container's own slots in an open screen (the player's inventory left out). */
	private static int containerSlots(ScreenHandler handler) {
		int n = 0;
		for (Slot slot : handler.slots) if (!(slot.inventory instanceof PlayerInventory)) n++;
		return n;
	}

	public void onScreenOpened(Screen opened) {
		if (pendingBase == null || !(opened instanceof HandledScreen<?> handled)) return;
		if (opened instanceof InventoryScreen || opened instanceof CreativeInventoryScreen) return;
		// Something else opened (a server's menu, say): not this container.
		if (containerSlots(handled.getScreenHandler()) != pendingSize || !opened.getTitle().getString().equals(pendingTitle)) {
			pendingBase = null;
			return;
		}
		screen = handled;
		base = pendingBase;
		pos = pendingPos;
		block = pendingBlock;
		size = pendingSize;
		captured = false;
		lastContents = null;
		stableTicks = 0;
		pendingBase = null;
		ScreenEvents.remove(opened).register(closed -> onClose());
		// Your own clicks and keys change the contents: judge it as it was before them.
		net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.beforeMouseClick(opened).register((s, click) -> beforeYouTouch());
		net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.beforeKeyPress(opened).register((s, key) -> beforeYouTouch());
	}

	public void tick(MinecraftClient client) {
		if (pendingBase != null && --pendingTicks <= 0) pendingBase = null;
		// The screen opens before its contents arrive; the revision becomes non-zero once they start to, and then
		// they must stay put a moment (some servers send them in parts).
		if (screen == null || captured || screen.getScreenHandler().getRevision() == 0) return;
		Map<String, Integer> now = contents(screen.getScreenHandler());
		if (!now.equals(lastContents)) {
			lastContents = now;
			stableTicks = 0;
			return;
		}
		if (++stableTicks < STABLE_TICKS) return;
		captured = true;
		compare(now);
	}

	/** You're about to move something: compare now with what had arrived, if it had settled at all. */
	private void beforeYouTouch() {
		if (screen == null || captured) return;
		captured = true;
		if (lastContents != null && stableTicks >= 2) compare(lastContents);
	}

	private void compare(Map<String, Integer> found) {
		Base.ContainerSnapshot approved = base.containers.get(pos.asLong());
		// Nothing to compare with, or a record of another shape of container (or from Hoard Eye): this look
		// just becomes the record.
		if (approved == null || approved.size != size) return;
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
			snapshot.size = size;
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
			// A hopper minecart running underneath pulls items too.
			net.minecraft.util.math.Box below = new net.minecraft.util.math.Box(block).expand(0.5, 0, 0.5).offset(0, -1, 0);
			if (!world.getEntitiesByClass(net.minecraft.entity.vehicle.HopperMinecartEntity.class, below, e -> true).isEmpty()) return true;
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
