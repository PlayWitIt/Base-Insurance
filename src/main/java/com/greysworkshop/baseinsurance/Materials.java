package com.greysworkshop.baseinsurance;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Works out what you need to rebuild a base, what you're carrying, and where the rest is stored. */
public final class Materials {
	public record Source(String label, BlockPos pos, int count, double distance) {}

	public record Need(Item item, int needed, int carried, List<Source> sources) {
		public int stored() {
			return sources.stream().mapToInt(Source::count).sum();
		}
	}

	private Materials() {}

	public static List<Need> compute(Base base, BaseManager manager, MinecraftClient client) {
		Map<Item, Integer> needed = new LinkedHashMap<>();
		for (Base.Change change : base.changes.values()) {
			if (change.kind() == Base.Kind.ADDED) continue;
			BlockState was = change.wasState();
			if (was == null || BlockStates.isSecondaryHalf(was)) continue;
			ItemStack icon = BlockStates.icon(was);
			if (!icon.isEmpty()) needed.merge(icon.getItem(), 1, Integer::sum);
		}

		Map<Item, Integer> carried = new HashMap<>();
		PlayerInventory inventory = client.player.getInventory();
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
			// Count what's inside shulker boxes you're carrying, too.
			ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
			if (contents != null) {
				contents.stream().forEach(inner -> carried.merge(inner.getItem(), inner.getCount(), Integer::sum));
			}
		}

		// Everything we know is in storage: this mod's snapshots in every base here, plus Container Peek's memory.
		String dimension = base.dimension;
		Map<Long, Map<String, Integer>> stores = new HashMap<>(ContainerPeekBridge.containers(manager.worldKey(), dimension));
		Map<Long, String> labels = new HashMap<>();
		for (Base other : manager.inDimension(dimension)) {
			for (Map.Entry<Long, Base.ContainerSnapshot> entry : other.containers.entrySet()) {
				// Skip containers that were destroyed; their contents are gone.
				Base.Change change = other.changes.get(entry.getKey());
				if (change != null && change.kind() != Base.Kind.ADDED) continue;
				stores.put(entry.getKey(), entry.getValue().items);
				labels.put(entry.getKey(), BlockStates.name(entry.getValue().block).getString());
			}
		}

		List<Need> result = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
			Item item = entry.getKey();
			String id = Registries.ITEM.getId(item).toString();
			List<Source> sources = new ArrayList<>();
			for (Map.Entry<Long, Map<String, Integer>> store : stores.entrySet()) {
				int count = store.getValue().getOrDefault(id, 0);
				if (count <= 0) continue;
				BlockPos pos = BlockPos.fromLong(store.getKey());
				String label = labels.getOrDefault(store.getKey(), "Container");
				sources.add(new Source(label, pos, count, Math.sqrt(client.player.squaredDistanceTo(pos.toCenterPos()))));
			}
			sources.sort((a, b) -> Double.compare(a.distance(), b.distance()));
			result.add(new Need(item, entry.getValue(), carried.getOrDefault(item, 0), sources));
		}
		result.sort((a, b) -> Integer.compare(b.needed(), a.needed()));
		return result;
	}
}
